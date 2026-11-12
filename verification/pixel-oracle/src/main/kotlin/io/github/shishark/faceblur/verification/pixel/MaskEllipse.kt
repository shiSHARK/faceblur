// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The mask region derived from FR-004 by this oracle itself (no library code). For a face box with left/top/width w and
 * height h in pixels: centre x = left + w / 2, centre y = top + h / 2 - 0.08 h, semi-axes 0.8 w and 0.9 h, and
 * normalised radius r = hypot((x - cx) / (0.8 w), (y - cy) / (0.9 h)) for the CENTRE of pixel (x, y), that is at
 * x + 0.5, y + 0.5, a convention fixed by ADR 0002 together with the boundary tolerance [BOUNDARY_EPSILON]: pixels whose
 * r differs from 1 by less than that are ambiguous and never judged. The soft edge lies outside r = 1 and is
 * max(feather, 1 / smaller semi-axis) wide in r (feather 0.10 by default, 0 to 0.5; at least 1 px). The feather must be
 * the one of the library configuration under test.
 */
public class MaskEllipse(
    cx: Double,
    cy: Double,
    rx: Double,
    ry: Double,
    /** The configured feather, 0 to 0.5 (the range the owner approved; widening it needs the owner). */
    public val feather: Double = SOFT_EDGE,
) {
    init {
        require(rx > 0 && ry > 0 && cx.isFinite() && cy.isFinite() && rx.isFinite() && ry.isFinite()) {
            "mask ellipse must have finite centre and positive semi-axes"
        }
        require(feather in 0.0..MAX_FEATHER) { "feather must be within 0 to $MAX_FEATHER" }
    }

    public val cx: Double = cx

    public val cy: Double = cy

    /**
     * Semi-axes in pixels. A positive semi-axis below 1 px (for example a face box clipped to a sliver) is raised to
     * [MIN_RADIUS] on that axis, as the renderer does (ADR 0002): with both at least 1 px the pixel nearest to the
     * centre is always inside the mask. It only adds masking; the oracle judges the raised ellipse.
     */
    public val rx: Double = max(rx, MIN_RADIUS)

    public val ry: Double = max(ry, MIN_RADIUS)

    // The semi-axes as given: scaling applies to these and the raise happens after, as in the renderer.
    private val givenRx: Double = rx
    private val givenRy: Double = ry

    public val smallerSemiAxis: Double get() = min(rx, ry)

    /** Width of the outward soft edge in units of r. */
    public val softEdgeWidth: Double get() = max(feather, 1.0 / smallerSemiAxis)

    /**
     * The owner's band, fixed in pixels before any test runs (ADR 0002): no pixel further than this beyond the
     * ellipse may change. W = softEdgeWidth x the larger semi-axis (along the smaller one the band is at most
     * softEdgeWidth x the smaller semi-axis). It comes from the geometry and the configured feather only, never from
     * the output under test, and it is not widened without the owner's approval.
     */
    public val maxBandWidthPx: Double get() = softEdgeWidth * max(rx, ry)

    /** Normalised radius of the centre of pixel (x, y). */
    public fun radius(x: Int, y: Int): Double = hypot((x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry)

    /** Euclidean distance in pixels from the point ([px], [py]) to the ellipse (0 inside), by Eberly's bisection. */
    public fun distanceTo(px: Double, py: Double): Double {
        var e0 = rx
        var e1 = ry
        var y0 = abs(px - cx)
        var y1 = abs(py - cy)
        if (e0 < e1) {
            val te = e0
            e0 = e1
            e1 = te
            val ty = y0
            y0 = y1
            y1 = ty
        }
        if ((y0 / e0) * (y0 / e0) + (y1 / e1) * (y1 / e1) <= 1.0) return 0.0
        if (y1 > 0) {
            if (y0 > 0) {
                val z0 = y0 / e0
                val z1 = y1 / e1
                val g = z0 * z0 + z1 * z1 - 1
                val r0 = (e0 / e1) * (e0 / e1)
                val sbar = root(r0, z0, z1, g)
                return hypot(r0 * y0 / (sbar + r0) - y0, y1 / (sbar + 1) - y1)
            }
            return abs(y1 - e1)
        }
        val numer = e0 * y0
        val denom = e0 * e0 - e1 * e1
        if (numer < denom) {
            val xde = numer / denom
            return hypot(e0 * xde - y0, e1 * sqrt(1 - xde * xde))
        }
        return abs(y0 - e0)
    }

    private fun root(r0: Double, z0: Double, z1: Double, g: Double): Double {
        val n0 = r0 * z0
        var s0 = z1 - 1
        var s1 = if (g < 0) 0.0 else hypot(n0, z1) - 1
        var s = 0.0
        repeat(ROOT_ITERATIONS) {
            s = (s0 + s1) / 2
            if (s == s0 || s == s1) return s
            val ratio0 = n0 / (s + r0)
            val ratio1 = z1 / (s + 1)
            val value = ratio0 * ratio0 + ratio1 * ratio1 - 1
            if (value > 0) {
                s0 = s
            } else if (value < 0) {
                s1 = s
            } else {
                return s
            }
        }
        return s
    }

    /** The same ellipse for an image scaled by [factor] (output width / working width). */
    public fun scaled(factor: Double): MaskEllipse =
        MaskEllipse(cx * factor, cy * factor, givenRx * factor, givenRy * factor, feather)

    public companion object {
        public const val EXPAND_X: Double = 1.6
        public const val EXPAND_Y: Double = 1.8
        public const val SHIFT_UP: Double = 0.08
        private const val ROOT_ITERATIONS: Int = 149
        public const val SOFT_EDGE: Double = 0.10
        public const val MAX_FEATHER: Double = 0.5

        /** Smallest semi-axis in pixels (ADR 0002: a smaller positive radius is raised to this). */
        public const val MIN_RADIUS: Double = 1.0

        /** |r - 1| below this is too close to the boundary for float-vs-double rounding to be judged. */
        public const val BOUNDARY_EPSILON: Double = 1e-4

        public fun fromFaceBox(
            left: Double,
            top: Double,
            width: Double,
            height: Double,
            feather: Double = SOFT_EDGE,
        ): MaskEllipse = MaskEllipse(
            cx = left + width / 2,
            cy = top + height / 2 - SHIFT_UP * height,
            rx = EXPAND_X / 2 * width,
            ry = EXPAND_Y / 2 * height,
            feather = feather,
        )
    }
}
