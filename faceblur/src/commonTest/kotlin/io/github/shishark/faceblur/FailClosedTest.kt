// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionImage
import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.FaceDetectorEngine
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.TiledFaceDetector
import io.github.shishark.faceblur.detect.YUNET_STRIDES
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetDetector
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.mask.MaskPainter
import io.github.shishark.faceblur.mask.MaskSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fail closed: invalid boxes, scores, masks, settings and images raise a typed [CoreFailure] instead of being
 * skipped; checks run before any call into the caller's image; lost writes are detected. Every test also runs the
 * nearest valid case (the control) to show that the check is specific.
 */
class FailClosedTest {
    private val w = 120
    private val h = 100

    private fun face(l: Float, t: Float, r: Float, b: Float, s: Float = 0.9f) = DetectedFace(RectF(l, t, r, b), s)

    /** Counts every call into the image, so "no callback before the check" can be asserted. */
    private class CountingPixels(private val base: IntArrayPixels) : MutablePixelImage {
        override val width get() = base.width
        override val height get() = base.height
        var calls = 0
            private set

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            calls++
            base.readPixels(x, y, w, h, dst)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            calls++
            base.writePixels(x, y, w, h, src)
        }
    }

    private fun image() = CountingPixels(IntArrayPixels(w, h, randomPixels(w, h, 5)))

    @Test
    fun invalidFaceBoxesThrowBeforeAnyPixelIsTouched() {
        val bad =
            listOf(
                "NaN left" to face(Float.NaN, 10f, 40f, 40f),
                "NaN bottom" to face(10f, 10f, 40f, Float.NaN),
                "+Inf right" to face(10f, 10f, Float.POSITIVE_INFINITY, 40f),
                "-Inf top" to face(10f, Float.NEGATIVE_INFINITY, 40f, 40f),
                "inverted x" to face(40f, 10f, 10f, 40f),
                "inverted y" to face(10f, 40f, 40f, 10f),
            )
        for ((name, f) in bad) {
            val img = image()
            val before = randomPixels(w, h, 5)
            // The bad face comes second: the valid first face must not be masked either (no partial result).
            assertFailsWith<CoreFailure.InvalidFaceBox>(name) {
                MaskPainter.apply(img, listOf(face(60f, 40f, 100f, 90f), f), MaskSettings())
            }
            assertEquals(0, img.calls, "$name: no call into the image")
            val after = IntArray(w * h).also { img.readPixels(0, 0, w, h, it) }
            assertContentEquals(before, after, name)
        }
        // Control: the valid face alone is masked.
        val img = image()
        MaskPainter.apply(img, listOf(face(60f, 40f, 100f, 90f)), MaskSettings())
        assertTrue(img.calls > 0)
    }

    @Test
    fun masksThatCannotCoverAPixelThrowInsteadOfBeingSkipped() {
        val img0 = image()
        assertFailsWith<CoreFailure.InvalidMask>("zero width") {
            MaskPainter.apply(img0, listOf(face(10f, 10f, 10f, 30f)), MaskSettings())
        }
        assertEquals(0, img0.calls)
        val geometries =
            listOf(
                MaskGeometry(Float.NaN, 10f, 5f, 5f, MaskShape.ELLIPSE),
                MaskGeometry(10f, 10f, Float.POSITIVE_INFINITY, 5f, MaskShape.ELLIPSE),
                MaskGeometry(10f, 10f, 5f, 0f, MaskShape.RECTANGLE),
                MaskGeometry(10f, 10f, -5f, 5f, MaskShape.ELLIPSE),
            )
        for (g in geometries) {
            val img = image()
            assertFailsWith<CoreFailure.InvalidMask>("$g") { MaskPainter.applyMasks(img, listOf(g), MaskSettings()) }
            assertEquals(0, img.calls)
        }
    }

    /**
     * a positive radius below the minimum (a box clipped by the image border to a sliver, or a sub-pixel box)
     * is drawn with the minimum radius of 1 px instead of failing; the pixel nearest to the centre is always covered.
     */
    @Test
    fun sliversAreMaskedWithTheMinimumRadius() {
        val cases =
            listOf(
                face(0f, 30f, 0.3f, 60f), // border sliver on the left edge
                face(10f, 10f, 10.7f, 10.7f), // sub-pixel box
                face(119.8f, 40f, 120f, 70f), // border sliver on the right edge
            )
        for (f in cases) {
            val base = IntArrayPixels(w, h, IntArray(w * h) { 0x00102030 })
            val img = CountingPixels(base)
            MaskPainter.apply(img, listOf(f), MaskSettings(style = MaskStyle.SOLID))
            val cx = ((f.rect.left + f.rect.right) / 2f).toInt().coerceIn(0, w - 1)
            val cy = ((f.rect.top + f.rect.bottom) / 2f - 0.08f * f.rect.height).toInt().coerceIn(0, h - 1)
            assertEquals(0xFF7F7F7F.toInt(), base.data[cy * w + cx], "${f.rect}: the centre pixel is masked")
        }
        // Control: geometries at and above the minimum are drawn unchanged.
        val g = MaskGeometry(10.5f, 10.5f, 1f, 1f, MaskShape.ELLIPSE)
        assertEquals(g, io.github.shishark.faceblur.mask.PreparedMask.usable(g))
        val small = MaskGeometry(10.5f, 10.5f, 0.2f, 3f, MaskShape.ELLIPSE)
        assertEquals(small.copy(rx = 1f), io.github.shishark.faceblur.mask.PreparedMask.usable(small))
    }

    @Test
    fun invalidOrShrinkingMaskSettingsAreRejected() {
        val cases =
            listOf<Pair<String, () -> MaskSettings>>(
                "expandX" to { MaskSettings(expandX = 0.5f) },
                "expandX" to { MaskSettings(expandX = 1.59f) },
                "expandX" to { MaskSettings(expandX = -1.35f) },
                "expandX" to { MaskSettings(expandX = Float.NaN) },
                "expandX" to { MaskSettings(expandX = 4.5f) },
                "expandY" to { MaskSettings(expandY = 1.79f) },
                "expandY" to { MaskSettings(expandY = Float.POSITIVE_INFINITY) },
                "shiftUp" to { MaskSettings(shiftUp = Float.NaN) },
                "shiftUp" to { MaskSettings(shiftUp = 1.0f) },
                "shiftUp" to { MaskSettings(shiftUp = 0.081f) },
                "shiftUp" to { MaskSettings(shiftUp = 0f) },
                "shiftUp" to { MaskSettings(shape = MaskShape.RECTANGLE, shiftUp = 0.2f) },
                "feather" to { MaskSettings(feather = -0.1f) },
                "feather" to { MaskSettings(feather = Float.NaN) },
                "feather" to { MaskSettings(feather = 2f) },
                "ringWidth" to { MaskSettings(ringWidth = Float.NaN) },
                "innerSolidFraction" to { MaskSettings(innerSolidFraction = 1.5f) },
            )
        for ((parameter, make) in cases) {
            val e = assertFailsWith<CoreFailure.InvalidSetting> { make() }
            assertEquals(parameter, e.parameter)
            assertTrue(e.message!!.startsWith("Invalid setting '$parameter'"))
        }
        // Controls: defaults, both shapes, a larger mask that moves further up, and the boundaries of each range.
        MaskSettings()
        MaskSettings(shape = MaskShape.RECTANGLE)
        MaskSettings(expandX = 2f, expandY = 2.2f, shiftUp = 0.1f)
        MaskSettings(expandX = 4f, expandY = 4f, shiftUp = 0f, feather = 0f, ringWidth = 1f, innerSolidFraction = 1f)
        MaskSettings(feather = 1f, ringWidth = 0f, innerSolidFraction = 0f)
    }

    /**
     * Why "contains the default mask" is the rule: a rejected shift uncovers pixels that the measured default mask
     * covers (here hair above the box), even where it covers the box corners better.
     */
    @Test
    fun aRejectedShiftWouldUncoverPixelsTheDefaultMaskCovers() {
        val f = face(100f, 100f, 300f, 340f)
        val default = MaskPainter.maskFor(f, MaskSettings())
        // The geometry MaskSettings(shiftUp = 0f) would produce, built directly because the settings are rejected.
        val shifted = default.copy(cy = (100f + 340f) / 2f)
        var lost = 0
        for (y in 0 until 500) {
            for (x in 0 until 400) {
                if (default.containsPixel(x, y) &&
                    !shifted.containsPixel(x, y)
                ) {
                    lost++
                }
            }
        }
        assertTrue(lost > 1000, "the shifted mask uncovers $lost pixels of the default mask")
    }

    @Test
    fun invalidDetectionSettingsAreRejected() {
        val cases =
            listOf<Pair<String, () -> Any>>(
                "minScore" to { DetectionSettings(minScore = 0f) },
                "minScore" to { DetectionSettings(minScore = 1f) },
                "minScore" to { DetectionSettings(minScore = Float.NaN) },
                "nmsIouThreshold" to { DetectionSettings(0.5f, nmsIouThreshold = 0f) },
                "nmsIouThreshold" to { DetectionSettings(0.5f, nmsIouThreshold = Float.NaN) },
                "overlap" to { DetectionSettings(0.5f, overlap = -0.1f) },
                "overlap" to { DetectionSettings(0.5f, overlap = 1f) },
                "overlap" to { DetectionSettings(0.5f, overlap = Float.NaN) },
                "maxPasses" to { DetectionSettings(0.5f, maxPasses = 0) },
                "maxMergeCandidates" to { DetectionSettings(0.5f, maxMergeCandidates = -1) },
            )
        for ((parameter, make) in cases) {
            assertEquals(parameter, assertFailsWith<CoreFailure.InvalidSetting> { make() }.parameter)
        }
        // Controls: the boundaries are accepted.
        DetectionSettings(minScore = 0.000001f, nmsIouThreshold = 1f, overlap = 0f)
        DetectionSettings(minScore = 0.999999f, maxPasses = 1, maxMergeCandidates = 0)
    }

    /** Raw YuNet outputs with one anchor at stride 32, row 4, column 4. */
    private fun outputs(cls: Float, obj: Float = cls, size: Float = 1f, offset: Float = 0f): Map<String, FloatArray> {
        val m = HashMap<String, FloatArray>()
        for (s in YUNET_STRIDES) {
            val n = (640 / s) * (640 / s)
            m["cls_$s"] = FloatArray(n)
            m["obj_$s"] = FloatArray(n)
            m["bbox_$s"] = FloatArray(n * 4)
        }
        val idx = 4 * 20 + 4
        m.getValue("cls_32")[idx] = cls
        m.getValue("obj_32")[idx] = obj
        m.getValue("bbox_32")[idx * 4] = offset
        m.getValue("bbox_32")[idx * 4 + 2] = size
        m.getValue("bbox_32")[idx * 4 + 3] = size
        return m
    }

    private class ConstantRunner(var out: Map<String, FloatArray>) : TensorRunner {
        override val inputWidth = 640
        override val inputHeight = 640

        override fun initialize() = Unit

        override fun close() = Unit

        override fun run(input: FloatArray) = out
    }

    private fun codes(out: Map<String, FloatArray>): List<String> = assertFailsWith<OutputCheckFailed> {
        yunetDetector(ConstantRunner(out)).detect(FakeDetectionImage(640, 640), 0.5f)
    }
        .findings

    @Test
    fun theDecoderRejectsNonFiniteScoresAndBoxesInsteadOfDroppingThem() {
        assertEquals(listOf("NON_FINITE"), codes(outputs(Float.NaN, 1f)), "NaN cls")
        assertEquals(listOf("NON_FINITE"), codes(outputs(1f, Float.NaN)), "NaN obj")
        assertEquals(listOf("NON_FINITE"), codes(outputs(0.9f, offset = Float.NaN)), "NaN box above threshold")
        assertEquals(listOf("NON_FINITE"), codes(outputs(0.9f, size = 200f)), "exp overflow")
        assertEquals(listOf("BOX_INVERTED"), codes(outputs(0.9f, size = -200f)), "exp underflow: zero width")
        // Controls: a finite anchor is decoded; a NaN box below the threshold is not read and is ignored.
        assertEquals(1, yunetDetector(ConstantRunner(outputs(0.9f))).detect(FakeDetectionImage(640, 640), 0.5f).size)
        assertEquals(
            0,
            yunetDetector(ConstantRunner(outputs(0.2f, offset = Float.NaN)))
                .detect(FakeDetectionImage(640, 640), 0.5f).size,
        )
    }

    @Test
    fun aModelFaultFailsTheCallWithItsCodeAndLeavesTheImageUnchanged() {
        var fault = false
        val runner =
            object : TensorRunner by ConstantRunner(outputs(0.9f)) {
                override fun run(input: FloatArray) = if (fault) outputs(Float.NaN, 1f) else outputs(0.9f)
            }
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
        fault = true
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("NON_FINITE"), e.findings)
        assertTrue(e.message!!.contains("NON_FINITE"))
        assertContentEquals(before, img.data, "nothing written")
        fault = false
        assertEquals(1, masker.maskInPlace(img).masks.size, "control")
        masker.close()
    }

    @Test
    fun theTilerRejectsInvalidEngineOutput() {
        val bad =
            listOf(
                face(10f, 10f, 40f, 40f, Float.NaN),
                face(10f, 10f, 40f, 40f, Float.POSITIVE_INFINITY),
                face(Float.NaN, 10f, 40f, 40f),
                face(40f, 10f, 10f, 40f),
                face(10f, 10f, 10f, 40f),
            )
        for (f in bad) {
            val engine = constantEngine(listOf(f))
            assertFailsWith<CoreFailure.InvalidDetection>("$f") {
                TiledFaceDetector(engine, DetectionSettings(0.5f)).detect(FakeDetectionImage(640, 480))
            }
        }
        val ok = TiledFaceDetector(constantEngine(listOf(face(10f, 10f, 40f, 40f))), DetectionSettings(0.5f))
        assertEquals(1, ok.detect(FakeDetectionImage(640, 480)).faces.size, "control")
    }

    private fun constantEngine(faces: List<DetectedFace>) = object : FaceDetectorEngine {
        override val inputWidth = 640
        override val inputHeight = 640

        override fun close() = Unit

        override fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit) = faces
    }

    /** A huge virtual image that stores only written pixels (constant grey elsewhere) and counts calls. */
    private class SparseImage(override val width: Int, override val height: Int) : MutablePixelImage {
        val written = HashMap<Long, Int>()
        var calls = 0

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            calls++
            check(dst.size >= w.toLong() * h) { "buffer too small" }
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    dst[yy * w + xx] = written[(y + yy).toLong() * width + x + xx] ?: 0xFF808080.toInt()
                }
            }
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            calls++
            for (yy in 0 until h) for (xx in 0 until w) written[(y + yy).toLong() * width + x + xx] = src[yy * w + xx]
        }
    }

    @Test
    fun imagesBeyondTheBlockSizeLimitAreRejectedBeforeAnyCallback() {
        for ((iw, ih) in listOf(50_000 to 50_000, 65_600 to 65_600, 46_341 to 46_341, -1 to 100)) {
            val img = SparseImage(iw, ih)
            val e =
                assertFailsWith<CoreFailure.ImageTooLarge>("$iw x $ih") {
                    MaskPainter.apply(
                        img,
                        listOf(face(0f, 0f, 49_999f, 49_999f)),
                        MaskSettings(style = MaskStyle.SOLID),
                    )
                }
            assertEquals(iw, e.width)
            assertEquals(0, img.calls, "$iw x $ih: no call into the image")
        }
        // Control: 46,340² pixels (just below Int.MAX_VALUE) with a small face works.
        val img = SparseImage(46_340, 46_340)
        MaskPainter.apply(img, listOf(face(100f, 100f, 140f, 150f)), MaskSettings(style = MaskStyle.SOLID))
        assertTrue(img.written.isNotEmpty())
    }

    /** An image that loses or alters writes; [premultiplied] rounds translucent pixels like platform bitmaps. */
    private class LossyImage(private val base: IntArrayPixels, private val mode: String) : MutablePixelImage {
        override val width get() = base.width
        override val height get() = base.height

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = base.readPixels(x, y, w, h, dst)

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            when (mode) {
                "drop" -> Unit

                "alter one" -> base.writePixels(
                    x,
                    y,
                    w,
                    h,
                    src.copyOf().also {
                        it[it.size / 2] = it[it.size / 2] xor 1
                    },
                )

                "premultiplied" -> {
                    val rounded =
                        IntArray(src.size) {
                            val p = src[it]
                            val a = p ushr 24
                            if (a == 0xFF) p else (p and 0xFF000000.toInt()) or (p and 0xFEFEFE)
                        }
                    base.writePixels(x, y, w, h, rounded)
                }

                else -> base.writePixels(x, y, w, h, src)
            }
        }
    }

    @Test
    fun lostOrAlteredWritesAreDetected() {
        val f = listOf(face(30f, 20f, 90f, 80f))
        for (mode in listOf("drop", "alter one")) {
            for (style in MaskStyle.entries) {
                val img = LossyImage(IntArrayPixels(w, h, randomPixels(w, h, 9)), mode)
                assertFailsWith<CoreFailure.WriteNotApplied>("$mode $style") {
                    MaskPainter.apply(img, f, MaskSettings(style = style))
                }
            }
        }
        // Controls: a faithful image, and one that only rounds translucent soft-edge pixels, pass.
        val translucent = IntArray(w * h) { randomPixels(w, h, 9)[0] and 0x00FFFFFF or (0x40 shl 24) }
        MaskPainter.apply(LossyImage(IntArrayPixels(w, h, randomPixels(w, h, 9)), "faithful"), f, MaskSettings())
        MaskPainter.apply(LossyImage(IntArrayPixels(w, h, translucent), "premultiplied"), f, MaskSettings())
    }
}
