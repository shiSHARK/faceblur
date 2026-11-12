// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The soundness core of the part-mask drop test ([PartCover]) against an independent reference: the part's ellipse
 * boundary sampled densely in double precision and tested against the face's ellipse, both at the smallest expansion
 * (1.6 / 1.8, shift 0.08), without any margin. The near-tangent pairs were found by a search over corner states
 * (one box sharing the face's top edge and one side) at that expansion, on whole pixels.
 * - "Sticks out": the true maximum lies between two of the 64 start angles and leaves the face ellipse by more than
 *   the 0.05 px margin, while at every start angle (in the margin frame) the part looks inside. A test that only
 *   looked at the start angles would drop these parts; [PartCover] must draw them.
 * - "Inside the margin band": the part lies inside the face ellipse, but closer to its boundary than the margins
 *   (0.05 px plus 1e-4 relative); the margins make [PartCover] keep it (a test without margins would drop it).
 */
class PartCoverTest {
    private val ex = MaskSettings.DEFAULT_EXPAND_X.toDouble()
    private val ey = MaskSettings.DEFAULT_EXPAND_Y.toDouble()
    private val shift = MaskSettings.DEFAULT_SHIFT_UP.toDouble()

    private class Ellipse(val cx: Double, val cy: Double, val a: Double, val b: Double)

    private fun ellipse(r: RectF): Ellipse {
        val w = r.right.toDouble() - r.left.toDouble()
        val h = r.bottom.toDouble() - r.top.toDouble()
        return Ellipse(
            (r.left.toDouble() + r.right.toDouble()) / 2.0,
            (r.top.toDouble() + r.bottom.toDouble()) / 2.0 - shift * h,
            ex * w / 2.0,
            ey * h / 2.0,
        )
    }

    /**
     * The largest distance in pixels by which the part's boundary leaves the face ellipse (negative: the smallest
     * gap inside it), from 400,000 boundary points, along the face's normalised radius.
     */
    private fun stickOut(face: RectF, part: RectF): Double {
        val f = ellipse(face)
        val p = ellipse(part)
        var worst = Double.NEGATIVE_INFINITY
        val n = 400_000
        for (i in 0 until n) {
            val t = 2.0 * PI * i / n
            val x = p.cx + p.a * cos(t)
            val y = p.cy + p.b * sin(t)
            val dx = (x - f.cx) / f.a
            val dy = (y - f.cy) / f.b
            val rho = sqrt(dx * dx + dy * dy)
            // Distance along the ray from the face centre, in pixels: (rho - 1) times the ray's length at rho = 1.
            val along = (rho - 1.0) * sqrt((dx * f.a) * (dx * f.a) + (dy * f.b) * (dy * f.b)) / rho
            if (along > worst) worst = along
        }
        return worst
    }

    /** The largest value of the boundary function at the 64 start angles, in PartCover's margin frame. */
    private fun startAngleMax(face: RectF, part: RectF): Double {
        val f = ellipse(face)
        val p = ellipse(part)
        val grow = 1.0 + 1e-4 + 0.05 / min(p.a, p.b)
        val shrink = 1.0 - 1e-4 - 0.05 / min(f.a, f.b)
        val c = f.a * shrink
        val d = f.b * shrink
        val u = (p.cx - f.cx) / c
        val v = (p.cy - f.cy) / d
        val alpha = p.a * grow / c
        val beta = p.b * grow / d
        var best = 0.0
        for (i in 0 until 64) {
            val t = (2 * i + 1) * PI / 64
            val x = u + alpha * cos(t)
            val y = v + beta * sin(t)
            best = maxOf(best, x * x + y * y)
        }
        return best
    }

    private val sticksOut = listOf(
        RectF(0f, 0f, 1217f, 1198f) to RectF(569f, 0f, 1217f, 1046f),
        RectF(0f, 0f, 1244f, 1037f) to RectF(129f, 0f, 1244f, 520f),
        RectF(0f, 0f, 1340f, 920f) to RectF(654f, 0f, 1340f, 797f),
    )

    private val insideTheMarginBand = listOf(
        RectF(0f, 0f, 1189f, 1341f) to RectF(0f, 0f, 448f, 1092f),
        RectF(0f, 0f, 986f, 1291f) to RectF(0f, 0f, 618f, 1166f),
        RectF(0f, 0f, 1273f, 864f) to RectF(0f, 0f, 758f, 772f),
    )

    @Test
    fun aPartThatSticksOutBetweenTwoStartAnglesIsDrawn() {
        for ((face, part) in sticksOut) {
            val out = stickOut(face, part)
            assertTrue(out > 0.05, "reference: the part leaves the face by more than the margin ($out px)")
            assertTrue(startAngleMax(face, part) <= 1.0, "construction: every start angle looks inside")
            assertFalse(PartCover.covers(face, part), "the part must be drawn")
        }
    }

    @Test
    fun aPartInsideTheFaceButWithinTheMarginBandIsKept() {
        for ((face, part) in insideTheMarginBand) {
            val out = stickOut(face, part)
            // The band of PartCover's margins: 0.05 px plus 1e-4 of the radius (here of the face's larger radius).
            val f = ellipse(face)
            val band = 0.05 + 1e-4 * maxOf(f.a, f.b)
            assertTrue(out <= 0.0 && out > -band, "reference: inside, within the margin band ($out px, band $band)")
            assertFalse(PartCover.covers(face, part), "the margins keep the part")
        }
    }

    @Test
    fun clearlyContainedPartsAreDropped() {
        // Controls: the same faces with parts shrunk well inside are dropped (the test is not "never drop").
        for ((face, part) in sticksOut + insideTheMarginBand) {
            val cx = (part.left + part.right) / 2f
            val cy = (part.top + part.bottom) / 2f
            val small = RectF(
                cx - part.width * 0.3f,
                cy - part.height * 0.3f,
                cx + part.width * 0.3f,
                cy + part.height * 0.3f,
            )
            assertTrue(stickOut(face, small) < -1.0)
            assertTrue(PartCover.covers(face, small))
        }
    }
}
