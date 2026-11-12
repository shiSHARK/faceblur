// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FacePart
import io.github.shishark.faceblur.FakeDetectionImage
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.Timings
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskStageLimits
import io.github.shishark.faceblur.mask.PreparedMask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The union property at pixel level (ADR 0012 decision 3, part masks): for thresholds t' < t, every pixel inside a
 * mask at t is inside a mask at t'. Masks come from the real tiler, merge and `Detection.toMaskPlan`; pixels are
 * classified twice, by the library's own opaque-pixel test and by an independent oracle in double arithmetic from
 * the mask values. Negative controls: the same plans without part masks lose pixels on the cut cases.
 */
class UnionMergeTest {
    /** An engine that returns, for pass number k, the listed image-space boxes at or above the threshold. */
    private class ScriptedEngine(private val passes: List<List<Pair<RectF, Float>>>) : FaceDetectorEngine {
        override val inputWidth = 640
        override val inputHeight = 640
        private var call = 0

        override fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit): List<DetectedFace> {
            val crop = image as FakeDetectionImage
            val sx = crop.width / crop.srcW
            val sy = crop.height / crop.srcH
            val boxes = passes.getOrElse(call++) { emptyList() }
            return boxes.filter { it.second >= minScore }.map { (r, s) ->
                DetectedFace(
                    RectF(
                        (r.left - crop.srcX) * sx,
                        (r.top - crop.srcY) * sy,
                        (r.right - crop.srcX) * sx,
                        (r.bottom - crop.srcY) * sy,
                    ),
                    s,
                )
            }
        }

        override fun close() = Unit
    }

    /** A scripted photo: its size, the boxes each pass returns, and the two thresholds. */
    private class Case(
        val name: String,
        val w: Int,
        val h: Int,
        val passes: List<List<Pair<RectF, Float>>>,
        val t: Float,
        val tLow: Float,
        /**
         * True when the plan without part masks must lose pixels at every expansion (a cut across the whole width or
         * height: the sliver never vanishes); false when only at the default expansion.
         */
        val needsPartsAtEveryExpansion: Boolean,
    )

    private fun detection(case: Case, threshold: Float, masking: MaskingConfig): Detection {
        val tiler = TiledFaceDetector(ScriptedEngine(case.passes), DetectionSettings(threshold))
        val out = tiler.detect(FakeDetectionImage(case.w, case.h))
        val timings = Timings(null, null, 0.0, out.passes, 0.0, null, 0.0, null)
        return Detection(
            case.w,
            case.h,
            out.faces.map { it.toFaceBox() },
            timings,
            false,
            masking,
            emptyList(),
            out.parts.map { FacePart(it.face, DetectedFace(it.rect).toFaceBox()) },
            out.covers,
        )
    }

    private fun r(l: Number, t: Number, rr: Number, b: Number) =
        RectF(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat())

    /**
     * The scripted cases. Tiles of a 640 px input with overlap 0.25: on a 1,000 px axis they start at 0 and 360, so
     * the inner borders are x (or y) = 360 and 640. A box that touches an inner border of its tile is cut.
     */
    private val cases =
        listOf(
            // The right part of a face, cut at x = 360 by tile 2 (higher score), and the whole face (lower score).
            Case(
                "cut half",
                1000,
                640,
                listOf(
                    listOf(r(300, 200, 500, 400) to 0.6f),
                    listOf(r(300, 200, 500, 400) to 0.6f),
                    listOf(r(360, 200, 500, 400) to 0.9f),
                ),
                0.8f,
                0.5f,
                true,
            ),
            // A corner piece (cut on the left at x = 360 and on the top at y = 360), then the two pieces cut on one
            // side, then the whole face: a chain of states. The face is placed so that the corner piece's mask sticks
            // out of the face's mask at the default expansion (1.6 / 1.8).
            Case(
                "corner piece then side pieces then whole",
                1000,
                1000,
                listOf(
                    listOf(r(310, 200, 500, 400) to 0.5f),
                    listOf(r(310, 200, 500, 400) to 0.5f),
                    listOf(r(360, 200, 500, 400) to 0.9f),
                    listOf(r(310, 360, 500, 400) to 0.85f),
                    listOf(r(360, 360, 500, 400) to 0.95f),
                ),
                0.92f,
                0.45f,
                false,
            ),
            // A thin strip: the lower 35 % of a face, cut at y = 360.
            Case(
                "thin strip",
                640,
                1000,
                listOf(
                    listOf(r(200, 230, 400, 430) to 0.6f),
                    listOf(r(200, 230, 400, 430) to 0.6f),
                    listOf(r(200, 360, 400, 430) to 0.9f),
                ),
                0.8f,
                0.5f,
                true,
            ),
            // A face at the left image edge (clipped there) that crosses the border at x = 360.
            Case(
                "left image edge",
                1000,
                640,
                listOf(
                    listOf(r(-50, 180, 500, 530) to 0.6f),
                    listOf(r(-50, 180, 500, 530) to 0.6f),
                    listOf(r(360, 180, 500, 530) to 0.9f),
                ),
                0.8f,
                0.5f,
                // At ×3 the part's sliver lies above and below the image.
                false,
            ),
            // A face at the top image edge (clipped there) that crosses the border at y = 360.
            Case(
                "top image edge",
                640,
                1000,
                listOf(
                    listOf(r(100, -60, 500, 440) to 0.6f),
                    listOf(r(100, -60, 500, 440) to 0.6f),
                    listOf(r(100, 360, 500, 440) to 0.9f),
                ),
                0.8f,
                0.5f,
                // At ×3 the part's sliver lies left and right of the image.
                false,
            ),
            // A face at the bottom-right corner (clipped on two sides), cut at x = 640 by the first tile.
            Case(
                "bottom-right corner",
                1000,
                640,
                listOf(
                    listOf(r(560, 450, 1060, 700) to 0.6f),
                    listOf(r(560, 450, 640, 640) to 0.9f),
                    listOf(r(560, 450, 1060, 700) to 0.6f),
                ),
                0.8f,
                0.5f,
                true,
            ),
        )

    private val maskings =
        listOf(
            MaskingConfig(shape = MaskShape.ELLIPSE),
            MaskingConfig(shape = MaskShape.RECTANGLE),
            MaskingConfig(shape = MaskShape.ELLIPSE, expandX = 3f, expandY = 3f),
            MaskingConfig(shape = MaskShape.RECTANGLE, expandX = 3f, expandY = 3f),
        )

    @Test
    fun everyPixelMaskedAtAHigherThresholdIsMaskedAtALowerOne() {
        for (case in cases) {
            for (masking in maskings) {
                val what = "${case.name}, ${masking.shape} ${masking.expandX}/${masking.expandY}"
                val high = detection(case, case.t, masking)
                val low = detection(case, case.tLow, masking)
                assertEquals(1, low.faces.size, "$what: one face at the lower threshold")
                val planHigh = high.toMaskPlan().masks
                val planLow = low.toMaskPlan().masks
                assertTrue(planHigh.isNotEmpty(), what)
                assertEquals(emptyList(), uncovered(case, planHigh, planLow, library = true), "$what (library)")
                assertEquals(emptyList(), uncovered(case, planHigh, planLow, library = false), "$what (oracle)")
            }
        }
    }

    @Test
    fun withoutPartMasksTheCutCasesLosePixelsAtTheDefaultAndAtThreeTimes() {
        for (case in cases) {
            for (masking in maskings.filter { it.shape == MaskShape.ELLIPSE }) {
                if (masking.expandX > 1.6f && !case.needsPartsAtEveryExpansion) continue
                val what = "${case.name}, ${masking.expandX}"
                val high = detection(case, case.t, masking).toMaskPlan().masks
                val low = detection(case, case.tLow, masking)
                val facesOnly = low.faces.map { Mask.fromFace(it, masking) }
                assertTrue(uncovered(case, high, facesOnly, library = false).isNotEmpty(), "negative control: $what")
                assertTrue(low.toMaskPlan().masks.size > low.faces.size, "$what: part masks in the plan")
            }
        }
    }

    @Test
    fun aMergedFaceHasMoreMasksThanFacesAndFromFaceGivesOnlyItsOwnMask() {
        val case = cases.first()
        val low = detection(case, case.tLow, MaskingConfig())
        assertEquals(1, low.faces.size)
        assertEquals(2, low.toMaskPlan().masks.size, "the face's mask, then the part mask of the cut state")
        assertEquals(Mask.fromFace(low.faces.single()), low.toMaskPlan().masks.first())
        // Same parts with another masking; equality covers the parts.
        assertEquals(2, low.toMaskPlan(MaskingConfig(shape = MaskShape.RECTANGLE)).masks.size)
        assertEquals(low, detection(case, case.tLow, MaskingConfig()))
        assertFalse(low == detection(case, case.t, MaskingConfig()))
    }

    /** Random candidate sets at the merge level: the property holds for every pair of thresholds. */
    @Test
    fun theMergeKeepsThePropertyOnRandomSets() {
        val random = Random(9001)
        var partsSeen = 0
        repeat(150) {
            val candidates = ArrayList<MergeCandidate>()
            repeat(random.nextInt(1, 4)) {
                val x = 20f + random.nextFloat() * 120f
                val y = 20f + random.nextFloat() * 120f
                val s = 15f + random.nextFloat() * 40f
                repeat(random.nextInt(1, 7)) {
                    // Cut parts and jittered whole boxes of the face.
                    val l = x + if (random.nextBoolean()) random.nextFloat() * s * 0.6f else -random.nextFloat() * 2f
                    val t = y + if (random.nextBoolean()) random.nextFloat() * s * 0.6f else -random.nextFloat() * 2f
                    val rr =
                        x + s - if (random.nextBoolean()) random.nextFloat() * s * 0.3f else -random.nextFloat() * 2f
                    val b =
                        y + s - if (random.nextBoolean()) random.nextFloat() * s * 0.3f else -random.nextFloat() * 2f
                    if (rr > l + 1f && b > t + 1f) {
                        candidates +=
                            MergeCandidate(
                                DetectedFace(RectF(l, t, rr, b), random.nextInt(1, 100) / 100f),
                                random.nextBoolean(),
                            )
                    }
                }
            }
            for (masking in maskings) {
                for ((t, tLow) in listOf(0.7f to 0.3f, 0.5f to 0.05f, 0.9f to 0.6f)) {
                    val high = plan(candidates.filter { it.face.score >= t }, masking)
                    val lowResult = Nms.mergeCandidates(candidates.filter { it.face.score >= tLow }, 0.4f, 0.7f)
                    partsSeen += lowResult.parts.size
                    val low = plan(candidates.filter { it.face.score >= tLow }, masking)
                    val square = Case("random", 200, 200, emptyList(), t, tLow, false)
                    assertEquals(
                        emptyList(),
                        uncovered(square, high, low, library = false),
                        "set $it $masking $t/$tLow",
                    )
                }
            }
        }
        assertTrue(partsSeen > 100, "the sets exercise part masks ($partsSeen)")
    }

    /**
     * A cut face that grows step by step to one side (scores descending), [steps] steps in total, of [size] px; the
     * first [highSteps] are above the higher threshold.
     */
    private fun growingFace(steps: Int, size: Float, grow: Float): List<MergeCandidate> {
        val out = ArrayList<MergeCandidate>()
        out += MergeCandidate(DetectedFace(RectF(50f, 50f, 50f + size, 50f + size * 1.3f), 0.99f), true)
        for (i in 1..steps) {
            out += MergeCandidate(
                DetectedFace(RectF(50f, 50f, 50f + size + i * grow, 50f + size * 1.3f), 0.98f - i * 0.002f),
                true,
            )
        }
        return out
    }

    @Test
    fun aFaceBelowTheCapAtTheHigherThresholdAndAboveItAtTheLowerOneKeepsThePropertyAlsoForTinyBoxes() {
        val cap = MaskStageLimits.MAX_PART_MASK_GROWTH
        val square = Case("cap", 200, 200, emptyList(), 0f, 0f, false)
        for ((size, grow) in listOf(20f to 1.5f, 6f to 0.5f, 1.2f to 0.1f, 0.4f to 0.05f)) {
            val all = growingFace(cap + 15, size, grow)
            for (masking in maskings) {
                // The higher threshold keeps the first 20 growth steps (below the cap), the lower one all of them.
                val tHigh = 0.98f - 20 * 0.002f
                val high = plan(all.filter { it.face.score >= tHigh - 1e-4f }, masking)
                val lowMerge = Nms.mergeCandidates(all, 0.4f, 0.7f)
                assertEquals(setOf(0), lowMerge.covers, "$size: the lower threshold draws a cover")
                val low = plan(all, masking)
                assertEquals(MaskShape.RECTANGLE, low.single().shape)
                val what = "size $size, ${masking.shape} ${masking.expandX}"
                assertEquals(emptyList(), uncovered(square, high, low, library = true), "$what (library)")
                assertEquals(emptyList(), uncovered(square, high, low, library = false), "$what (oracle)")
                // Above the cap at both thresholds: the covers nest.
                val highAbove = plan(all.filter { it.face.score >= 0.98f - (cap + 5) * 0.002f - 1e-4f }, masking)
                assertEquals(emptyList(), uncovered(square, highAbove, low, library = true), "$what both above")
            }
        }
    }

    @Test
    fun withoutTheCoverAFaceAboveTheCapLosesPixelsOfItsEarlierStates() {
        val cap = MaskStageLimits.MAX_PART_MASK_GROWTH
        val square = Case("cap", 200, 200, emptyList(), 0f, 0f, false)
        val all = growingFace(cap + 15, 20f, 1.5f)
        val masking = MaskingConfig()
        val high = plan(all.filter { it.face.score >= 0.98f - 20 * 0.002f - 1e-4f }, masking)
        val face = Nms.mergeCandidates(all, 0.4f, 0.7f).faces.single().toFaceBox()
        val ownEllipseOnly = listOf(Mask.fromFace(face, masking))
        assertTrue(uncovered(square, high, ownEllipseOnly, library = true).isNotEmpty(), "negative control")
    }

    private fun plan(candidates: List<MergeCandidate>, masking: MaskingConfig): List<Mask> {
        val m = Nms.mergeCandidates(candidates, 0.4f, 0.7f)
        val d = Detection(
            200,
            200,
            m.faces.map { it.toFaceBox() },
            Timings(null, null, 0.0, 1, 0.0, null, 0.0, null),
            false,
            masking,
            emptyList(),
            m.parts.map {
                FacePart(it.face, FaceBox.create(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom, 1f))
            },
            m.covers,
        )
        return d.toMaskPlan().masks
    }

    /** Pixels (x, y) inside a mask of [high] and outside every mask of [low], within the image (at most 10). */
    private fun uncovered(case: Case, high: List<Mask>, low: List<Mask>, library: Boolean): List<Pair<Int, Int>> {
        val inLow = BooleanArray(case.w * case.h)
        for (m in low) forEachInside(case, m, library) { x, y -> inLow[y * case.w + x] = true }
        val out = ArrayList<Pair<Int, Int>>()
        for (m in high) {
            forEachInside(case, m, library) { x, y -> if (!inLow[y * case.w + x] && out.size < 10) out += x to y }
        }
        return out
    }

    private inline fun forEachInside(case: Case, m: Mask, library: Boolean, f: (Int, Int) -> Unit) {
        val x0 = max(0, floor(m.centerX - m.radiusX - 1).toInt())
        val x1 = min(case.w - 1, ceil(m.centerX + m.radiusX + 1).toInt())
        val y0 = max(0, floor(m.centerY - m.radiusY - 1).toInt())
        val y1 = min(case.h - 1, ceil(m.centerY + m.radiusY + 1).toInt())
        // The drawn geometry: the stage raises radii below 1 px to 1 px around the same centre.
        val g = PreparedMask.usable(m.toGeometry())
        val rx = max(m.radiusX.toDouble(), 1.0)
        val ry = max(m.radiusY.toDouble(), 1.0)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val inside =
                    if (library) {
                        g.containsPixel(x, y)
                    } else {
                        // Independent oracle: pixel centre, double arithmetic, from the mask values only.
                        val dx = (x + 0.5 - m.centerX.toDouble()) / rx
                        val dy = (y + 0.5 - m.centerY.toDouble()) / ry
                        if (m.shape == MaskShape.ELLIPSE) dx * dx + dy * dy <= 1.0 else max(abs(dx), abs(dy)) <= 1.0
                    }
                if (inside) f(x, y)
            }
        }
    }
}
