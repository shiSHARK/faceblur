// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FacePart
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.Timings
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskStageLimits
import io.github.shishark.faceblur.mask.PreparedMask
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
 * The growth cap and the rectangle cover (ADR 0013 decision 1): a face that grew more than 32 times is drawn as one
 * RECTANGLE whose integer edges hold every drawn earlier state (radii raised to 1 px included); the cover's pixel set
 * is its integer rectangle; covers nest exactly between thresholds, also near 65,535 px with quarter-pixel edges.
 */
class CoverTest {
    private val cap = MaskStageLimits.MAX_PART_MASK_GROWTH

    /** One cut box that grows to the right by [step] px in each of [n] merge steps. */
    private fun chain(n: Int, step: Float = 1f): List<MergeCandidate> {
        val out = ArrayList<MergeCandidate>()
        out += MergeCandidate(DetectedFace(RectF(100f, 100f, 140f, 200f), 0.99f), true)
        for (i in 1..n) {
            out += MergeCandidate(DetectedFace(RectF(100f, 100f, 140f + i * step, 200f), 0.99f - i * 1e-5f), true)
        }
        return out
    }

    private fun detection(m: MergeResult, masking: MaskingConfig = MaskingConfig()) = Detection(
        10_000,
        10_000,
        m.faces.map { it.toFaceBox() },
        Timings(null, null, 0.0, 1, 0.0, null, 0.0, null),
        false,
        masking,
        emptyList(),
        m.parts.map { FacePart(it.face, FaceBox.create(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom, 1f)) },
        m.covers,
    )

    @Test
    fun aFaceGrownAtMost32TimesKeepsItsPartsAndOneMoreGivesExactlyOneRectangle() {
        for (n in listOf(1, 5, cap)) {
            val m = Nms.mergeCandidates(chain(n), 0.4f, 0.7f)
            assertEquals(1, m.faces.size, "n=$n")
            assertTrue(m.covers.isEmpty(), "n=$n: no cover at or below the cap")
            val plan = detection(m).toMaskPlan().masks
            assertEquals(1 + m.parts.size, plan.size, "n=$n: the face and its parts")
            assertEquals(MaskShape.ELLIPSE, plan.first().shape)
        }
        val over = Nms.mergeCandidates(chain(cap + 1), 0.4f, 0.7f)
        assertEquals(setOf(0), over.covers)
        assertEquals(0, over.parts.size, "a cover face has no part masks")
        for (shape in MaskShape.entries) {
            val plan = detection(over, MaskingConfig(shape = shape)).toMaskPlan().masks
            assertEquals(1, plan.size, "$shape: exactly one mask")
            assertEquals(MaskShape.RECTANGLE, plan.single().shape, "$shape: the cover is a rectangle")
        }
    }

    @Test
    fun aLongGrowthChainEndsWithOneMaskAndCompletes() {
        val m = Nms.mergeCandidates(chain(3_000, step = 0.25f), 0.4f, 0.7f)
        assertEquals(1, m.faces.size)
        assertEquals(setOf(0), m.covers)
        assertEquals(1, detection(m).toMaskPlan().masks.size)
    }

    @Test
    fun thePartTestCountsItsEvaluationsAsMergeWork() {
        // A small cut box, then a large whole box around it: the earlier state is centred in the final box, so the
        // part test runs its evaluations (at least the 64 start angles) before it drops the part.
        fun set(cut: Boolean) = listOf(
            MergeCandidate(DetectedFace(RectF(145f, 145f, 155f, 155f), 0.99f), cut),
            MergeCandidate(DetectedFace(RectF(100f, 100f, 200f, 200f), 0.98f), false),
        )
        val m = Nms.mergeCandidates(set(true), 0.4f, 0.7f)
        val without = Nms.mergeCandidates(set(false), 0.4f, 0.7f)
        assertEquals(0, m.parts.size, "the centred state is dropped")
        assertTrue(m.work >= without.work + 64, "part tests add work (${m.work} vs ${without.work})")
        val e = kotlin.runCatching {
            Nms.mergeCandidates(set(true), 0.4f, 0.7f, maxWork = m.work - 1)
        }.exceptionOrNull()
        assertTrue(e is OutputCheckFailed && e.findings == listOf("TOO_MANY_MERGE_CANDIDATES"), "bound applies")
        assertEquals(m.work, Nms.mergeCandidates(set(true), 0.4f, 0.7f, maxWork = m.work).work, "exactly at the bound")
    }

    /** Drawn pixels of [m] (radii raised to 1 px, the stage's float test), inside a scan window. */
    private inline fun forEachDrawn(m: Mask, f: (Int, Int) -> Unit) {
        val g = PreparedMask.usable(m.toGeometry())
        val x0 = floor(g.cx - g.rx - 2).toInt()
        val x1 = ceil(g.cx + g.rx + 2).toInt()
        val y0 = floor(g.cy - g.ry - 2).toInt()
        val y1 = ceil(g.cy + g.ry + 2).toInt()
        for (y in y0..y1) for (x in x0..x1) if (g.containsPixel(x, y)) f(x, y)
    }

    private fun coverRect(box: FaceBox, masking: MaskingConfig): IntArray {
        val e = Mask.coverEdges(box.left, box.top, box.right, box.bottom, masking.expandX, masking.expandY)
        return intArrayOf(e[0].toInt(), e[1].toInt(), e[2].toInt(), e[3].toInt())
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = FaceBox.create(l, t, r, b, 1f)

    @Test
    fun theCoverHoldsEveryDrawnEarlierStateAndItsPixelSetIsItsIntegerRectangle() {
        val random = Random(13)
        var cases = 0
        repeat(1_500) { k ->
            val masking = MaskingConfig(
                shape = if (k % 2 == 0) MaskShape.ELLIPSE else MaskShape.RECTANGLE,
                expandX = 1.6f + random.nextFloat() * 1.4f,
                expandY = 1.8f + random.nextFloat() * 1.2f,
            )
            val tiny = k % 3 == 0
            val w = if (tiny) 0.02f + random.nextFloat() * 6f else 6f + random.nextFloat() * 60f
            val h = if (tiny) 0.02f + random.nextFloat() * 6f else 6f + random.nextFloat() * 60f
            val l = random.nextFloat() * (65_535f - w)
            val t = random.nextFloat() * (65_535f - h)
            val face = box(l, t, l + w, t + h)
            val cover = Mask.cover(face, masking)
            val rect = coverRect(face, masking)
            // The pixel set equals the integer rectangle (checked on a ring around its edges and inside).
            val g = cover.toGeometry()
            for (y in rect[1] - 1..rect[3]) {
                for (x in rect[0] - 1..rect[2]) {
                    val inRect = x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]
                    assertEquals(inRect, g.containsPixel(x, y), "case $k pixel ($x, $y)")
                }
            }
            // A sub-box with edges shared or moved inwards: its drawn mask, and the face's own, lie in the cover.
            val sl = if (random.nextBoolean()) l else l + random.nextFloat() * w * 0.5f
            val st = if (random.nextBoolean()) t else t + random.nextFloat() * h * 0.5f
            val sr = if (random.nextBoolean()) l + w else max(sl + 0.01f, l + w - random.nextFloat() * w * 0.5f)
            val sb = if (random.nextBoolean()) t + h else max(st + 0.01f, t + h - random.nextFloat() * h * 0.5f)
            for (state in listOf(face, box(sl, st, min(sr, l + w), min(sb, t + h)))) {
                forEachDrawn(Mask.fromFace(state, masking)) { x, y ->
                    assertTrue(
                        x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3],
                        "case $k: drawn pixel ($x, $y) outside the cover",
                    )
                }
            }
            cases++
        }
        assertEquals(1_500, cases)
    }

    @Test
    fun growthChainsClampedAtTheImageEdgesStayInsideTheirCover() {
        val random = Random(17)
        val imageW = 640f
        val imageH = 480f
        repeat(200) { k ->
            val masking = MaskingConfig(shape = if (k % 2 == 0) MaskShape.ELLIPSE else MaskShape.RECTANGLE)
            var l = random.nextFloat() * imageW
            var t = random.nextFloat() * imageH
            var r = min(imageW, l + 0.5f + random.nextFloat() * 4f)
            var b = min(imageH, t + 0.5f + random.nextFloat() * 4f)
            val states = ArrayList<FaceBox>()
            states += box(l, t, r, b)
            repeat(33 + random.nextInt(28)) {
                when (random.nextInt(4)) {
                    0 -> l = max(0f, l - random.nextFloat() * 3f)
                    1 -> t = max(0f, t - random.nextFloat() * 3f)
                    2 -> r = min(imageW, r + random.nextFloat() * 3f)
                    else -> b = min(imageH, b + random.nextFloat() * 3f)
                }
                states += box(l, t, r, b)
            }
            val rect = coverRect(states.last(), masking)
            for (s in states) {
                forEachDrawn(Mask.fromFace(s, masking)) { x, y ->
                    assertTrue(x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3], "chain $k ($x, $y)")
                }
            }
        }
    }

    @Test
    fun coversNestExactlyNear65535WithQuarterPixelSharedEdges() {
        val random = Random(29)
        repeat(4_000) {
            val masking = MaskingConfig(
                expandX = 1.6f + random.nextInt(7) * 0.2f,
                expandY =
                1.8f + random.nextInt(6) * 0.2f,
            )
            fun q() = random.nextInt(0, 4) * 0.25f
            // The higher threshold's box, near the far corner, edges on quarter pixels.
            val l = 65_400f + random.nextInt(100) + q()
            val t = 65_400f + random.nextInt(100) + q()
            val r = min(65_535f, l + 1f + random.nextInt(30) + q())
            val b = min(65_535f, t + 1f + random.nextInt(30) + q())
            // The lower threshold's box shares some edges exactly and extends the others by quarter pixels.
            val ll = if (random.nextBoolean()) l else l - (1 + random.nextInt(4)) * 0.25f
            val tt = if (random.nextBoolean()) t else t - (1 + random.nextInt(4)) * 0.25f
            val rr = if (random.nextBoolean()) r else min(65_535f, r + (1 + random.nextInt(4)) * 0.25f)
            val bb = if (random.nextBoolean()) b else min(65_535f, b + (1 + random.nextInt(4)) * 0.25f)
            val hi = Mask.coverEdges(l, t, r, b, masking.expandX, masking.expandY)
            val lo = Mask.coverEdges(ll, tt, rr, bb, masking.expandX, masking.expandY)
            assertTrue(lo[0] <= hi[0] && lo[1] <= hi[1] && lo[2] >= hi[2] && lo[3] >= hi[3], "nesting at ($l, $t)")
            for (v in hi + lo) assertEquals(floor(v), v, "integer edges")
        }
    }

    @Test
    fun thePartTestDrawsPartsWithARadiusBelow105PxAndDropsOnlyContainedParts() {
        val face = RectF(0f, 0f, 100f, 100f)
        // Part radius at the smallest expansion: 1.6 × 1.3 / 2 = 1.04 px < 1.05: drawn.
        assertFalse(PartCover.covers(face, RectF(40f, 40f, 41.3f, 60f)))
        // Face radius below 1.05 (a tiny face, 1.6 × 1.2 / 2 = 0.96 px): every part is drawn.
        assertFalse(PartCover.covers(RectF(0f, 0f, 1.2f, 1.2f), RectF(0.2f, 0.2f, 1.0f, 1.0f)))
        // Just above the rule (1.6 × 1.32 / 2 = 1.056 px), a centred part is dropped; its drawn pixels lie inside the
        // drawn face mask.
        assertTrue(PartCover.covers(face, RectF(40f, 40f, 41.32f, 60f)))
        val random = Random(31)
        var dropped = 0
        repeat(3_000) {
            val fw = 2f + random.nextFloat() * 40f
            val fh = 2f + random.nextFloat() * 40f
            val f = RectF(10f, 10f, 10f + fw, 10f + fh)
            val pl = 10f + random.nextFloat() * fw * 0.6f
            val pt = 10f + random.nextFloat() * fh * 0.6f
            val p = RectF(
                pl,
                pt,
                pl + 0.05f + random.nextFloat() * (10f + fw - pl),
                pt + 0.05f + random.nextFloat() * (10f + fh - pt),
            )
            if (!PartCover.covers(f, p)) return@repeat
            dropped++
            for (masking in listOf(MaskingConfig(), MaskingConfig(expandX = 3f, expandY = 3f))) {
                for (shape in MaskShape.entries) {
                    val mk = MaskingConfig(shape = shape, expandX = masking.expandX, expandY = masking.expandY)
                    val faceMask = PreparedMask.usable(
                        Mask.fromFace(box(f.left, f.top, f.right, f.bottom), mk).toGeometry(),
                    )
                    forEachDrawn(Mask.fromFace(box(p.left, p.top, p.right, p.bottom), mk)) { x, y ->
                        assertTrue(faceMask.containsPixel(x, y), "dropped part pixel ($x, $y) outside the face mask")
                    }
                }
            }
        }
        assertTrue(dropped > 100, "the random pairs exercise dropped parts ($dropped)")
    }
}
