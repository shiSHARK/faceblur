// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OutputFormat
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.ReservedBytes
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRelease
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Counts and observes the codec steps of file mode (decode once, encode once; the masked pixels before encoding). */
internal class ObservingCodec(private val inner: ImageCodec = IosImageCodec) : ImageCodec {
    var opens = 0
    var decodes = 0
    var encodes = 0
    var scales = 0
    var decodedSize: Size? = null
    var encodedPixels: IntArray? = null

    /** Runs after each encoding (the cancellation test requests cancellation there). */
    var afterEncode: () -> Unit = {}

    /** Runs before the source is opened (the negative control of the snapshot test copies the input there). */
    var beforeOpen: (FileSource) -> Unit = {}

    override fun open(source: FileSource, call: CallContext): OpenedImage {
        opens++
        beforeOpen(source)
        val opened = inner.open(source, call)
        return object : OpenedImage by opened {
            override fun decode(working: Size): WorkingImage {
                decodes++
                decodedSize = working
                return observe(opened.decode(working))
            }
        }
    }

    private fun observe(image: WorkingImage): WorkingImage = object : WorkingImage by image {
        override fun scaledTo(output: Size): WorkingImage {
            scales++
            return observe(image.scaledTo(output))
        }

        override fun encodeJpeg(quality: Int): ByteArray {
            encodes++
            val p: MutablePixelImage = image.pixels
            encodedPixels = IntArray(p.width * p.height).also { p.readPixels(0, 0, p.width, p.height, it) }
            return image.encodeJpeg(quality).also { afterEncode() }
        }
    }
}

/**
 * ST-028 and ST-029 on the simulator: the iOS file-mode codec (ImageIO decode with the orientation baked in,
 * CoreGraphics drawing, vImage scaling, ImageIO JPEG encoding) behind the common orchestration, with the real Kotlin
 * glue adapter and a fake runtime. Inputs are generated at test time from synthetic pixels; nothing is committed.
 */
class IosFileModeTest {
    private val dirs = ArrayList<String>()

    private fun dir() = IosFiles.newDir().also { dirs += it }

    @AfterTest
    fun cleanUp() {
        FaceBlurRuntime.installImageCodec(null)
        dirs.forEach { IosFiles.removeDir(it) }
        assertEquals(0L, ReservedBytes.current(), "no bytes left reserved")
    }

    /** A W x H opaque image with a red block in the stored top-left corner and a blue one in the bottom-right. */
    private fun marked(w: Int, h: Int): IntArray {
        val px = gradientPixels(w, h)
        for (y in 0 until h / 8) for (x in 0 until w / 8) px[y * w + x] = 0xFFFF0000.toInt()
        for (y in h - h / 8 until h) for (x in w - w / 8 until w) px[y * w + x] = 0xFF0000FF.toInt()
        return px
    }

    private fun encodeMarked(
        w: Int,
        h: Int,
        type: String,
        orientation: Int,
        xmp: Boolean = false,
        aux: Set<IosFiles.Aux> = emptySet(),
    ): ByteArray = requireNotNull(encodeMarkedOrNull(w, h, type, orientation, xmp, aux)) { "ImageIO refused $type" }

    private fun encodeMarkedOrNull(
        w: Int,
        h: Int,
        type: String,
        orientation: Int,
        xmp: Boolean,
        aux: Set<IosFiles.Aux>,
    ): ByteArray? {
        val img = IosFiles.imageOf(w, h, marked(w, h))
        val props = IosFiles.cannedProperties(orientation)
        try {
            return IosFiles.encodeOrNull(img, type, props, xmp, aux, auxMetadata = false)
                ?: IosFiles.encodeOrNull(img, type, props, xmp, aux, auxMetadata = true)
        } finally {
            platform.CoreFoundation.CFRelease(props)
            CGImageRelease(img)
        }
    }

    /** The decoded output pixel (ARGB) at ([x], [y]) through the library's own codec. */
    private fun pixelsOf(jpeg: ByteArray): Triple<Int, Int, IntArray> {
        val opened = IosImageCodec.open(FileSource.Bytes(jpeg), CallContext(null))
        opened.use {
            val w = it.size.width
            val h = it.size.height
            it.decode(it.size).use { img ->
                val px = IntArray(w * h)
                img.pixels.readPixels(0, 0, w, h, px)
                return Triple(w, h, px)
            }
        }
    }

    private fun isRed(v: Int) = ((v shr 16) and 0xFF) > 200 && ((v shr 8) and 0xFF) < 60 && (v and 0xFF) < 60

    private fun isBlue(v: Int) = ((v shr 16) and 0xFF) < 60 && ((v shr 8) and 0xFF) < 60 && (v and 0xFF) > 200

    private fun assertCleanOutput(out: ByteArray, label: String) {
        IosFiles.keepForChecker("outputs", "$label.jpg", out)
        val seen = IosFiles.inspect(out)
        assertTrue(seen.clean, "$label: $seen")
        assertEquals(1L, seen.orientation, "$label: orientation written as 1")
        assertEquals(0xFF, out[out.size - 2].toInt() and 0xFF, "$label: EOI is last")
        assertEquals(0xD9, out[out.size - 1].toInt() and 0xFF, "$label: EOI is last")
        assertEquals(
            emptyList(),
            io.github.shishark.faceblur.filemode.OutputValidator.findings(
                out,
                io.github.shishark.faceblur.MetadataPolicy.StripAll,
            ),
            "$label: the library's output check",
        )
    }

    // ---------------------------------------------------------------- ST-028 AC1 / AC9: formats and orientation

    @Test
    fun jpegHeicAndPngWithOrientationSixComeOutUprightMaskedAndWithoutMetadata() {
        val w = 800
        val h = 600
        val inputs = listOf(
            "jpeg" to encodeMarked(w, h, "public.jpeg", 6),
            "heic" to encodeMarked(w, h, "public.heic", 6),
            "png" to encodeMarked(w, h, "public.png", 6),
        )
        for ((label, input) in inputs) {
            val inputCopy = input.copyOf()
            assertFalse(IosFiles.inspect(input).clean, "$label: the inspection flags the input (negative control)")
            val codec = ObservingCodec()
            FaceBlurRuntime.installImageCodec(codec)
            val (result, out) = IosFiles.masker { NoFaceRunner() }.use { IosFiles.maskBytes(it, input) }
            assertContentEquals(inputCopy, input, "$label: input unchanged")
            assertEquals(Size(h, w), Size(result.width, result.height), "$label: upright working size")
            assertEquals(h to w, IosFiles.storedSize(out), "$label: output stored upright")
            val (ow, oh, px) = pixelsOf(out)
            // Orientation 6 turns the stored top-left corner to the top-right, the bottom-right to the bottom-left.
            assertTrue(isRed(px[5 * ow + (ow - 6)]), "$label: red block at the top right")
            assertTrue(isBlue(px[(oh - 6) * ow + 5]), "$label: blue block at the bottom left")
            assertFalse(isRed(px[5 * ow + 5]), "$label: nothing red at the top left")
            assertCleanOutput(out, label)
            assertEquals(1, codec.decodes, "$label: one decode")
            assertEquals(1, codec.encodes, "$label: one encode")
        }
    }

    @Test
    fun aMaskedFaceIsOpaqueInTheOutput() {
        val input = encodeMarked(1000, 750, "public.jpeg", 1)
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        val (result, out) = IosFiles.masker().use { IosFiles.maskBytes(it, input) }
        assertTrue(result.faces.isNotEmpty(), "the fake runtime reports a face")
        assertTrue(result.masks.isNotEmpty())
        assertCleanOutput(out, "masked")
        // The masked pixels that went into the encoder differ from the decoded source inside every face box.
        val decoded = pixelsOf(input)
        val masked = requireNotNull(codec.encodedPixels)
        for (f in result.faces) {
            val cx = ((f.left + f.right) / 2).toInt()
            val cy = ((f.top + f.bottom) / 2).toInt()
            assertNotEquals(decoded.third[cy * 1000 + cx], masked[cy * 1000 + cx], "face centre is masked")
        }
    }

    // ---------------------------------------------------------------- ST-028 AC2, AC3, AC8: sizes, decode once

    @Test
    fun aLargeSourceIsDecodedOnceAtTheWorkingSizeAndScaledAfterMasking() {
        val input = encodeMarked(5000, 3750, "public.jpeg", 1)
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        val (r, out) = IosFiles.masker().use {
            IosFiles.maskBytes(it, input, FileOptions(maxOutputLongSide = 1000))
        }
        assertEquals(1, codec.opens)
        assertEquals(1, codec.decodes, "decode once")
        assertEquals(Size(3500, 2625), codec.decodedSize, "decoded at the working size")
        assertEquals(1, codec.scales, "scaled once after masking")
        assertEquals(1, codec.encodes, "encode once")
        assertEquals(
            listOf(5000, 3750, 3500, 2625, 1000, 750),
            listOf(
                r.sourceWidth,
                r.sourceHeight,
                r.width,
                r.height,
                r.outputWidth,
                r.outputHeight,
            ),
        )
        assertEquals(out.size.toLong(), r.outputBytes)
        assertEquals(1000 to 750, IosFiles.storedSize(out))
        assertTrue(r.faces.all { it.right <= 3500f && it.bottom <= 2625f }, "faces in working pixels")
        assertTrue(r.timings.totalMs > 0.0)
        assertCleanOutput(out, "scaled")
    }

    @Test
    fun maxLongSideAndQualityChangeTheOutputAsSet() {
        val input = encodeMarked(3600, 2700, "public.jpeg", 1)
        val sizes = listOf(3500 to Size(3500, 2625), 0 to Size(3600, 2700))
        for ((max, expected) in sizes) {
            val (r, out) = IosFiles.masker { NoFaceRunner() }.use {
                IosFiles.maskBytes(it, input, FileOptions(maxLongSide = max))
            }
            assertEquals(expected, Size(r.outputWidth, r.outputHeight), "maxLongSide $max")
            assertEquals(expected.width to expected.height, IosFiles.storedSize(out))
        }
        val small = encodeMarked(1200, 900, "public.jpeg", 1)
        val q50 = IosFiles.masker { NoFaceRunner() }.use {
            IosFiles.maskBytes(it, small, FileOptions(format = OutputFormat.Jpeg(50))).second
        }
        val q90 = IosFiles.masker { NoFaceRunner() }.use {
            IosFiles.maskBytes(it, small, FileOptions(format = OutputFormat.Jpeg(90))).second
        }
        assertTrue(q50.size < q90.size, "quality 50 gives a smaller file than 90")
        assertCleanOutput(q50, "q50")
    }

    // ---------------------------------------------------------------- ST-028 AC6, AC7: odd inputs, limits

    @Test
    fun aSourceAboveThePixelLimitFailsBeforeAnyDecode() {
        val input = encodeMarked(6000, 5000, "public.jpeg", 1)
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        val e = assertFailsWith<FaceMaskException.ImageTooLarge> {
            IosFiles.masker().use { IosFiles.maskBytes(it, input, FileOptions(maxLongSide = 0)) }
        }
        assertEquals(25_000_000L, e.limit.toLong())
        assertEquals(0, codec.decodes, "no decode")
    }

    @Test
    fun noFaceGivesAnEmptyListThroughTheWholePipeline() {
        val (r, out) = IosFiles.masker { NoFaceRunner() }.use {
            IosFiles.maskBytes(it, encodeMarked(900, 700, "public.jpeg", 1))
        }
        assertTrue(r.faces.isEmpty())
        assertTrue(r.masks.isEmpty())
        assertCleanOutput(out, "no-face")
    }

    @Test
    fun oddInputsGiveACleanOutputOrATypedError() {
        val w = 640
        val h = 480
        val px = gradientPixels(w, h)
        val i16 = IosFiles.image16(w, h, px)
        val png16 = IosFiles.encode(i16, "public.png")
        CGImageRelease(i16)
        val cmyk = IosFiles.imageCmyk(w, h, px)
        val jpegCmyk = IosFiles.encode(cmyk, "public.jpeg")
        CGImageRelease(cmyk)
        val alpha = ByteArray(w * h * 4) { if (it % 4 == 3) (if ((it / 4) % w < w / 2) 0 else 255).toByte() else 40 }
        val transparent = IosFiles.imageOfRgba(w, h, alpha, alpha = true)
        val pngAlpha = IosFiles.encode(transparent, "public.png")
        CGImageRelease(transparent)
        for ((label, input) in listOf("png16" to png16, "cmyk" to jpegCmyk, "pngAlpha" to pngAlpha)) {
            val (_, out) = IosFiles.masker { NoFaceRunner() }.use { IosFiles.maskBytes(it, input) }
            assertCleanOutput(out, label)
        }
        // Transparent pixels are composited onto opaque white before detection and encoding.
        val flat = IosFiles.masker { NoFaceRunner() }.use { IosFiles.maskBytes(it, pngAlpha).second }
        val (fw, _, fp) = pixelsOf(flat)
        assertTrue((fp[10 * fw + 10] and 0xFFFFFF) > 0xF0F0F0, "transparent area is white")

        val jpeg = encodeMarked(w, h, "public.jpeg", 1)
        // A truncated JPEG: ImageIO may decode the part that is there (the rest is filled); either the output is
        // clean or the call fails with a typed error (ST-028 AC6).
        try {
            val (_, out) = IosFiles.masker().use { IosFiles.maskBytes(it, jpeg.copyOf(jpeg.size / 3)) }
            assertCleanOutput(out, "truncated")
        } catch (e: FaceMaskException.UnsupportedImage) {
            Unit
        }
        val bad = listOf(
            "empty" to ByteArray(0),
            "gif" to IosFiles.encode(IosFiles.imageOf(8, 8, IntArray(64) { -1 }), "com.compuserve.gif"),
            "text" to "not an image at all".encodeToByteArray(),
        )
        for ((label, input) in bad) {
            assertFailsWith<FaceMaskException.UnsupportedImage>(label) {
                IosFiles.masker().use { IosFiles.maskBytes(it, input) }
            }
        }
    }

    // ---------------------------------------------------------------- ST-029 AC1, AC2: no auxiliary data, no canary

    @Test
    fun auxiliaryImagesGpsXmpAndCanariesNeverReachTheOutput() {
        val planted = listOf(
            Triple("jpeg-disparity", "public.jpeg", IosFiles.Aux.DISPARITY),
            Triple("heic-disparity", "public.heic", IosFiles.Aux.DISPARITY),
            Triple("jpeg-gainmap", "public.jpeg", IosFiles.Aux.GAIN_MAP),
            Triple("heic-gainmap", "public.heic", IosFiles.Aux.GAIN_MAP),
        )
        val made = ArrayList<String>()
        for ((label, type, aux) in planted) {
            // This runtime's ImageIO may refuse to write a combination; what it made is recorded and checked.
            val input = encodeMarkedOrNull(1200, 900, type, 6, xmp = true, aux = setOf(aux)) ?: continue
            val seen = IosFiles.inspect(input)
            if (seen.auxiliary == 0) continue
            made += label
            if (type == "public.jpeg") IosFiles.keepForChecker("inputs", "$label.jpg", input)
            // Negative control: the same inspection flags the input on every planted item.
            assertTrue(seen.gps, "$label: input carries GPS")
            assertTrue(seen.xmpPacket, "$label: input carries XMP")
            assertTrue(seen.canaries.isNotEmpty(), "$label: input carries canaries")
            assertFalse(seen.clean, label)
            val (r, out) = IosFiles.masker().use { IosFiles.maskBytes(it, input) }
            assertTrue(r.masks.isNotEmpty(), label)
            assertCleanOutput(out, label)
            if (aux == IosFiles.Aux.GAIN_MAP) assertTrue(r.gainMapRemoved, "$label: the gain map is reported")
        }
        IosFiles.keepForChecker("inputs", "made.txt", made.joinToString("\n").encodeToByteArray())
        assertTrue(made.isNotEmpty(), "at least one input with auxiliary data was generated on this runtime")
        assertTrue(made.any { it.startsWith("heic") }, "an HEIC input with auxiliary data was generated: $made")
    }

    // ---------------------------------------------------------------- ST-029 AC3 (read side): nothing written

    @Test
    fun detectFileAndTheStreamFormWriteNoFileAndTheSnapshotSeesACopy() {
        val d = dir()
        val path = "$d/input.jpg"
        IosFiles.store(path, encodeMarked(1600, 1200, "public.jpeg", 6, xmp = true))
        val masker = IosFiles.masker()
        masker.use {
            val before = IosFiles.snapshot()
            val detection = it.detectFile(path)
            assertTrue(detection.faces.isNotEmpty())
            val (_, out) = IosFiles.maskBytes(it, IosFiles.read(path))
            assertCleanOutput(out, "stream")
            assertEquals(emptyList(), IosFiles.diff(before, IosFiles.snapshot()), "nothing written by the library")

            // Positive control: a file planted in Caches is seen.
            val planted = platform.Foundation.NSHomeDirectory() + "/Library/Caches/faceblur-snapshot-control.bin"
            IosFiles.store(planted, byteArrayOf(1))
            assertEquals(listOf(planted), IosFiles.diff(before, IosFiles.snapshot()).filter { p -> p == planted })
            IosFiles.removeDir(planted)

            // Negative control: a codec that copies the input to tmp before decoding makes the check fail.
            val copying = ObservingCodec()
            val copy = platform.Foundation.NSTemporaryDirectory() + "faceblur-copy-of-input.bin"
            copying.beforeOpen = { s -> if (s is FileSource.Path) IosFiles.store(copy, IosFiles.read(s.path)) }
            FaceBlurRuntime.installImageCodec(copying)
            val before2 = IosFiles.snapshot()
            it.detectFile(path)
            assertTrue(
                IosFiles.diff(before2, IosFiles.snapshot()).any { p ->
                    p.endsWith("faceblur-copy-of-input.bin")
                },
            )
            IosFiles.removeDir(copy)
        }
    }

    @Test
    fun pathSourcesAreReadOnlyAsRegularFiles() {
        val d = dir()
        assertFailsWith<FaceMaskException.IoFailed> { IosFiles.masker().use { it.detectFile("$d/missing.jpg") } }
        assertFailsWith<FaceMaskException.UnsupportedImage> { IosFiles.masker().use { it.detectFile(d) } }
        val input = encodeMarked(700, 500, "public.png", 1)
        val path = "$d/renamed.heic" // a PNG under another name is still read as PNG
        IosFiles.store(path, input)
        val r = IosFiles.masker().use { it.detectFile(path) }
        assertTrue(r.faces.isNotEmpty())
        assertContentEquals(input, IosFiles.read(path), "source unchanged")
    }
}
