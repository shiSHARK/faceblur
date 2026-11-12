// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.TiledFaceDetector
import io.github.shishark.faceblur.detect.YUNET_STRIDES
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetDetector
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.image.AreaResampler
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.mask.MaskPainter
import io.github.shishark.faceblur.mask.MaskSettings
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreUtilityTest {
    @Test
    fun sha256MatchesTheStandardTestVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    /** The padded layout in 64-bit arithmetic, near the largest model size, without allocating. */
    @Test
    fun theSha256LayoutHoldsUpToTheLargestModelSize() {
        val cap = 2_147_483_639L
        val sizes = listOf(0L, 1L, 55L, 56L, 64L, cap - 71L, cap - 64L, cap - 9L, cap - 1L, cap, Int.MAX_VALUE.toLong())
        for (size in sizes) {
            val (whole, tail) = Sha256.layout(size)
            val total = whole * 64L + tail
            assertTrue(tail == 64 || tail == 128, "size $size")
            assertEquals(0L, total % 64L, "size $size")
            assertTrue(total >= size + 9 && total < size + 9 + 64, "size $size: $total")
            assertTrue(whole * 64L <= size && size - whole * 64L < 64L, "size $size")
        }
        assertFailsWith<IllegalArgumentException> { Sha256.layout(-1L) }
    }

    @Test
    fun theResamplerAveragesAreasAndCopiesUnscaledCrops() {
        assertEquals(640 to 480, AreaResampler.fitSize(1280, 960, 640))
        assertEquals(100 to 50, AreaResampler.fitSize(100, 50, 640), "never upscales")
        // 40 × 40 with 2 × 2 blocks of known values → 20 × 20 averages (strips of 16 rows are crossed).
        val src = IntArrayPixels(
            40,
            40,
            IntArray(1600) { i ->
                (0xFF shl 24) or
                    (((i % 40) % 2 * 100 + (i / 40) % 2 * 50) shl 8)
            },
        )
        val out = AreaResampler.resample(src, 0, 0, 40, 40, 20, 20)
        assertTrue(out.all { (it shr 8) and 0xFF == 75 }, "every 2 × 2 block averages to (0 + 100 + 50 + 150) / 4")
        val crop = AreaResampler.resample(src, 3, 5, 7, 9, 7, 9)
        val expected = IntArray(63).also { src.readPixels(3, 5, 7, 9, it) }
        assertContentEquals(expected, crop)
        val view = PixelDetectionImage(src).cropScaled(0, 0, 40, 40, 20)
        assertEquals(20, view.width)
        assertEquals(400, view.readArgb().size)
        view.release()
    }

    /** ADR 0009 Verification: the library's clamp gives exactly 0, width or height, and landmarks on the limits. */
    @Test
    fun theLibraryClampHasExactValues() {
        val runner =
            object : TensorRunner {
                override val inputWidth = 640
                override val inputHeight = 640

                override fun initialize() = Unit

                override fun close() = Unit

                override fun run(input: FloatArray): Map<String, FloatArray> {
                    val m = HashMap<String, FloatArray>()
                    for (s in YUNET_STRIDES) {
                        val n = (640 / s) * (640 / s)
                        m["cls_$s"] = FloatArray(n)
                        m["obj_$s"] = FloatArray(n)
                        m["bbox_$s"] = FloatArray(n * 4)
                    }
                    // Four anchors at stride 32, each 236 px wide, crossing one edge of the 640 × 480 image each; landmarks far
                    // outside.
                    for ((row, col) in listOf(7 to 0, 0 to 10, 7 to 19, 14 to 10)) {
                        val idx = row * 20 + col
                        m.getValue("cls_32")[idx] = 0.9f
                        m.getValue("obj_32")[idx] = 0.9f
                        m.getValue("bbox_32")[idx * 4 + 2] = 2f
                        m.getValue("bbox_32")[idx * 4 + 3] = 2f
                    }
                    return m
                }
            }
        val image = PixelDetectionImage(IntArrayPixels(640, 480))
        val faces = TiledFaceDetector(yunetDetector(runner), DetectionSettings(0.5f)).globalPassOnly(image)
        assertEquals(4, faces.size)
        assertTrue(faces.any { it.rect.left == 0f }, "left edge")
        assertTrue(faces.any { it.rect.top == 0f }, "top edge")
        assertTrue(faces.any { it.rect.right == 640f }, "right edge")
        assertTrue(faces.any { it.rect.bottom == 480f }, "bottom edge")
        // The public path gives the same exact edges.
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
        val boxes = masker.use { it.detect(IntArrayPixels(640, 480)).faces }
        assertEquals(4, boxes.size)
        assertTrue(boxes.any { it.left == 0f } && boxes.any { it.top == 0f })
        assertTrue(boxes.any { it.right == 640f } && boxes.any { it.bottom == 480f })
    }

    /**
     * a colour the photo hides under alpha 0 next to a mask is not revealed by the soft edge. Two canvases with
     * alpha 0 that differ only in their hidden colour give identical output wherever the output is visible
     * (alpha > 0); opaque canvases keep the benchmark's straight blend (covered by the existing pixel tests).
     */
    @Test
    fun theSoftEdgeDoesNotRevealColourHiddenUnderTransparency() {
        val w = 200
        val h = 200
        val faces = listOf(DetectedFace(RectF(60f, 50f, 140f, 150f)))
        for (style in MaskStyle.entries) {
            val cfg = MaskSettings(style = style, feather = 0.5f)
            val a = IntArrayPixels(w, h, IntArray(w * h) { (it * 7919) and 0xFFFFFF })
            val b = IntArrayPixels(w, h, IntArray(w * h) { (it * 104729 + 12345) and 0xFFFFFF })
            MaskPainter.apply(a, faces, cfg)
            MaskPainter.apply(b, faces, cfg)
            var visible = 0
            for (i in 0 until w * h) {
                if (a.data[i] ushr 24 != 0) {
                    visible++
                    assertEquals(a.data[i], b.data[i], "$style pixel ${i % w},${i / w}")
                }
            }
            assertTrue(visible > 5000, "$style: the mask and its soft edge are visible")
        }
        // Control: with opaque canvases the different colours around the mask do show in the fills and the edge.
        val cfg = MaskSettings(feather = 0.5f)
        val a = IntArrayPixels(w, h, IntArray(w * h) { ((it * 7919) and 0xFFFFFF) or (0xFF shl 24) })
        val b = IntArrayPixels(w, h, IntArray(w * h) { ((it * 104729 + 12345) and 0xFFFFFF) or (0xFF shl 24) })
        MaskPainter.apply(a, faces, cfg)
        MaskPainter.apply(b, faces, cfg)
        assertTrue((0 until w * h).any { a.data[it] != b.data[it] })
    }
}
