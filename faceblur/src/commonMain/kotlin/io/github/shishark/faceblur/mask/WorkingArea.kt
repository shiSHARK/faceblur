// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.MaskStyle

/**
 * The size of every buffer of one call's working area (ADR 0013 decision 6), computed in step 1. [bytes] is the
 * exact sum of what [reserveTileTables] and [WorkingArea.allocateAndTouch] request, in the library's model of an
 * array: each array counts its elements plus [ARRAY_HEADER] bytes, each table entry [REFERENCE] bytes (a modelled
 * size; a virtual machine's object alignment and reference compression move the heap figure by a few kilobytes).
 *
 * Shadow slots: per size class of tile (full 64 × 64, the narrower last column, the shorter last row, the corner),
 * exactly the tiles that the union of the masks' write rectangles touches. Coverage slots: exactly the tiles that the
 * union of the masks' opaque rectangles touches (coverage bits are only ever set there). Both are counted in step 1
 * with the tile tables, after the area limit.
 */
internal class AreaLayout(
    val style: MaskStyle,
    val masks: Int,
    val tilesX: Int,
    val tilesY: Int,
    /** Width of the last tile column (64 when the image width is a multiple of 64). */
    val lastWidth: Int,
    /** Height of the last tile row (64 when the image height is a multiple of 64). */
    val lastHeight: Int,
    val fullSlots: Int,
    val rightSlots: Int,
    val bottomSlots: Int,
    val cornerSlots: Int,
    val coverageSlots: Int,
    /** GRADIENT_OVAL: the largest bin count of the plan (0 for other styles). */
    val maxBins: Int,
    /** SMOOTH_FILL: the largest base grid and the largest sum over all pyramid levels (0 for other styles). */
    val maxBaseCells: Int,
    val maxLevelCells: Int,
) {
    val shadowSlots: Int get() = fullSlots + rightSlots + bottomSlots + cornerSlots

    /** The exact number of bytes of the working area, the two tile tables included ([tableBytes]). */
    val bytes: Long =
        sizeOf(
            masks.toLong(),
            tilesX.toLong() * tilesY,
            slotCounts(),
            lastWidth.toLong(),
            lastHeight.toLong(),
            coverageSlots.toLong(),
            scratchBytes(style, maxBins.toLong(), maxBaseCells.toLong(), maxLevelCells.toLong()),
        )

    private fun slotCounts(): SlotCounts = SlotCounts(
        fullSlots.toLong(),
        rightSlots.toLong(),
        bottomSlots.toLong(),
        cornerSlots.toLong(),
    )

    /** Shadow slots per size class. */
    internal class SlotCounts(val full: Long, val right: Long, val bottom: Long, val corner: Long)

    companion object {
        /** Bytes counted per array besides its elements (object header and length). */
        const val ARRAY_HEADER: Long = 16L

        /** Bytes counted per entry of a table of references. */
        const val REFERENCE: Long = 8L

        /** Floats stored per mask: centre, radii, feather and ring width in normalised units. */
        const val MASK_FLOATS: Int = 6

        /** Ints stored per mask: write, read and opaque rectangle (left, top, right, bottom each). */
        const val MASK_INTS: Int = 12

        /** GRADIENT_OVAL: luma histogram levels per bin, radial levels of the colour table, grid buffer side. */
        const val HIST: Int = 64
        const val LEVELS: Int = 32
        const val GRID_SIDE: Int = 66

        /** SMOOTH_FILL: the most pyramid levels a grid within the image limits can have. */
        const val MAX_PYRAMID_LEVELS: Int = 40

        /** Run buffers: one array per length 1..[MaskStageLimits.MAX_RUN], twice (read/stage and read-back). */
        val RUN_BUFFER_BYTES: Long = 2L * runSetBytes()

        private fun runSetBytes(): Long {
            var b = ARRAY_HEADER + REFERENCE * (MaskStageLimits.MAX_RUN + 1)
            for (len in 1..MaskStageLimits.MAX_RUN) b += ARRAY_HEADER + 4L * len
            return b
        }

        private fun array(elements: Long, elementBytes: Long): Long =
            checkedAdd(checkedMul(elements, elementBytes), ARRAY_HEADER)

        /** The bytes of the two tile tables (shadow and coverage map) for an image of [tiles] tiles. */
        fun tableBytes(tiles: Long): Long = checkedMul(2L, array(tiles, 4L))

        /**
         * The size function of the working area, in 64-bit arithmetic with every product and sum checked: an
         * overflow throws `NotEnoughMemory` (nothing is allocated). Within the library's limits no overflow occurs.
         */
        fun sizeOf(
            masks: Long,
            tiles: Long,
            slots: SlotCounts,
            lastWidth: Long,
            lastHeight: Long,
            coverageSlots: Long,
            scratch: Long,
        ): Long {
            val tile = MaskStageLimits.TILE.toLong()
            var b = array(checkedMul(masks, MASK_FLOATS.toLong()), 4L)
            b = checkedAdd(b, array(checkedMul(masks, MASK_INTS.toLong()), 4L))
            b = checkedAdd(b, array(masks, 1L))
            // Tile tables of the shadow and of the coverage map.
            b = checkedAdd(b, tableBytes(tiles))
            val shadow = checkedAdd(checkedAdd(slots.full, slots.right), checkedAdd(slots.bottom, slots.corner))
            // The table of shadow tile arrays, the list of touched tiles and the write marks.
            b = checkedAdd(b, array(shadow, REFERENCE))
            b = checkedAdd(b, array(shadow, 4L))
            b = checkedAdd(b, array(checkedMul(shadow, tile), 8L))
            b = checkedAdd(b, checkedMul(slots.full, array(tile * tile, 4L)))
            b = checkedAdd(b, checkedMul(slots.right, array(checkedMul(lastWidth, tile), 4L)))
            b = checkedAdd(b, checkedMul(slots.bottom, array(checkedMul(tile, lastHeight), 4L)))
            b = checkedAdd(b, checkedMul(slots.corner, array(checkedMul(lastWidth, lastHeight), 4L)))
            // The coverage map.
            b = checkedAdd(b, array(checkedMul(coverageSlots, tile), 8L))
            b = checkedAdd(b, RUN_BUFFER_BYTES)
            return checkedAdd(b, scratch)
        }

        /**
         * The fill scratch for the largest mask of the plan. Every scratch array exists for every style; the arrays of
         * the other styles have length 0 (they still count their header).
         */
        fun scratchBytes(style: MaskStyle, bins: Long, baseCells: Long, levelCells: Long): Long {
            val go = style == MaskStyle.GRADIENT_OVAL
            val sf = style == MaskStyle.SMOOTH_FILL
            val n = if (go) bins else 0L
            var b = array(checkedMul(n, HIST.toLong()), 4L)
            b = checkedAdd(b, array(n, 4L))
            b = checkedAdd(b, array(if (go) HIST.toLong() else 0L, 4L))
            // med, scale, sr, sg, sb, sw, smoothing copy, Poisson kernel.
            b = checkedAdd(b, checkedMul(8L, array(n, 4L)))
            b = checkedAdd(b, array(if (go) n / 2 + 1 else 0L, 4L))
            b = checkedAdd(b, checkedMul(3L, array(checkedMul(n, LEVELS.toLong()), 4L)))
            b = checkedAdd(b, array(if (go) (GRID_SIDE * GRID_SIDE).toLong() else 0L, 4L))
            val cells = if (sf) levelCells else 0L
            val base = if (sf) baseCells else 0L
            b = checkedAdd(b, checkedMul(4L, array(cells, 4L)))
            b = checkedAdd(b, array(base, 4L))
            b = checkedAdd(b, array(base, 1L))
            return checkedAdd(b, checkedMul(3L, array(if (sf) MAX_PYRAMID_LEVELS.toLong() else 0L, 4L)))
        }
    }
}

/**
 * The two tile tables of a call, one entry per image tile (ADR 0013 decision 6): reserved first, used in step 1 to
 * count the tiles the plan touches (and set back to 0), then the shadow and coverage tables of the [WorkingArea].
 */
internal class TileTables(val shadow: IntArray, val coverage: IntArray) {
    /** One element per 4,096 bytes set to 0 (the initial value): commits the memory, changes no state. */
    fun touch() {
        touchInts(shadow)
        touchInts(coverage)
    }
}

/**
 * The working area of one call: every buffer the mask stage uses from step 1 to the end of step 3, allocated and
 * touched (the [tables] by [reserveTileTables], the rest by [allocateAndTouch]) before any pixel of the caller's
 * image is read or written.
 */
internal class WorkingArea(val layout: AreaLayout, tables: TileTables) {
    lateinit var maskFloats: FloatArray
    lateinit var maskInts: IntArray

    /** Bit 0: ellipse; bit 1: the write rectangle is not empty. */
    lateinit var maskFlags: ByteArray
    val shadowTable: IntArray = tables.shadow
    val coverageTable: IntArray = tables.coverage
    lateinit var shadowTiles: Array<IntArray?>
    lateinit var touched: IntArray
    lateinit var marks: LongArray
    lateinit var coverage: LongArray
    lateinit var runRead: Array<IntArray?>
    lateinit var runBack: Array<IntArray?>

    // GRADIENT_OVAL scratch.
    lateinit var hist: IntArray
    lateinit var count: IntArray
    lateinit var deviation: IntArray
    lateinit var median: FloatArray
    lateinit var scale: FloatArray
    lateinit var sumR: FloatArray
    lateinit var sumG: FloatArray
    lateinit var sumB: FloatArray
    lateinit var sumW: FloatArray
    lateinit var smoothCopy: FloatArray
    lateinit var kernel: FloatArray
    lateinit var gauss: FloatArray
    lateinit var tableR: FloatArray
    lateinit var tableG: FloatArray
    lateinit var tableB: FloatArray
    lateinit var grid: IntArray

    // SMOOTH_FILL scratch.
    lateinit var levelR: FloatArray
    lateinit var levelG: FloatArray
    lateinit var levelB: FloatArray
    lateinit var levelW: FloatArray
    lateinit var unknown: IntArray
    lateinit var unknownFlags: ByteArray
    lateinit var levelWidth: IntArray
    lateinit var levelHeight: IntArray
    lateinit var levelOffset: IntArray

    /** First slot of each tile size class in [shadowTiles]: full, last column, last row, corner, end. */
    val classStart0: Int get() = 0
    val classStart1: Int get() = layout.fullSlots
    val classStart2: Int get() = layout.fullSlots + layout.rightSlots
    val classStart3: Int get() = layout.fullSlots + layout.rightSlots + layout.bottomSlots

    /**
     * Allocates every buffer except the tile tables through [a], then writes 0 (each array's initial value) once per
     * 4,096 bytes. Synthetic on the JVM and Android ([HiddenFromJava]): it takes the [StageAllocator] test seam.
     */
    @HiddenFromJava
    fun allocateAndTouch(a: StageAllocator) {
        val l = layout
        val tile = MaskStageLimits.TILE
        maskFloats = a.floats(l.masks * AreaLayout.MASK_FLOATS)
        maskInts = a.ints(l.masks * AreaLayout.MASK_INTS)
        maskFlags = a.bytes(l.masks)
        val slots = l.shadowSlots
        shadowTiles = a.intTable(slots)
        touched = a.ints(slots)
        marks = a.longs(slots * tile)
        var s = 0
        repeat(l.fullSlots) { shadowTiles[s++] = a.ints(tile * tile) }
        repeat(l.rightSlots) { shadowTiles[s++] = a.ints(l.lastWidth * tile) }
        repeat(l.bottomSlots) { shadowTiles[s++] = a.ints(tile * l.lastHeight) }
        repeat(l.cornerSlots) { shadowTiles[s++] = a.ints(l.lastWidth * l.lastHeight) }
        coverage = a.longs(l.coverageSlots * tile)
        runRead = runSet(a)
        runBack = runSet(a)
        val go = l.style == MaskStyle.GRADIENT_OVAL
        val bins = if (go) l.maxBins else 0
        hist = a.ints(bins * AreaLayout.HIST)
        count = a.ints(bins)
        deviation = a.ints(if (go) AreaLayout.HIST else 0)
        median = a.floats(bins)
        scale = a.floats(bins)
        sumR = a.floats(bins)
        sumG = a.floats(bins)
        sumB = a.floats(bins)
        sumW = a.floats(bins)
        smoothCopy = a.floats(bins)
        kernel = a.floats(bins)
        gauss = a.floats(if (go) bins / 2 + 1 else 0)
        tableR = a.floats(bins * AreaLayout.LEVELS)
        tableG = a.floats(bins * AreaLayout.LEVELS)
        tableB = a.floats(bins * AreaLayout.LEVELS)
        grid = a.ints(if (go) AreaLayout.GRID_SIDE * AreaLayout.GRID_SIDE else 0)
        val sf = l.style == MaskStyle.SMOOTH_FILL
        val cells = if (sf) l.maxLevelCells else 0
        val base = if (sf) l.maxBaseCells else 0
        levelR = a.floats(cells)
        levelG = a.floats(cells)
        levelB = a.floats(cells)
        levelW = a.floats(cells)
        unknown = a.ints(base)
        unknownFlags = a.bytes(base)
        val levels = if (sf) AreaLayout.MAX_PYRAMID_LEVELS else 0
        levelWidth = a.ints(levels)
        levelHeight = a.ints(levels)
        levelOffset = a.ints(levels)
        touchAll()
    }

    private fun runSet(a: StageAllocator): Array<IntArray?> {
        val set = a.intTable(MaskStageLimits.MAX_RUN + 1)
        for (len in 1..MaskStageLimits.MAX_RUN) set[len] = a.ints(len)
        return set
    }

    /**
     * Commits the memory of the reservation before the first write into the caller's image: one element per 4,096
     * bytes of every array, and one element of every smaller array, is set to 0 (its initial value), so the touch
     * changes no state. The tile tables were touched when they were reserved.
     */
    private fun touchAll() {
        touch(maskFloats)
        touch(maskInts)
        touch(maskFlags)
        touch(touched)
        touch(marks)
        for (t in shadowTiles) if (t != null) touch(t)
        touch(coverage)
        for (len in 1..MaskStageLimits.MAX_RUN) {
            runRead[len]?.let { touch(it) }
            runBack[len]?.let { touch(it) }
        }
        touch(hist)
        touch(count)
        touch(deviation)
        touch(median)
        touch(scale)
        touch(sumR)
        touch(sumG)
        touch(sumB)
        touch(sumW)
        touch(smoothCopy)
        touch(kernel)
        touch(gauss)
        touch(tableR)
        touch(tableG)
        touch(tableB)
        touch(grid)
        touch(levelR)
        touch(levelG)
        touch(levelB)
        touch(levelW)
        touch(unknown)
        touch(unknownFlags)
        touch(levelWidth)
        touch(levelHeight)
        touch(levelOffset)
    }

    private fun touch(a: IntArray) = touchInts(a)

    private fun touch(a: LongArray) {
        var i = 0
        while (i < a.size) {
            a[i] = 0L
            i += PAGE / 8
        }
    }

    private fun touch(a: FloatArray) {
        var i = 0
        while (i < a.size) {
            a[i] = 0f
            i += PAGE / 4
        }
    }

    private fun touch(a: ByteArray) {
        var i = 0
        while (i < a.size) {
            a[i] = 0
            i += PAGE
        }
    }
}

/** Bytes between two touched elements. */
private const val PAGE = 4_096

private fun touchInts(a: IntArray) {
    var i = 0
    while (i < a.size) {
        a[i] = 0
        i += PAGE / 4
    }
}
