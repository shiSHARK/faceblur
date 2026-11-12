// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSizing
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.JpegOutput
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import kotlin.math.ln
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A runner whose one face depends on the tensor it receives: the anchor cell (stride 32, one of the inner 18 x 18
 * cells) and the score come from a hash of every input float, and each input's hash is appended to [seen]. Two runs
 * give the same faces only for the same tensors (up to a hash collision), so a difference in what either mode hands
 * the detector shows in the faces and in [seen].
 */
private class InputFaceRunner(private val seen: MutableList<Int>) : TensorRunner by OneFaceRunner() {
    private val layout = OneFaceRunner()

    override fun run(input: FloatArray): Map<String, FloatArray> {
        var h = FNV_OFFSET
        for (f in input) h = (h xor f.toRawBits()) * FNV_PRIME
        seen += h
        val mix = fmix(h)
        val cell = (1 + ((mix ushr 8) and 0xFFFF) % 18) * 20 + 1 + (mix and 0xFF) % 18
        val score = 0.8f + ((mix ushr 24) and 0x3F) / 420f
        val m = layout.run(input)
        val cls = m.getValue("cls_32").also { it.fill(0f) }
        val obj = m.getValue("obj_32").also { it.fill(0f) }
        val box = m.getValue("bbox_32").also { it.fill(0f) }
        cls[cell] = score
        obj[cell] = score
        box[cell * 4 + 2] = ln(2f)
        box[cell * 4 + 3] = ln(2f)
        return m
    }

    private fun fmix(v: Int): Int {
        var x = v xor (v ushr 16)
        x *= 0x85EBCA6B.toInt()
        x = x xor (x ushr 13)
        x *= 0xC2B2AE35.toInt()
        return x xor (x ushr 16)
    }

    private companion object {
        const val FNV_OFFSET = -0x7EE3623B // 2166136261 as a signed Int
        const val FNV_PRIME = 0x01000193
    }
}

/**
 * ST-030 on the simulator (`ModeAgreementIosTest`): file mode and in-memory mode hand the detector the same tensors and
 * give the same faces, the same masked pixels and the same JPEG, when both run on the pixels of the same decode
 * (`maxOutputLongSide` 0). Inputs: the registered fixtures (22 public ones, 12 more in the local gate; 640 x 640 PNG) and inputs generated from them
 * at test time (JPEG with orientation 6 and 8, HEIC with orientation 6, one enlarged JPEG above the working size). The
 * runtime is the fake glue on the real Kotlin adapter with [InputFaceRunner] (faces from the input); the real ONNX
 * Runtime runs only in the Swift package's tests.
 */
class ModeAgreementIosTest {
    /** The hash of every tensor the detector received during the current leg. */
    private val seen = ArrayList<Int>()

    @AfterTest
    fun cleanUp() = FaceBlurRuntime.installImageCodec(null)

    private fun masker(): FaceMasker = IosFiles.masker { InputFaceRunner(seen) }

    private class Run(
        val inputs: List<Int>,
        val faces: List<FaceBox>,
        val masks: List<Mask>,
        val pixels: IntArray,
        val jpeg: ByteArray,
    )

    /** File mode through the common orchestration; the pixels are those handed to the encoder. */
    private fun fileMode(masker: FaceMasker, input: ByteArray): Run {
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        seen.clear()
        try {
            val (r, out) = IosFiles.maskBytes(masker, input)
            assertEquals(1, codec.decodes)
            return Run(seen.toList(), r.faces, r.masks, requireNotNull(codec.encodedPixels), out)
        } finally {
            FaceBlurRuntime.installImageCodec(null)
        }
    }

    /** The sizes file mode plans for [input] (default options). */
    private fun sizesOf(input: ByteArray) = IosImageCodec.open(FileSource.Bytes(input), CallContext(null)).use {
        FileSizing.plan(it.size.width, it.size.height, 1, FileOptions())
    }

    /**
     * In-memory mode on the same decode (the library's codec at the same working size), then the same encoder and
     * rebuild; [plan] given: `applyMasks` with it instead of `maskInPlace`.
     */
    private fun inMemory(masker: FaceMasker, input: ByteArray, plan: MaskPlan? = null): Run {
        val working = sizesOf(input).working
        seen.clear()
        IosImageCodec.open(FileSource.Bytes(input), CallContext(null)).use { opened ->
            opened.decode(working).use { img ->
                val r = if (plan == null) masker.maskInPlace(img.pixels) else masker.applyMasks(img.pixels, plan)
                val px = IntArray(working.width * working.height)
                img.pixels.readPixels(0, 0, working.width, working.height, px)
                val jpeg = JpegOutput.finish(img.encodeJpeg(90), null, MetadataPolicy.StripAll, working).bytes
                return Run(seen.toList(), r.faces, r.masks, px, jpeg)
            }
        }
    }

    private fun sameFaces(a: List<FaceBox>, b: List<FaceBox>) = a.size == b.size && a.indices.all {
        a[it].left == b[it].left && a[it].top == b[it].top && a[it].right == b[it].right &&
            a[it].bottom == b[it].bottom && a[it].score == b[it].score
    }

    /** What differs between two runs; empty when they agree. */
    private fun differences(a: Run, b: Run): List<String> = listOfNotNull(
        "detector input".takeIf { a.inputs != b.inputs },
        "faces".takeIf { !sameFaces(a.faces, b.faces) },
        "masks".takeIf { a.masks != b.masks },
        "masked pixels".takeIf { !a.pixels.contentEquals(b.pixels) },
        "encoded output".takeIf { !a.jpeg.contentEquals(b.jpeg) },
    )

    /** [image] encoded as [type] with [orientation] and the canned metadata; releases [image]. */
    private fun encoded(image: CGImageRef, type: String, orientation: Int): ByteArray {
        val props = IosFiles.cannedProperties(orientation)
        try {
            return IosFiles.encode(image, type, props)
        } finally {
            CFRelease(props)
            CGImageRelease(image)
        }
    }

    /** The registered fixture [png] re-encoded at test time as [type] with [orientation]. */
    private fun reencoded(png: ByteArray, type: String, orientation: Int) =
        encoded(IosFiles.image(png), type, orientation)

    /** The fixture [png] enlarged (nearest neighbour) to [w] x [h] stored pixels, as a JPEG with [orientation]. */
    private fun enlarged(png: ByteArray, w: Int, h: Int, orientation: Int): ByteArray {
        val (sw, sh) = IosFiles.storedSize(png)
        val px = IosImageCodec.open(FileSource.Bytes(png), CallContext(null)).use { opened ->
            opened.decode(Size(sw, sh)).use { img ->
                IntArray(sw * sh).also { img.pixels.readPixels(0, 0, sw, sh, it) }
            }
        }
        val big = IntArray(w * h) { px[(it / w) * sh / h * sw + (it % w) * sw / w] }
        return encoded(IosFiles.imageOf(w, h, big), "public.jpeg", orientation)
    }

    /** The registered fixtures as stored, then the inputs generated from the first and the last one. */
    private fun inputs(): List<Pair<String, ByteArray>> {
        val fixtures = IosFiles.publicFixtures()
        assertTrue(IosFiles.localCount() in setOf(0, 12), "the local images are all there or not at all")
        assertEquals(
            22 + IosFiles.localCount(),
            fixtures.size,
            "the registered set (22 public, 12 more in the local gate)",
        )
        val stored = fixtures.map { it.substringAfterLast('/') to IosFiles.read(it) }
        val generated = listOf(stored.first(), stored.last()).flatMap { (name, png) ->
            listOf(
                "$name as JPEG, orientation 6" to reencoded(png, "public.jpeg", 6),
                "$name as JPEG, orientation 8" to reencoded(png, "public.jpeg", 8),
                "$name as HEIC, orientation 6" to reencoded(png, "public.heic", 6),
            )
        }
        val (name, png) = stored.first()
        val large = "$name enlarged to $LARGE_W x $LARGE_H, JPEG, orientation 6" to
            enlarged(png, LARGE_W, LARGE_H, 6)
        return stored + generated + large
    }

    @Test
    fun fileModeAndInMemoryModeAgreeOnEveryPublicFixture() {
        val inputs = inputs()
        var withMasks = 0
        var downscaled = 0
        masker().use { masker ->
            for ((name, input) in inputs) {
                val sizes = sizesOf(input)
                if (sizes.working != sizes.source) downscaled++
                val file = fileMode(masker, input)
                val memory = inMemory(masker, input)
                assertTrue(file.inputs.isNotEmpty() && file.faces.isNotEmpty(), "$name: the detector saw a face")
                assertEquals(emptyList(), differences(file, memory), name)
                // ST-030 AC4: a second run of the same call gives the same bytes.
                assertContentEquals(file.jpeg, fileMode(masker, input).jpeg, "$name: deterministic")
                if (file.masks.isNotEmpty()) withMasks++
            }
        }
        assertEquals(inputs.size, withMasks, "every input was masked")
        assertEquals(1, downscaled, "exactly the enlarged input takes the downscale path")
    }

    /** Negative control of the agreement check: one mode gets another orientation of the image, or another image. */
    @Test
    fun theAgreementCheckSeesAnotherOrientationOrAnotherImage() {
        val fixtures = IosFiles.publicFixtures()
        val a = IosFiles.read(fixtures.first())
        val b = IosFiles.read(fixtures.last())
        val cases = listOf(
            "orientation 6 in file mode, 8 in memory" to
                (reencoded(a, "public.jpeg", 6) to reencoded(a, "public.jpeg", 8)),
            "another image in memory" to (a to b),
        )
        masker().use { masker ->
            for ((label, pair) in cases) {
                val file = fileMode(masker, pair.first)
                val found = differences(file, inMemory(masker, pair.second))
                assertTrue("detector input" in found && "faces" in found, "$label: $found")
                assertEquals(emptyList(), differences(file, inMemory(masker, pair.first)), "$label: same input agrees")
            }
        }
    }

    /** ST-030 AC2: the comparison sees a single mask moved by one pixel. */
    @Test
    fun aMaskShiftedByOnePixelIsSeen() {
        val path = IosFiles.publicFixtures().first()
        val input = IosFiles.read(path)
        masker().use { masker ->
            val file = fileMode(masker, input)
            val working = sizesOf(input).working
            val m = file.masks.first()
            val shifted =
                listOf(Mask.exact(m.shape, m.centerX + 1f, m.centerY, m.radiusX, m.radiusY)) + file.masks.drop(1)
            val moved = inMemory(masker, input, MaskPlan(working.width, working.height, shifted))
            assertFalse(file.pixels.contentEquals(moved.pixels), "a 1 px shift changes the masked pixels")
            assertFalse(file.jpeg.contentEquals(moved.jpeg), "and the output")
            val same = inMemory(masker, input, MaskPlan(working.width, working.height, file.masks))
            assertContentEquals(file.pixels, same.pixels, "the unshifted plan gives the file-mode pixels")
        }
    }

    private companion object {
        /** Stored size of the enlarged input: above the default working size on the long side. */
        const val LARGE_W = 3840
        const val LARGE_H = 2880
    }
}
