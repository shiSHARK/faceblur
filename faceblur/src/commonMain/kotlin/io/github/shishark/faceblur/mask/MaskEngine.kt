// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.isFatal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Units of work of the mask stage (ADR 0013 decision 3): one unit per loop step of any phase (coverage-map write or
 * lookup, pixel of a row read or tile load, ring or known-pixel sample, colour-table step, SMOOTH_FILL cell, grid
 * point, upsampled or composited pixel, copied pixel). Cancellation is read at least once every 65,536 units of
 * steps 1-2 (no single addition is larger than [MAX_BATCH]); the copy of step 3 is counted without reading it.
 */
internal class StageWork private constructor(
    private val checkCancelled: () -> Unit,
    private val observer: StageObserver?,
) {
    var units: Long = 0L
        private set
    private var nextRead = READ_INTERVAL

    /** Adds [n] (at most [MAX_BATCH]) units of steps 1-2 and reads cancellation when it is due. */
    fun add(n: Int) {
        units += n
        if (units >= nextRead) read()
    }

    /** Reads cancellation now (between masks and at the end of step 2). */
    fun read() {
        observer?.cancellationRead(units)
        checkCancelled()
        nextRead = units + READ_INTERVAL
    }

    /** Adds [n] units of the copy (step 3) without reading cancellation: the copy is never interrupted. */
    fun count(n: Int) {
        units += n
    }

    /**
     * The constructor is private and the factory synthetic on the JVM and Android ([HiddenFromJava]): it takes the
     * [StageObserver] test seam.
     */
    companion object {
        @HiddenFromJava
        operator fun invoke(checkCancelled: () -> Unit, observer: StageObserver?): StageWork =
            StageWork(checkCancelled, observer)

        /** The largest single addition (one full tile load). */
        const val MAX_BATCH: Int = MaskStageLimits.TILE * MaskStageLimits.TILE

        /** Units between two reads, so that a read happens within every 65,536 units. */
        const val READ_INTERVAL: Long = 65_536L - MAX_BATCH
    }
}

/** Test seam: notified at the step boundaries of the mask stage (null in production). */
internal interface StageObserver {
    /** Step [step] starts (1 check and reserve, 2 compute, 3 copy; 4 = the stage finished); [work] units so far. */
    fun step(step: Int, work: Long) {}

    /** The working area was reserved and touched (step 1). */
    fun reserved(area: WorkingArea) {}

    /** Cancellation is read after [work] units. */
    fun cancellationRead(work: Long) {}
}

/**
 * Test seams of the mask stage (internal): the allocator, the memory probe, an observer and a lowered area limit.
 * Java code can neither make one nor read its probe: the constructor is private, the factory and [probe] are synthetic
 * on the JVM and Android ([HiddenFromJava]; the JVM boundary test checks it).
 */
internal class StageSeams private constructor(
    val allocator: StageAllocator,
    @get:HiddenFromJava val probe: MemoryProbe?,
    val observer: StageObserver?,
    val areaFactor: Long,
) {
    companion object {
        /** The production seams by default: the array allocator, the registry's probe, no observer, the real limit. */
        @HiddenFromJava
        operator fun invoke(
            allocator: StageAllocator = ArrayAllocator,
            probe: MemoryProbe? = FaceBlurRuntime.memoryProbe(),
            observer: StageObserver? = null,
            areaFactor: Long = MaskStageLimits.MAX_MASK_AREA_FACTOR,
        ): StageSeams = StageSeams(allocator, probe, observer, areaFactor)
    }
}

/**
 * The bounded mask stage (ADR 0013 decision 3): steps 1 check and reserve (no image access), 2 compute (reads the
 * caller's image, never writes it, allocates no array, cancellable), 3 copy (the only step that writes).
 *
 * Every check, every allocation and all fill computation happen before the first write, so a failure in steps 1-2
 * leaves the caller's image untouched. Fills never sample a pixel inside any mask (coverage map); the masks are
 * composited in plan order into a shadow of 64 × 64 tiles loaded from the original image; step 3 writes exactly the
 * pixels inside some write rectangle, in runs of at most 64 pixels, and reads each run back.
 */
internal object MaskEngine {
    /** Runs the stage; synthetic on the JVM and Android ([HiddenFromJava]), as it takes the [StageSeams]. */
    @HiddenFromJava
    fun apply(
        image: MutablePixelImage,
        masks: List<MaskGeometry>,
        config: MaskSettings,
        checkCancelled: () -> Unit = {},
        seams: StageSeams = StageSeams(),
    ) {
        MaskStage(image, masks, config, StageWork(checkCancelled, seams.observer), seams).run()
    }

    /**
     * The exact size in bytes of the working area that [apply] would reserve for [masks] on a [width] × [height]
     * image with [config] (step 1 only: no image access; it allocates only the two tile tables, which count the
     * touched tiles); 0 when no mask reaches the image. The same sizing as the call itself; used by the sizing tests.
     */
    fun workingAreaBytes(width: Int, height: Int, masks: List<MaskGeometry>, config: MaskSettings): Long =
        MaskStage(SizeOnlyImage(width, height), masks, config, StageWork({}, null), StageSeams(probe = null))
            .plannedBytes()

    /** An image of a given size whose pixels are never accessed (sizing only). */
    private class SizeOnlyImage(override val width: Int, override val height: Int) : MutablePixelImage {
        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray): Unit =
            throw CoreFailure.InvalidMask("sizing reads no pixel")

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray): Unit =
            throw CoreFailure.InvalidMask("sizing writes no pixel")
    }
}

private class MaskStage private constructor(
    private val image: MutablePixelImage,
    private val masks: List<MaskGeometry>,
    private val config: MaskSettings,
    private val work: StageWork,
    private val seams: StageSeams,
) {
    private val iw = image.width
    private val ih = image.height
    private val tilesX = (iw + TILE - 1) / TILE
    private val tilesY = (ih + TILE - 1) / TILE
    private val fullColumns = iw / TILE
    private val fullRows = ih / TILE
    private val lastWidth = if (iw % TILE == 0) TILE else iw % TILE
    private val lastHeight = if (ih % TILE == 0) TILE else ih % TILE
    private lateinit var area: WorkingArea
    private var coverageUsed = 0
    private var touchedCount = 0
    private var used0 = 0
    private var used1 = 0
    private var used2 = 0
    private var used3 = 0

    /** Step 1's checks and sizing only: the bytes [run] would reserve (0 when no mask reaches the image). */
    fun plannedBytes(): Long {
        if (iw < 0 || ih < 0 || iw.toLong() * ih > Int.MAX_VALUE) throw CoreFailure.ImageTooLarge(iw, ih)
        for (i in masks.indices) PreparedMask.validate(masks[i])
        if (iw == 0 || ih == 0) return 0L
        val sizing = measure(masks.size) ?: return 0L
        return layout(masks.size, sizing, reserveTileTables(tilesX * tilesY, seams.allocator)).bytes
    }

    fun run() {
        seams.observer?.step(1, work.units)
        if (iw < 0 || ih < 0 || iw.toLong() * ih > Int.MAX_VALUE) throw CoreFailure.ImageTooLarge(iw, ih)
        val n = masks.size
        // Fail closed for every mask (also on an empty image) before anything else.
        for (i in 0 until n) {
            work.add(1)
            PreparedMask.validate(masks[i])
        }
        if (n > MaskStageLimits.MAX_MASKS_PER_IMAGE) {
            throw CoreFailure.InvalidMask("more than ${MaskStageLimits.MAX_MASKS_PER_IMAGE} masks")
        }
        if (iw == 0 || ih == 0) return
        val sizing = measure(n) ?: return
        // The colour tables are created on first use: before the reservation, never in step 2.
        if (Srgb.toLinear.isEmpty()) throw CoreFailure.InvalidMask("no colour table")
        // First the two tile tables (sized by the image alone), which count the tiles the plan touches; then the
        // rest of the working area, sized exactly for those tiles. Each part passes the memory check before it is
        // allocated. The tables' bytes stay in the count until the rest is reserved, so the second check compares
        // the whole working area (and counts the tables a second time once they show as used: a few bytes per tile).
        val tables = AreaLayout.tableBytes(tilesX.toLong() * tilesY)
        ReservedBytes.admit(tables, seams.probe)
        try {
            val tileTables = reserveTileTables(tilesX * tilesY, seams.allocator)
            val layout = layout(n, sizing, tileTables)
            val rest = layout.bytes - tables
            ReservedBytes.admit(rest, seams.probe, held = tables)
            try {
                area = reserveWorkingArea(layout, tileTables, seams.allocator)
            } finally {
                ReservedBytes.release(rest)
            }
        } finally {
            ReservedBytes.release(tables)
        }
        seams.observer?.reserved(area)
        storeMasks(n)
        seams.observer?.step(2, work.units)
        compute(n)
        // The last cancellation read: after it the copy runs to the end.
        work.read()
        seams.observer?.step(3, work.units)
        copy()
        seams.observer?.step(4, work.units)
    }

    // ---- Step 1: limits and the size of the working area --------------------------------------------------------

    /** The fill scratch sizes of the plan (step 1). */
    private class Sizing(val maxBins: Int, val maxBase: Long, val maxLevels: Long)

    /**
     * The area limit and the fill scratch sizes of the plan; null when no mask reaches the image. No allocation: the
     * area limit is checked before anything is reserved.
     */
    private fun measure(n: Int): Sizing? {
        var active = 0
        var areaSum = 0L
        var maxBins = 0
        var maxBase = 0L
        var maxLevels = 0L
        for (i in 0 until n) {
            work.add(1)
            val g = masks[i]
            val rx = max(g.rx, PreparedMask.MIN_RADIUS)
            val ry = max(g.ry, PreparedMask.MIN_RADIUS)
            val minR = min(rx, ry)
            val featherN = featherOf(config, minR)
            val ringN = ringOf(config, minR)
            val wq = 1f + featherN
            val wl = extentLow(g.cx - wq * rx - 0.5f, iw)
            val wt = extentLow(g.cy - wq * ry - 0.5f, ih)
            val wr = extentHigh(g.cx + wq * rx - 0.5f, iw)
            val wb = extentHigh(g.cy + wq * ry - 0.5f, ih)
            if (wr <= wl || wb <= wt) continue
            active++
            val rq = readExtent(featherN, ringN)
            val rl = min(wl, extentLow(g.cx - rq * rx - 0.5f, iw))
            val rt = min(wt, extentLow(g.cy - rq * ry - 0.5f, ih))
            val rr = max(wr, extentHigh(g.cx + rq * rx - 0.5f, iw))
            val rb = max(wb, extentHigh(g.cy + rq * ry - 0.5f, ih))
            areaSum += (rr - rl).toLong() * (rb - rt)
            when (config.style) {
                MaskStyle.SOLID -> Unit

                MaskStyle.GRADIENT_OVAL -> maxBins = max(maxBins, GradientOvalFill.binCount(rx, ry))

                MaskStyle.SMOOTH_FILL -> {
                    val k = SmoothFill.spacing(rx, ry, rr - rl, rb - rt, work)
                    val w0 = (rr - rl + k - 1) / k
                    val h0 = (rb - rt + k - 1) / k
                    maxBase = max(maxBase, w0.toLong() * h0)
                    maxLevels = max(maxLevels, SmoothFill.pyramidCells(w0, h0, work))
                }
            }
        }
        val limit = seams.areaFactor * max(iw.toLong() * ih, MaskStageLimits.MIN_AREA_BASE)
        if (areaSum > limit) throw CoreFailure.MaskWorkLimit(areaSum, limit)
        if (active == 0) return null
        return Sizing(maxBins, maxBase, maxLevels)
    }

    /**
     * The working area of the plan (ADR 0013 decision 6): shadow slots for exactly the union of the tiles that the
     * write rectangles touch, per size class, and coverage slots for exactly the union of the tiles that the opaque
     * rectangles touch. Counted with the reserved tile [tables] as marks, which are set back to 0 afterwards; the work
     * is linear in the summed tile counts of the rectangles, which the area limit bounds.
     */
    private fun layout(n: Int, sizing: Sizing, tables: TileTables): AreaLayout {
        val counts = TileCounts()
        walkTiles(n, tables, counts)
        walkTiles(n, tables, null)
        return AreaLayout(
            config.style,
            n,
            tilesX,
            tilesY,
            lastWidth,
            lastHeight,
            counts.full,
            counts.right,
            counts.bottom,
            counts.corner,
            counts.coverage,
            sizing.maxBins,
            sizing.maxBase.toInt(),
            sizing.maxLevels.toInt(),
        )
    }

    /** Distinct tiles per shadow size class and distinct coverage tiles (step 1). */
    private class TileCounts {
        var full = 0
        var right = 0
        var bottom = 0
        var corner = 0
        var coverage = 0
    }

    /**
     * Walks the tiles of every mask's write rectangle and opaque rectangle. With [counts]: marks each tile in its
     * table the first time and counts it; without: sets every visited entry back to 0. One unit of work per visit.
     */
    private fun walkTiles(n: Int, tables: TileTables, counts: TileCounts?) {
        for (i in 0 until n) {
            work.add(1)
            val g = masks[i]
            val rx = max(g.rx, PreparedMask.MIN_RADIUS)
            val ry = max(g.ry, PreparedMask.MIN_RADIUS)
            val wq = 1f + featherOf(config, min(rx, ry))
            val wl = extentLow(g.cx - wq * rx - 0.5f, iw)
            val wt = extentLow(g.cy - wq * ry - 0.5f, ih)
            val wr = extentHigh(g.cx + wq * rx - 0.5f, iw)
            val wb = extentHigh(g.cy + wq * ry - 0.5f, ih)
            if (wr <= wl || wb <= wt) continue
            for (ty in wt / TILE..(wb - 1) / TILE) {
                for (tx in wl / TILE..(wr - 1) / TILE) {
                    work.add(1)
                    val t = ty * tilesX + tx
                    if (counts == null) {
                        tables.shadow[t] = 0
                    } else if (tables.shadow[t] == 0) {
                        tables.shadow[t] = 1
                        val partX = tx == fullColumns
                        val partY = ty == fullRows
                        when {
                            !partX && !partY -> counts.full++
                            partX && !partY -> counts.right++
                            !partX -> counts.bottom++
                            else -> counts.corner++
                        }
                    }
                }
            }
            val ol = extentLow(g.cx - rx - 0.5f, iw)
            val ot = extentLow(g.cy - ry - 0.5f, ih)
            val or = extentHigh(g.cx + rx - 0.5f, iw)
            val ob = extentHigh(g.cy + ry - 0.5f, ih)
            if (or <= ol || ob <= ot) continue
            for (ty in ot / TILE..(ob - 1) / TILE) {
                for (tx in ol / TILE..(or - 1) / TILE) {
                    work.add(1)
                    val t = ty * tilesX + tx
                    if (counts == null) {
                        tables.coverage[t] = 0
                    } else if (tables.coverage[t] == 0) {
                        tables.coverage[t] = 1
                        counts.coverage++
                    }
                }
            }
        }
    }

    /** The normalised radius of the read rectangle beyond the write rectangle, per style. */
    private fun readExtent(featherN: Float, ringN: Float): Float = when (config.style) {
        MaskStyle.SOLID -> 1f + featherN
        MaskStyle.GRADIENT_OVAL -> 1f + max(featherN, ringN)
        MaskStyle.SMOOTH_FILL -> 1f + featherN + ringN
    }

    /** Stores every mask's values and rectangles in the reserved per-mask arrays (still step 1, no image access). */
    private fun storeMasks(n: Int) {
        val f = area.maskFloats
        val r = area.maskInts
        val flags = area.maskFlags
        for (i in 0 until n) {
            work.add(1)
            val g = masks[i]
            val rx = max(g.rx, PreparedMask.MIN_RADIUS)
            val ry = max(g.ry, PreparedMask.MIN_RADIUS)
            val minR = min(rx, ry)
            val featherN = featherOf(config, minR)
            val ringN = ringOf(config, minR)
            val o = i * AreaLayout.MASK_FLOATS
            f[o] = g.cx
            f[o + 1] = g.cy
            f[o + 2] = rx
            f[o + 3] = ry
            f[o + 4] = featherN
            f[o + 5] = ringN
            val q = i * AreaLayout.MASK_INTS
            val wq = 1f + featherN
            val wl = extentLow(g.cx - wq * rx - 0.5f, iw)
            val wt = extentLow(g.cy - wq * ry - 0.5f, ih)
            val wr = extentHigh(g.cx + wq * rx - 0.5f, iw)
            val wb = extentHigh(g.cy + wq * ry - 0.5f, ih)
            val rq = readExtent(featherN, ringN)
            r[q] = wl
            r[q + 1] = wt
            r[q + 2] = wr
            r[q + 3] = wb
            r[q + 4] = min(wl, extentLow(g.cx - rq * rx - 0.5f, iw))
            r[q + 5] = min(wt, extentLow(g.cy - rq * ry - 0.5f, ih))
            r[q + 6] = max(wr, extentHigh(g.cx + rq * rx - 0.5f, iw))
            r[q + 7] = max(wb, extentHigh(g.cy + rq * ry - 0.5f, ih))
            r[q + 8] = extentLow(g.cx - rx - 0.5f, iw)
            r[q + 9] = extentLow(g.cy - ry - 0.5f, ih)
            r[q + 10] = extentHigh(g.cx + rx - 0.5f, iw)
            r[q + 11] = extentHigh(g.cy + ry - 0.5f, ih)
            var flag = if (g.shape == MaskShape.ELLIPSE) FillContext.FLAG_ELLIPSE else 0
            if (wr > wl && wb > wt) flag = flag or FillContext.FLAG_ACTIVE
            flags[i] = flag.toByte()
        }
    }

    // ---- Step 2: coverage map, fills, shadow -----------------------------------------------------------------------

    private fun compute(n: Int) {
        val context = FillContext(image, area, work, config, tilesX)
        val oval = GradientOvalFill(context)
        val smooth = SmoothFill(context)
        buildCoverage(n, context)
        for (i in 0 until n) {
            work.read()
            work.add(1)
            if (area.maskFlags[i].toInt() and FillContext.FLAG_ACTIVE == 0) continue
            when (config.style) {
                MaskStyle.SOLID -> Unit
                MaskStyle.GRADIENT_OVAL -> oval.prepare(i)
                MaskStyle.SMOOTH_FILL -> smooth.prepare(i)
            }
            composite(i, context, oval, smooth)
        }
    }

    /** One bit per pixel whose centre lies inside the opaque mask of any mask (the same float test as the fills). */
    private fun buildCoverage(n: Int, c: FillContext) {
        for (i in 0 until n) {
            work.add(1)
            val ol = c.rect(i, FillContext.OPAQUE)
            val ot = c.rect(i, FillContext.OPAQUE + 1)
            val or = c.rect(i, FillContext.OPAQUE + 2)
            val ob = c.rect(i, FillContext.OPAQUE + 3)
            if (or <= ol || ob <= ot) continue
            val cx = c.cx(i)
            val cy = c.cy(i)
            val rx = c.rx(i)
            val ry = c.ry(i)
            val ellipse = c.ellipse(i)
            for (y in ot until ob) {
                val dy = (y + 0.5f - cy) / ry
                for (x in ol until or) {
                    work.add(1)
                    val dx = (x + 0.5f - cx) / rx
                    val r = if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                    if (r <= 1f) setCovered(x, y)
                }
            }
        }
    }

    private fun setCovered(x: Int, y: Int) {
        val t = (y ushr FillContext.TILE_SHIFT) * tilesX + (x ushr FillContext.TILE_SHIFT)
        var slot = area.coverageTable[t]
        if (slot == 0) {
            if (coverageUsed >= area.layout.coverageSlots) throw CoreFailure.InvalidMask("coverage tiles exhausted")
            slot = ++coverageUsed
            area.coverageTable[t] = slot
        }
        val i = (slot - 1) * TILE + (y and FillContext.TILE_MASK)
        area.coverage[i] = area.coverage[i] or (1L shl (x and FillContext.TILE_MASK))
    }

    /**
     * Composites mask [i] in its write rectangle, tile by tile, into the shadow: inside the mask the fill with alpha
     * 255; in the outward feather band a smoothstep blend of colour and alpha towards the fill; beyond it nothing.
     */
    private fun composite(i: Int, c: FillContext, oval: GradientOvalFill, smooth: SmoothFill) {
        val wl = c.rect(i, FillContext.WRITE)
        val wt = c.rect(i, FillContext.WRITE + 1)
        val wr = c.rect(i, FillContext.WRITE + 2)
        val wb = c.rect(i, FillContext.WRITE + 3)
        val cx = c.cx(i)
        val cy = c.cy(i)
        val rx = c.rx(i)
        val ry = c.ry(i)
        val featherN = c.feather(i)
        val ellipse = c.ellipse(i)
        val outer = 1f + featherN
        val solid = config.color or OPAQUE
        for (ty in wt / TILE..(wb - 1) / TILE) {
            for (tx in wl / TILE..(wr - 1) / TILE) {
                val slot = shadowSlot(tx, ty)
                val tile = area.shadowTiles[slot] ?: throw CoreFailure.InvalidMask("no shadow tile")
                val x0 = tx * TILE
                val y0 = ty * TILE
                val tw = tileWidth(tx)
                val xa = max(wl, x0)
                val xb = min(wr, x0 + tw)
                val ya = max(wt, y0)
                val yb = min(wb, y0 + tileHeight(ty))
                markRows(slot, xa - x0, xb - x0, ya - y0, yb - y0)
                if (config.style == MaskStyle.GRADIENT_OVAL) oval.prepareTile(xa, ya, xb, yb)
                for (y in ya until yb) {
                    val dy = (y + 0.5f - cy) / ry
                    val row = (y - y0) * tw - x0
                    for (x in xa until xb) {
                        work.add(1)
                        val dx = (x + 0.5f - cx) / rx
                        val r = if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                        if (r >= outer) continue
                        val e = when (config.style) {
                            MaskStyle.SOLID -> solid
                            MaskStyle.GRADIENT_OVAL -> oval.value(x, y)
                            MaskStyle.SMOOTH_FILL -> smooth.value(x, y)
                        }
                        val p = row + x
                        if (r <= 1f) {
                            tile[p] = OPAQUE or (e and 0xFFFFFF)
                        } else {
                            val a = (alpha(r, featherN) * 256f + 0.5f).toInt()
                            if (a <= 0) continue
                            val o = tile[p]
                            val oA = o ushr 24
                            val na = oA + (((255 - oA) * a + 128) shr 8)
                            tile[p] = (na shl 24) or blendColour(o, oA, e, a)
                        }
                    }
                }
            }
        }
    }

    /** The shadow slot of tile ([tx], [ty]); on its first touch the tile is loaded from the original image. */
    private fun shadowSlot(tx: Int, ty: Int): Int {
        val t = ty * tilesX + tx
        val known = area.shadowTable[t]
        if (known != 0) return known - 1
        val l = area.layout
        val partX = tx == fullColumns
        val partY = ty == fullRows
        val slot = when {
            !partX && !partY -> if (used0 < l.fullSlots) area.classStart0 + used0++ else -1
            partX && !partY -> if (used1 < l.rightSlots) area.classStart1 + used1++ else -1
            !partX -> if (used2 < l.bottomSlots) area.classStart2 + used2++ else -1
            else -> if (used3 < l.cornerSlots) area.classStart3 + used3++ else -1
        }
        if (slot < 0) throw CoreFailure.InvalidMask("shadow tiles exhausted")
        area.shadowTable[t] = slot + 1
        area.touched[touchedCount++] = t
        val tile = area.shadowTiles[slot] ?: throw CoreFailure.InvalidMask("no shadow tile")
        val tw = tileWidth(tx)
        val th = tileHeight(ty)
        image.readPixels(tx * TILE, ty * TILE, tw, th, tile)
        work.add(tw * th)
        return slot
    }

    /** Marks the pixels [xa]..[xb) of rows [ya]..[yb) of a shadow tile as written (tile coordinates). */
    private fun markRows(slot: Int, xa: Int, xb: Int, ya: Int, yb: Int) {
        val len = xb - xa
        val bits = if (len >= TILE) -1L else ((1L shl len) - 1L) shl xa
        for (y in ya until yb) {
            work.add(1)
            val i = slot * TILE + y
            area.marks[i] = area.marks[i] or bits
        }
    }

    private fun tileWidth(tx: Int) = if (tx == fullColumns) lastWidth else TILE

    private fun tileHeight(ty: Int) = if (ty == fullRows) lastHeight else TILE

    // ---- Step 3: copy ----------------------------------------------------------------------------------------------

    /**
     * Writes exactly the marked pixels of every touched tile, in row order of the tiles, as row runs of 1 to 64
     * pixels, each staged in the reserved buffer of its length, written, read back into the second buffer of that
     * length and compared (every pixel written with alpha 255 must read back unchanged). Creates no object except the
     * exception it may throw; reads no cancellation.
     */
    private fun copy() {
        sortTouched()
        for (k in 0 until touchedCount) {
            val t = area.touched[k]
            val slot = area.shadowTable[t] - 1
            val tile = area.shadowTiles[slot] ?: throw CoreFailure.InvalidMask("no shadow tile")
            val tx = t % tilesX
            val ty = t / tilesX
            val tw = tileWidth(tx)
            val th = tileHeight(ty)
            for (row in 0 until th) {
                work.count(1)
                var bits = area.marks[slot * TILE + row]
                while (bits != 0L) {
                    val a = bits.countTrailingZeroBits()
                    val rest = bits.inv() and (-1L shl a)
                    val b = if (rest == 0L) TILE else rest.countTrailingZeroBits()
                    writeRun(tile, row * tw + a, tx * TILE + a, ty * TILE + row, b - a)
                    bits = if (b >= TILE) 0L else bits and (-1L shl b)
                }
            }
        }
    }

    private fun writeRun(tile: IntArray, from: Int, x: Int, y: Int, len: Int) {
        val stage = area.runRead[len] ?: throw CoreFailure.InvalidMask("no run buffer")
        val back = area.runBack[len] ?: throw CoreFailure.InvalidMask("no run buffer")
        tile.copyInto(stage, 0, from, from + len)
        try {
            image.writePixels(x, y, len, 1, stage)
            image.readPixels(x, y, len, 1, back)
        } catch (e: Throwable) {
            // The image itself failed during the copy: it may be partly written and must be discarded.
            if (isFatal(e)) throw e
            throw CoreFailure.WriteInterrupted(e)
        }
        for (j in 0 until len) {
            val p = stage[j]
            if (p ushr 24 == 0xFF && back[j] != p) throw CoreFailure.WriteNotApplied()
        }
        work.count(len)
    }

    /** Sorts the touched tile indexes ascending (row order) in place: heapsort, no allocation, counted. */
    private fun sortTouched() {
        val a = area.touched
        val n = touchedCount
        for (start in n / 2 - 1 downTo 0) siftDown(a, start, n)
        for (end in n - 1 downTo 1) {
            val tmp = a[0]
            a[0] = a[end]
            a[end] = tmp
            siftDown(a, 0, end)
        }
    }

    private fun siftDown(a: IntArray, start: Int, end: Int) {
        var root = start
        while (true) {
            work.count(1)
            var child = 2 * root + 1
            if (child >= end) return
            if (child + 1 < end && a[child + 1] > a[child]) child++
            if (a[root] >= a[child]) return
            val tmp = a[root]
            a[root] = a[child]
            a[child] = tmp
            root = child
        }
    }

    /**
     * Not private: [MaskEngine] calls the factory. The constructor is private and the factory synthetic on the JVM and
     * Android ([HiddenFromJava]), as it takes the [StageSeams].
     */
    companion object {
        @HiddenFromJava
        operator fun invoke(
            image: MutablePixelImage,
            masks: List<MaskGeometry>,
            config: MaskSettings,
            work: StageWork,
            seams: StageSeams,
        ): MaskStage = MaskStage(image, masks, config, work, seams)

        const val TILE = MaskStageLimits.TILE
        const val OPAQUE: Int = 0xFF shl 24

        /** 1 inside, smoothstep fall-off over the feather band, 0 beyond (as [PreparedMask.alpha]). */
        fun alpha(r: Float, featherN: Float): Float {
            if (r <= 1f) return 1f
            val t = (r - 1f) / featherN
            if (t >= 1f) return 0f
            return 1f - t * t * (3f - 2f * t)
        }

        /**
         * Colour of a soft-edge pixel: the opaque fill [e] with weight [a] / 256 over the photo pixel [o] (alpha
         * [oA]). Opaque photo pixels use the benchmark's straight blend (bit-identical results). Translucent photo
         * pixels are composited "source over" in premultiplied form, so a colour that the photo hid under a low alpha
         * is not revealed when the soft edge raises the alpha: at alpha 0 the result is exactly the fill colour.
         */
        fun blendColour(o: Int, oA: Int, e: Int, a: Int): Int {
            val oR = (o shr 16) and 0xFF
            val oG = (o shr 8) and 0xFF
            val oB = o and 0xFF
            val eR = (e shr 16) and 0xFF
            val eG = (e shr 8) and 0xFF
            val eB = e and 0xFF
            if (oA == 0xFF) {
                val nr = oR + (((eR - oR) * a + 128) shr 8)
                val ng = oG + (((eG - oG) * a + 128) shr 8)
                val nb = oB + (((eB - oB) * a + 128) shr 8)
                return (nr shl 16) or (ng shl 8) or nb
            }
            val wf = 255 * a
            val wp = oA * (256 - a)
            val den = wf + wp
            val nr = (eR * wf + oR * wp + den / 2) / den
            val ng = (eG * wf + oG * wp + den / 2) / den
            val nb = (eB * wf + oB * wp + den / 2) / den
            return (nr shl 16) or (ng shl 8) or nb
        }
    }
}
