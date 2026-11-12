// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.MaskShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Integer pixel rectangle, right/bottom exclusive. */
internal class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val area: Int get() = if (isEmpty) 0 else width * height

    fun intersects(o: IRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun union(o: IRect) = IRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun clip(w: Int, h: Int) =
        IRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
}

/** A mask plus the derived per-face parameters every effect needs (normalized units: 1 = mask radius). */
internal class PreparedMask(val geometry: MaskGeometry, config: MaskSettings) {
    val cx = geometry.cx
    val cy = geometry.cy
    val rx = geometry.rx
    val ry = geometry.ry
    val ellipse = geometry.shape == MaskShape.ELLIPSE
    private val minR = min(rx, ry)

    /** Outward soft edge; never thinner than ~1 px so that the edge is anti-aliased. */
    val featherN: Float = featherOf(config, minR)

    /** Sampling ring outside the mask (GRADIENT_OVAL / SMOOTH_FILL); at least 2 px. */
    val ringN: Float = ringOf(config, minR)

    /**
     * Normalized radius of the centre of pixel (x, y) — computed with exactly the same float operations as
     * [MaskGeometry.normalizedRadius] (division, not multiplication by 1/r), so "inside" is decided identically
     * everywhere (privacy-relevant at the boundary pixels). Hot loops inline the same expression.
     */
    fun radius(x: Int, y: Int): Float {
        val dx = (x + 0.5f - cx) / rx
        val dy = (y + 0.5f - cy) / ry
        return if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
    }

    /** 1 inside, smoothstep fall-off over the feather band, 0 beyond. */
    fun alpha(r: Float): Float {
        if (r <= 1f) return 1f
        val t = (r - 1f) / featherN
        if (t >= 1f) return 0f
        return 1f - t * t * (3f - 2f * t)
    }

    /** Pixels whose centre may have normalized radius ≤ [q], clipped to the image. */
    fun extent(q: Float, imageW: Int, imageH: Int): IRect {
        // The same float expressions as always, but converted through Double and clamped to the image before the
        // Int conversion: a mask larger than the Int range covers the whole image instead of wrapping to an empty
        // area (which would leave it silently unwritten).
        return IRect(
            extentLow(cx - q * rx - 0.5f, imageW),
            extentLow(cy - q * ry - 0.5f, imageH),
            extentHigh(cx + q * rx - 0.5f, imageW),
            extentHigh(cy + q * ry - 0.5f, imageH),
        )
    }

    companion object {
        /**
         * Smallest mask radius in pixels. With both radii at least 1 px, the pixel whose centre is nearest to the
         * mask centre (at most 0.5 px away on each axis) is always inside the mask (r <= sqrt(0.5) < 1).
         */
        const val MIN_RADIUS: Float = 1f

        /**
         * Largest accepted centre coordinate or radius in pixels (2²²): float pixel arithmetic is exact to 0.5 px up
         * to here, and images are at most 65,535 px on a side, so every real mask fits with a wide margin.
         */
        const val MAX_COORDINATE: Float = 4_194_304f

        /**
         * Fail closed: a mask that cannot be drawn is an error, never silently skipped. A positive radius
         * below [MIN_RADIUS] (for example a face box clipped by the image border to a sliver) is raised to
         * [MIN_RADIUS] instead: this only adds masking.
         */
        fun usable(g: MaskGeometry): MaskGeometry {
            validate(g)
            if (g.rx >= MIN_RADIUS && g.ry >= MIN_RADIUS) return g
            return g.copy(rx = maxOf(g.rx, MIN_RADIUS), ry = maxOf(g.ry, MIN_RADIUS))
        }

        /** The checks of [usable] without the copy: throws [CoreFailure.InvalidMask] for a mask that cannot be drawn. */
        fun validate(g: MaskGeometry) {
            if (!(g.cx.isFinite() && g.cy.isFinite() && g.rx.isFinite() && g.ry.isFinite())) {
                throw CoreFailure.InvalidMask("non-finite centre or radius")
            }
            if (!(g.rx > 0f && g.ry > 0f)) throw CoreFailure.InvalidMask("radius not positive")
            val bound = MAX_COORDINATE
            if (!(g.rx <= bound && g.ry <= bound && g.cx in -bound..bound && g.cy in -bound..bound)) {
                throw CoreFailure.InvalidMask("centre or radius outside the supported range")
            }
        }
    }
}

/** atan2 with ≤ 3e-4 rad (0.02°) error — far below the ≥ 2.8° angular bins; cheaper than libm on every platform. */
internal fun fastAtan2(y: Float, x: Float): Float {
    val ax = abs(x)
    val ay = abs(y)
    if (ax == 0f && ay == 0f) return 0f
    val a = min(ax, ay) / max(ax, ay)
    val s = a * a
    var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
    if (ay > ax) r = 1.5707964f - r
    if (x < 0f) r = 3.1415927f - r
    return if (y < 0f) -r else r
}

internal fun clamp255(v: Int): Int = if (v < 0) {
    0
} else if (v > 255) {
    255
} else {
    v
}

/**
 * The first pixel whose centre may lie at or after [v] (a mask edge in pixels minus 0.5), clamped to 0..[limit].
 * Converted through Double and clamped before the Int conversion: a mask larger than the Int range covers the whole
 * image instead of wrapping to an empty area (which would leave it silently unwritten).
 */
internal fun extentLow(v: Float, limit: Int): Int = floor(v.toDouble()).coerceIn(0.0, limit.toDouble()).toInt()

/** The end (exclusive) of the pixels whose centre may lie at or before [v], clamped to 0..[limit]. */
internal fun extentHigh(v: Float, limit: Int): Int = (ceil(v.toDouble()) + 1.0).coerceIn(0.0, limit.toDouble()).toInt()

/** The outward soft edge in normalised units for the smaller radius [minR]: at least about 1 px. */
internal fun featherOf(config: MaskSettings, minR: Float): Float =
    max(if (config.feather.isFinite()) config.feather else 0f, 1f / minR)

/** The sampling ring in normalised units for the smaller radius [minR]: at least 2 px. */
internal fun ringOf(config: MaskSettings, minR: Float): Float =
    max(if (config.ringWidth.isFinite()) config.ringWidth else 0f, 2f / minR)
