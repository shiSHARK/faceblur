// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The mask stage is linear (ADR 0013 decision 3): the counted work, copy included, stays at or below
 *   C_mask × masks + C_read × Σ read area + C_shadow × shadow area
 * for many disjoint masks and for many identical masks stacked over one region. A step over pairs of masks or a
 * per-pixel scan over other masks would make the stacked case grow with the square of the mask count.
 *
 * The constants come from the code, not from measured runs (every loop adds its units through `StageWork`):
 * - per mask, every style: validation 1, sizing 1, the two tile walks of step 1 (count, then reset) 2, storing 1,
 *   the compute loop 1, the coverage loop 1 = 7. The tile walks visit the tiles of the write and the opaque
 *   rectangle twice: 4T with T ≤ (w/64 + 2)(h/64 + 2) ≤ 4 + 0.07·wh for a w × h write rectangle (w + h ≤ 2wh), so
 *   at most 16 more per mask and 0.28 per write pixel (counted as 1 per read pixel below). SMOOTH_FILL sizing adds
 *   at most 17 spacing-search and 40 pyramid steps.
 * - GRADIENT_OVAL per mask, b ≤ 128 bins: zeroing 64b + b, the empty check b, robust centres ≤ 255b, sums b,
 *   normalising b, circular fill ≤ 3 × 3b, smoothing ≤ 3 × (2b + 1 + b(b + 1)), averages ≤ 3b, colour table
 *   32 × (2b + b²) = 35b² + 408b + 3 ≤ 625,667 at b = 128.
 * - SMOOTH_FILL per mask: levels ≤ 40, and the cells: zeroing ≤ all-level cells (≤ 2·base + 40), normalising base,
 *   pull 4 per coarse cell, push ≤ 8 per fine cell: ≤ 27·base + 520, with base ≤ read area.
 * - per read pixel: GRADIENT_OVAL two passes × (row read + sample + coverage lookup) ≤ 6; SMOOTH_FILL row read +
 *   sample + lookup + 27 cell units ≤ 30; SOLID 0; every style 1 for the tile walks.
 * - per write pixel (≤ read pixel): coverage write 1, composite 1, row mark ≤ 1, fill value: SOLID 0, SMOOTH_FILL 1,
 *   GRADIENT_OVAL ≤ 5 (value, upsample and ≤ 4 grid points per pixel of a tile region).
 * - per shadow pixel: tile load 1, copy 1, copy row loop ≤ 1, heap sort of the touched tiles ≤ 32 per tile (each tile
 *   has at least one pixel, ≤ 2^20 tiles).
 */
class StageWorkTest {
    private fun cMask(style: MaskStyle): Long = when (style) {
        MaskStyle.SOLID -> 7L + 16L
        MaskStyle.GRADIENT_OVAL -> 7L + 16L + 625_667L
        MaskStyle.SMOOTH_FILL -> 7L + 16L + 57L + 40L + 520L
    }

    private fun cRead(style: MaskStyle): Long = when (style) {
        MaskStyle.SOLID -> 0L + 3L + 1L
        MaskStyle.GRADIENT_OVAL -> 6L + 3L + 5L + 1L
        MaskStyle.SMOOTH_FILL -> 30L + 3L + 1L + 1L
    }

    private val cShadow = 35L

    /** Σ read area and the shadow area (pixels of the tiles the write rectangles touch), from the masks' extents. */
    private fun areas(masks: List<MaskGeometry>, s: MaskSettings, w: Int, h: Int): Pair<Long, Long> {
        var read = 0L
        val tiles = HashSet<Int>()
        val tx = (w + 63) / 64
        for (g in masks) {
            val m = PreparedMask(PreparedMask.usable(g), s)
            val write = m.extent(1f + m.featherN, w, h)
            if (write.isEmpty) continue
            val r = when (s.style) {
                MaskStyle.SOLID -> write
                MaskStyle.GRADIENT_OVAL -> m.extent(1f + maxOf(m.featherN, m.ringN), w, h).union(write)
                MaskStyle.SMOOTH_FILL -> m.extent(1f + m.featherN + m.ringN, w, h).union(write)
            }
            read += r.area.toLong()
            for (y in write.top / 64..(write.bottom - 1) / 64) {
                for (x in write.left / 64..(write.right - 1) / 64) tiles += y * tx + x
            }
        }
        var shadow = 0L
        for (t in tiles) shadow += minOf(64, w - (t % tx) * 64).toLong() * minOf(64, h - (t / tx) * 64)
        return read to shadow
    }

    private fun work(masks: List<MaskGeometry>, s: MaskSettings, w: Int, h: Int): Long {
        val steps = StepRecorder()
        MaskEngine.apply(IntArrayPixels(w, h), masks, s, seams = StageSeams(observer = steps))
        return steps.unitsAt(4)
    }

    private fun check(label: String, masks: List<MaskGeometry>, style: MaskStyle, w: Int, h: Int): Long {
        val s = settings(style)
        val units = work(masks, s, w, h)
        val (read, shadow) = areas(masks, s, w, h)
        val bound = cMask(style) * masks.size + cRead(style) * read + cShadow * shadow
        assertTrue(units <= bound, "$label $style ${masks.size} masks: $units units > bound $bound")
        return units
    }

    private fun disjoint(n: Int, w: Int): List<MaskGeometry> = List(n) { i ->
        val perRow = w / 12
        MaskGeometry(6f + (i % perRow) * 12f, 6f + (i / perRow) * 12f, 2.5f, 3f, MaskShape.ELLIPSE)
    }

    private fun stacked(n: Int): List<MaskGeometry> = List(n) { i ->
        MaskGeometry(
            100f + (i % 3),
            100f + (i % 5),
            30f,
            36f,
            if (i % 2 == 0) MaskShape.ELLIPSE else MaskShape.RECTANGLE,
        )
    }

    @Test
    fun manyDisjointMasksStayWithinTheLinearBound() {
        val w = 2_400
        val h = 2_400
        for (n in listOf(10_000, 20_000, 40_000)) check("disjoint", disjoint(n, w), MaskStyle.SOLID, w, h)
        for (style in listOf(MaskStyle.GRADIENT_OVAL, MaskStyle.SMOOTH_FILL)) {
            for (n in listOf(500, 1_000, 2_000)) check("disjoint", disjoint(n, w), style, w, h)
        }
    }

    @Test
    fun manyStackedMasksOverOneRegionStayWithinTheLinearBoundAndGrowLinearly() {
        val w = 400
        val h = 300
        for (style in MaskStyle.entries) {
            val sizes = if (style == MaskStyle.SOLID) listOf(1_000, 2_000, 4_000) else listOf(250, 500, 1_000)
            val units = sizes.map { check("stacked", stacked(it), style, w, h) }
            // Four times the masks: at most about four times the work (a pair step would give sixteen times).
            assertTrue(units[2] <= 4 * units[0] + 100_000, "$style: ${units[0]} -> ${units[2]} units for ×4 masks")
        }
    }
}
