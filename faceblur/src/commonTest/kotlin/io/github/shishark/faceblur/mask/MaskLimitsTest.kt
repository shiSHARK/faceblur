// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MASK_LIMITS
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskLimitText
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.MergeCandidate
import io.github.shishark.faceblur.detect.Nms
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.grouped
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The per-image mask limits of ADR 0013 decision 2: the count (65,536) and the summed working area (32 × max(image
 * pixels, 1,048,576)), each exactly at the limit and one past it, with the error each call gives, and no read and no
 * write of the image by the mask stage.
 */
class MaskLimitsTest {
    private val max = MaskStageLimits.MAX_MASKS_PER_IMAGE

    /** [n] disjoint 2 × 2 boxes on a grid (no merging between them). */
    private fun disjoint(n: Int): List<MergeCandidate> = List(n) { i ->
        val x = (i % 1_000) * 4f
        val y = (i / 1_000) * 4f
        MergeCandidate(DetectedFace(RectF(x, y, x + 2f, y + 2f), 0.9f), false)
    }

    @Test
    fun detectionAcceptsTheLimitAndRefusesOneMoreMaskWithTooManyMasks() {
        assertEquals(max, Nms.mergeCandidates(disjoint(max), 0.4f, 0.7f).faces.size)
        val e = assertFailsWith<OutputCheckFailed> { Nms.mergeCandidates(disjoint(max + 1), 0.4f, 0.7f) }
        assertEquals(listOf("TOO_MANY_MASKS"), e.findings)
        assertEquals((max + 1).toLong(), e.maskCount, "the plan's mask count travels with the finding")
        assertEquals(max, e.maskLimit)
    }

    private val advice = "the score threshold is very likely too low for this model; check it with faceblur-eval."

    /** The actionable texts (ADR 0012 decision 7): count, threshold and the faceblur-eval advice; numbers only. */
    @Test
    fun theTooManyMasksTextsNameTheCountTheThresholdAndTheAdvice() {
        assertEquals(
            "65,537 masks at score threshold 0.05 exceed the limit of 65,536: $advice",
            MaskLimitText.count(65_537, max, 0.05f),
        )
        assertEquals(
            "1,200 masks at score threshold 0.01 have a summed working area of 1,600,000,000 pixels, above the " +
                "limit of 1,599,999,999: $advice",
            MaskLimitText.area(1_200, 0.01f, 1_600_000_000L, 1_599_999_999L),
        )
        assertEquals(
            "240,000,001 box pair checks of the duplicate removal at score threshold 0.05 exceed the limit of " +
                "240,000,000: $advice",
            MaskLimitText.nmsPairChecks(240_000_001L, 240_000_000L, 0.05f),
        )
        assertEquals(
            "1 box pair check of the duplicate removal at score threshold 0.01 exceeds the limit of 0: $advice",
            MaskLimitText.nmsPairChecks(1L, 0L, 0.01f),
        )
        // The singular forms (only reachable with internal limits of 0): the verb agrees with "1".
        assertEquals(
            "1 mask at score threshold 0.5 exceeds the limit of 0: $advice",
            MaskLimitText.count(1L, 0, 0.5f),
        )
        assertEquals(
            "1 mask at score threshold 0.5 has a summed working area of 1,000 pixels, above the limit of 999: $advice",
            MaskLimitText.area(1, 0.5f, 1_000L, 999L),
        )
        assertTrue(MaskLimitText.planCount(70_000).endsWith("; the plan has 70,000 masks"))
        assertTrue(MaskLimitText.planCount(70_000).startsWith("at most 65,536 masks, and a summed mask working area"))
        assertEquals("1", grouped(1))
        assertEquals("999", grouped(999))
        assertEquals("1,000", grouped(1_000))
        assertEquals("9,223,372,036,854,775,807", grouped(Long.MAX_VALUE))
        assertEquals("-1,234", grouped(-1_234))
        // The public exception: codes first, then the sentence; findings keep the codes only.
        val e = FaceMaskException.ModelOutputInvalid(listOf("TOO_MANY_MASKS"), MaskLimitText.count(65_537, max, 0.05f))
        assertEquals(
            "Model output invalid: TOO_MANY_MASKS. 65,537 masks at score threshold 0.05 exceed the limit of 65,536: " +
                advice,
            e.message,
        )
        assertEquals(listOf("TOO_MANY_MASKS"), e.findings)
        assertEquals(
            "Model output invalid: NON_FINITE",
            FaceMaskException.ModelOutputInvalid(listOf("NON_FINITE")).message,
        )
    }

    @Test
    fun partMasksCountTowardsTheDetectionLimit() {
        // One face that grew once (a cut box, then a larger one): the face and its part are two masks.
        val grown = listOf(
            MergeCandidate(DetectedFace(RectF(100f, 100f, 130f, 160f), 0.9f), true),
            MergeCandidate(DetectedFace(RectF(100f, 100f, 160f, 160f), 0.8f), false),
        )
        val ok = Nms.mergeCandidates(grown, 0.4f, 0.7f, maxMasks = 2)
        assertEquals(1, ok.faces.size)
        assertEquals(1, ok.parts.size)
        val e = assertFailsWith<OutputCheckFailed> { Nms.mergeCandidates(grown, 0.4f, 0.7f, maxMasks = 1) }
        assertEquals(listOf("TOO_MANY_MASKS"), e.findings)
    }

    @Test
    fun aPlanAcceptsTheLimitAndRefusesOneMoreMask() {
        val mask = Mask.manual(1f, 1f, 3f, 3f)
        assertEquals(max, MaskPlan(10, 10, List(max) { mask }).masks.size)
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> { MaskPlan(10, 10, List(max + 1) { mask }) }
        assertEquals("masks", e.parameter)
        assertTrue(e.message!!.endsWith("; the plan has 65,537 masks"), "the count is reported: ${e.message}")
    }

    @Test
    fun theCoreRefusesTooManyMasksAsASecondLayer() {
        val image = RecordingImage(stageImage(16, 16, 1))
        val masks = List(max + 1) { MaskGeometry(8f, 8f, 2f, 2f, MaskShape.ELLIPSE) }
        assertFailsWith<CoreFailure.InvalidMask> { MaskEngine.apply(image, masks, MaskSettings()) }
        assertEquals(0, image.calls)
    }

    /** A mask whose read rectangle, clipped to the image, is exactly the pixel (0, 0). */
    private val onePixel = MaskGeometry(-1.5f, -1.5f, 1f, 1f, MaskShape.ELLIPSE)

    /** A mask whose read rectangle is the whole image. */
    private fun whole(w: Int, h: Int) = MaskGeometry(w / 2f, h / 2f, w.toFloat(), h.toFloat(), MaskShape.RECTANGLE)

    @Test
    fun theSummedWorkingAreaPassesExactlyAtTheLimitAndFailsOnePixelPast() {
        // A 1,024 × 1,024 image: the limit is 32 × 1,048,576, that is 32 whole-image masks.
        val w = 1_024
        val h = 1_024
        val s = MaskSettings(style = MaskStyle.SOLID)
        val atLimit = List(32) { whole(w, h) }
        // The read rectangle of the one-pixel mask is exactly one pixel.
        val single = PreparedMask(onePixel, s).extent(1f + PreparedMask(onePixel, s).featherN, w, h)
        assertEquals(1, single.area)
        // At the limit the check passes: step 2 starts (the test stops the call there, the plan would be valid).
        class Passed : RuntimeException()
        val observer = StepRecorder().apply { onStep = { if (it == 2) throw Passed() } }
        val image = RecordingImage(IntArrayPixels(w, h))
        assertFailsWith<Passed> { MaskEngine.apply(image, atLimit, s, seams = StageSeams(observer = observer)) }
        assertEquals(0, image.calls)
        // One pixel more: refused before any read or write.
        assertFailsWith<CoreFailure.MaskWorkLimit> { MaskEngine.apply(image, atLimit + onePixel, s) }
        assertEquals(0, image.calls)
        // Below 1,048,576 pixels the limit is still 32 × 1,048,576: 33 masks over a 100 × 100 image (330,000 pixels,
        // more than 32 × 10,000) pass.
        val small = RecordingImage(IntArrayPixels(100, 100))
        MaskEngine.apply(small, List(33) { whole(100, 100) }, s)
        assertTrue(small.writes > 0)
    }

    /** The owner's value of the area factor; the other caps and the documented memory bound did not move with it. */
    @Test
    fun theAreaFactorIs32AndNoOtherCapOrTheMemoryBoundMoved() {
        assertEquals(32L, MaskStageLimits.MAX_MASK_AREA_FACTOR)
        assertEquals(65_536, MaskStageLimits.MAX_MASKS_PER_IMAGE)
        assertEquals(1_048_576L, MaskStageLimits.MIN_AREA_BASE)
        assertEquals(32, MaskStageLimits.MAX_PART_MASK_GROWTH)
        assertEquals(524_288, MaskStageLimits.MAX_SMOOTH_FILL_CELLS)
        assertEquals(64L * 1024 * 1024, MaskStageLimits.AVAILABLE_MEMORY_MARGIN)
        assertEquals(4.3, MaskStageLimits.WORST_CASE_BYTES_PER_PIXEL)
        assertEquals(32L * 1024 * 1024, MaskStageLimits.WORST_CASE_FIXED_BYTES)
        assertEquals(listOf(1_710_239L, 5_020_519L, 16_338_075L), MaskStageLimits.TYPICAL_BYTES)
        assertEquals(
            "at most 65,536 masks, and a summed mask working area of at most 32 times max(image pixels, 1,048,576)",
            MASK_LIMITS,
        )
        // The sizing does not depend on the factor: a plan exactly at the new limit (32 whole-image masks at 25 MP)
        // reserves what one whole-image mask reserves plus the per-mask arrays, within 4.3 bytes per pixel + 32 MiB.
        val iw = 6_000
        val ih = 4_166
        val bound =
            MaskStageLimits.WORST_CASE_BYTES_PER_PIXEL * iw.toLong() * ih + MaskStageLimits.WORST_CASE_FIXED_BYTES
        for (style in MaskStyle.entries) {
            val settings = MaskSettings(style = style)
            val one = MaskEngine.workingAreaBytes(iw, ih, listOf(whole(iw, ih)), settings)
            val atLimit = MaskEngine.workingAreaBytes(iw, ih, List(32) { whole(iw, ih) }, settings)
            assertTrue(atLimit - one in 1..31L * 128, "$style: 31 more masks add only their per-mask arrays")
            assertTrue(atLimit <= bound, "$style: $atLimit > $bound")
        }
    }

    /**
     * The area limit through the public `applyMasks`: a plan exactly at 32 × max(image pixels, 1,048,576) is masked;
     * one pixel of working area more fails closed with the actionable text, before the image is read or written.
     */
    @Test
    fun applyMasksMasksExactlyAtTheAreaLimitAndRefusesOnePixelMoreWithTheActionableText() {
        val w = 1_024
        val h = 1_024
        val wholeMask = Mask.exact(MaskShape.RECTANGLE, w / 2f, h / 2f, w.toFloat(), h.toFloat())
        val atLimit = List(32) { wholeMask }
        // SOLID: the read rectangle of this mask, clipped to the image, is exactly the pixel (0, 0) (see above).
        val onePixelMore = Mask.exact(MaskShape.ELLIPSE, -1.5f, -1.5f, 1f, 1f)
        val original = gradientPixels(w, h)
        masker(FaceMaskerConfig(masking = MaskingConfig(style = MaskStyle.SOLID))).use { m ->
            val over = RecordingImage(IntArrayPixels(w, h, original.copyOf()))
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration> {
                m.applyMasks(over, MaskPlan(w, h, atLimit + onePixelMore))
            }
            assertEquals("masks", e.parameter)
            assertEquals(
                "Invalid value for 'masks': allowed at most 65,536 masks, and a summed mask working area of at most " +
                    "32 times max(image pixels, 1,048,576); the plan has 33 masks with a summed working area of " +
                    "33,554,433 pixels, above the limit of 33,554,432",
                e.message,
            )
            assertEquals(0, over.calls, "refused before any read or write")
            assertContentEquals(original, over.base.data, "the image is untouched")
            val at = RecordingImage(IntArrayPixels(w, h, original.copyOf()))
            m.applyMasks(at, MaskPlan(w, h, atLimit))
            assertTrue(at.writes > 0, "exactly at the limit the plan is masked")
            assertTrue(at.base.data.all { it == MaskingConfig().color }, "every pixel is covered")
        }
    }

    private fun masker(config: FaceMaskerConfig = FaceMaskerConfig(), limits: DetectionSettings? = null) =
        if (limits == null) {
            FaceMasker.forTest(testYuNetModel(), config, RunnerRuntime(OneFaceRunner()))
        } else {
            FaceMasker.forTest(testYuNetModel(), config, RunnerRuntime(OneFaceRunner()), limits)
        }

    /** A runner that "sees" three disjoint faces in one pass: 0 + 1 + 2 = 3 in-pass NMS pair checks. */
    private class ThreeFacesRunner : TensorRunner by OneFaceRunner() {
        private val one = OneFaceRunner()

        override fun run(input: FloatArray): Map<String, FloatArray> {
            val m = one.run(input)
            for (column in listOf(10, 16)) {
                val idx = 4 * 20 + column
                m.getValue("cls_32")[idx] = 0.9f
                m.getValue("obj_32")[idx] = 0.9f
                m.getValue("bbox_32")[idx * 4 + 2] = ln(2f)
                m.getValue("bbox_32")[idx * 4 + 3] = ln(2f)
            }
            return m
        }
    }

    /** The in-pass NMS work bound through the public calls: the pair checks, the threshold and the advice. */
    @Test
    fun theNmsWorkBoundReportsThePairChecksTheThresholdAndTheAdvice() {
        val image = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
        val text =
            "Model output invalid: TOO_MANY_NMS_PAIR_CHECKS. 3 box pair checks of the duplicate removal at score " +
                "threshold 0.5 exceed the limit of 2: $advice"
        val runtime = RunnerRuntime(ThreeFacesRunner())
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime, DetectionSettings(0.5f, maxNmsWork = 2)).use {
            val d = assertFailsWith<FaceMaskException.ModelOutputInvalid> { it.detect(image) }
            assertEquals(listOf("TOO_MANY_NMS_PAIR_CHECKS"), d.findings)
            assertEquals(text, d.message, "detect: pair checks, threshold and advice")
            val p = assertFailsWith<FaceMaskException.ModelOutputInvalid> { it.maskInPlace(image) }
            assertEquals(listOf("TOO_MANY_NMS_PAIR_CHECKS"), p.findings)
            assertEquals(text, p.message, "maskInPlace: pair checks, threshold and advice")
        }
        assertEquals(0, image.writes, "nothing was written")
        // Exactly at the bound the same photo passes.
        val ok = RunnerRuntime(ThreeFacesRunner())
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), ok, DetectionSettings(0.5f, maxNmsWork = 3)).use {
            assertEquals(3, it.detect(image).faces.size)
        }
    }

    @Test
    fun eachCallReportsTheLimitsWithItsOwnErrorAndWritesNothing() {
        // The model's masks above the count limit: TOO_MANY_MASKS from detect and maskInPlace.
        val counting = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
        val few = DetectionSettings(minScore = 0.5f, maxMasks = 0)
        masker(limits = few).use { m ->
            val countText =
                "Model output invalid: TOO_MANY_MASKS. 1 mask at score threshold 0.5 exceeds the limit of 0: " +
                    advice
            val d = assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.detect(counting) }
            assertEquals(listOf("TOO_MANY_MASKS"), d.findings)
            assertEquals(countText, d.message, "detect: count, threshold and advice")
            val p = assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.maskInPlace(counting) }
            assertEquals(listOf("TOO_MANY_MASKS"), p.findings)
            assertEquals(countText, p.message, "maskInPlace: count, threshold and advice")
        }
        assertEquals(0, counting.writes, "detection read the image; nothing was written")
        // The area limit: the model's masks give TOO_MANY_MASKS, a caller's plan InvalidConfiguration("masks").
        val image = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
        masker().use { m ->
            val plan = m.detect(image).toMaskPlan()
            assertTrue(plan.masks.isNotEmpty())
            val readsAfterDetection = image.reads
            val manyWhole = MaskPlan(
                640,
                480,
                List(128) { Mask.exact(MaskShape.RECTANGLE, 320f, 240f, 4_000f, 4_000f) },
            )
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration> { m.applyMasks(image, manyWhole) }
            assertEquals("masks", e.parameter)
            assertTrue(
                e.message!!.contains(
                    "; the plan has 128 masks with a summed working area of 39,321,600 pixels, above " +
                        "the limit of 33,554,432",
                ),
                "the plan's count and area are reported: ${e.message}",
            )
            assertEquals(readsAfterDetection, image.reads, "the mask stage read nothing")
            assertEquals(0, image.writes)
        }
        // With the area limit lowered to 0 through the test seam, the same detection's masks exceed it:
        // maskInPlace reports a model fault, applyMasks with the same plan a caller's configuration.
        val lowered = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
        masker().use { m ->
            m.useStageSeamsForTest(StageSeams(areaFactor = 0))
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.maskInPlace(lowered) }
            assertEquals(listOf("TOO_MANY_MASKS"), e.findings)
            val text = e.message!!
            assertTrue(text.startsWith("Model output invalid: TOO_MANY_MASKS. "), text)
            assertTrue(
                Regex(
                    """(1 mask at score threshold 0\.5 has|\d[\d,]* masks at score threshold 0\.5 have) a summed """ +
                        """working area of [\d,]+ pixels, """ +
                        """above the limit of 0: """,
                ).containsMatchIn(text),
                text,
            )
            assertTrue(text.endsWith(advice), text)
            val plan = m.detect(lowered).toMaskPlan()
            val c = assertFailsWith<FaceMaskException.InvalidConfiguration> { m.applyMasks(lowered, plan) }
            assertEquals("masks", c.parameter)
            assertTrue(c.message!!.contains("above the limit of 0"), c.message)
        }
        assertEquals(0, lowered.writes)
    }
}
