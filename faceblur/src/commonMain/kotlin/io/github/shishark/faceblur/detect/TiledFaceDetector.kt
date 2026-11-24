// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.geometry.DetectedFace
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs [engine] on the whole image shrunk to the model input (the global pass), then on overlapping tiles of the
 * model's input size in source pixels (never scaled), maps every box back to full-image coordinates, clips it to the
 * image (a box that becomes empty lies wholly outside the image or in the padding and is dropped) and merges
 * duplicates across passes (ADR 0012 decision 3).
 *
 * Merging ([Nms.merge]): two boxes are the same face when IoU > [DetectionSettings.nmsIouThreshold] **or** their
 * intersection covers ≥ [CONTAINMENT_THRESHOLD] of the smaller box. Of a duplicate pair the higher score wins; when a
 * box touching an inner tile edge ("cut") is matched by a lower-scored box, the kept box becomes the union of both,
 * and its earlier state stays as a part mask unless the grown box's mask covers it, so a lower threshold never
 * removes or shrinks a mask. The candidates of all passes are bounded ([DetectionSettings.maxMergeCandidates]) and
 * so is the merge work ([DetectionSettings.maxMergeWork]); either bound fails closed.
 *
 * Two engines ([secondEngine]): with at least two tiles, every other tile runs on the second engine. Each
 * pass is prepared (crop, tensor) on the calling thread and handed to its engine, which runs it on its own pass
 * thread while the caller prepares the other engine's pass; the results are taken back in tile order, so the merge
 * gets exactly the candidates, in exactly the order, of one engine. A failure of either pass fails the call: the pass
 * still in flight is abandoned (its session retired, never read). A cancellation lets a pass in flight end and drops
 * its result. Without a second engine (null from [secondEngine]) every pass runs on [engine], as before.
 */
internal class TiledFaceDetector(
    private val engine: FaceDetectorEngine,
    private val config: DetectionSettings,
    /** Diagnostics: stages "crop" (cropScaled for every pass), "engine" (engine.detect) and "merge". */
    private val stageClock: StageClock? = null,
    /** Called before every pass and during long loops; cooperative cancellation throws from here. */
    private val checkCancelled: () -> Unit = {},
    /**
     * The second engine, asked for once per detection when it has at least two tiles; null (or a null
     * result): every pass on [engine]. Its own failure fails the detection.
     */
    private val secondEngine: (() -> FaceDetectorEngine?)? = null,
    /** Test seam: sees every candidate as it enters the merge input (box, score, cut), in merge-input order. */
    private val mergeInputObserver: ((DetectedFace, Boolean) -> Unit)? = null,
) {
    private val tileWidth: Int get() = engine.inputWidth
    private val tileHeight: Int get() = engine.inputHeight

    /** Tile rectangles (x, y, w, h) in full-image pixels for an image of this size. */
    fun planTiles(imageWidth: Int, imageHeight: Int): List<IntArray> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val xs = axisStarts(imageWidth, tileWidth, config.overlap)
        val ys = axisStarts(imageHeight, tileHeight, config.overlap)
        // A single tile equal to the whole image is the global pass itself (same pixels, no scaling).
        if (xs.size == 1 && ys.size == 1) return emptyList()
        val tw = min(tileWidth, imageWidth)
        val th = min(tileHeight, imageHeight)
        val tiles = ArrayList<IntArray>(xs.size * ys.size)
        for (y in ys) for (x in xs) tiles += intArrayOf(x, y, tw, th)
        return tiles
    }

    fun detect(image: DetectionImage): DetectionOutcome {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return DetectionOutcome(emptyList(), 0)
        engine.beginPhoto(config.maxNmsWork)
        val passes = passCount(w, h, tileWidth, tileHeight, config.overlap)
        if (passes > config.maxPasses) {
            val side = largestSquareSide(config.maxPasses, tileWidth, tileHeight, config.overlap)
            throw FaceMaskException.ImageTooLarge(
                w,
                h,
                side.toLong() * side,
                "the pass limit for a $tileWidth x $tileHeight model input (at most ${config.maxPasses} passes; " +
                    "the largest square image within it has ${side.toLong() * side} pixels)",
            )
        }
        val candidates = MergeCandidates(config.maxMergeCandidates)
        checkCancelled()
        runPass(image, intArrayOf(0, 0, w, h), isTile = false, candidates)
        searchTiles(image, planTiles(w, h), candidates)
        val count = candidates.size
        val merged =
            timed(stageClock, "merge") {
                Nms.merge(
                    candidates,
                    config.nmsIouThreshold,
                    CONTAINMENT_THRESHOLD,
                    config.maxMergeWork,
                    config.maxMasks,
                    checkCancelled,
                )
            }
        // Candidates are clipped to the image already, so their unions are too; the clamp only guards the contract.
        val faces = ArrayList<DetectedFace>(merged.faces.size)
        val newIndex = IntArray(merged.faces.size) { -1 }
        for ((i, f) in merged.faces.withIndex()) {
            val r = f.rect.clampTo(w.toFloat(), h.toFloat())
            if (r.isEmpty) continue
            newIndex[i] = faces.size
            faces += f.copy(rect = r)
        }
        val parts = merged.parts.mapNotNull { p ->
            val r = p.rect.clampTo(w.toFloat(), h.toFloat())
            if (newIndex[p.face] < 0 || r.isEmpty) null else MergedPart(newIndex[p.face], r)
        }
        val covers = merged.covers.mapNotNullTo(HashSet()) { newIndex[it].takeIf { f -> f >= 0 } }
        return DetectionOutcome(
            faces,
            passes.toInt(),
            parts,
            covers,
            count,
            merged.pairChecks,
            merged.work,
            engine.nmsWork,
        )
    }

    /**
     * Runs the passes of [tiles] in tile order and adds their candidates to [out]. With at least two tiles and a
     * second engine, every other tile runs on that engine at the same time (see the class); the candidates are added
     * in tile order all the same.
     */
    private fun searchTiles(image: DetectionImage, tiles: List<IntArray>, out: MergeCandidates) {
        if (tiles.isEmpty()) return
        val second = if (tiles.size >= 2) secondEngine?.invoke() else null
        if (second == null) {
            for (tile in tiles) {
                checkCancelled()
                runPass(image, tile, isTile = true, out)
            }
            return
        }
        second.joinPhoto(engine, config.maxNmsWork)
        val engines = arrayOf(engine, second)
        val inFlight = ArrayDeque<Started>(2)
        val add: (DetectedFace, Boolean) -> Unit = { f, cut -> addCandidate(out, f, cut) }
        try {
            for ((k, tile) in tiles.withIndex()) {
                checkCancelled()
                val lane = k % 2
                // The engine's previous pass (and every earlier one) is taken back first, in tile order.
                while (inFlight.any { it.lane == lane }) finishPass(inFlight.removeFirst(), image, add)
                startPass(engines[lane], lane, image, tile, isTile = true)?.let { inFlight.addLast(it) }
            }
            while (inFlight.isNotEmpty()) finishPass(inFlight.removeFirst(), image, add)
        } catch (e: Throwable) {
            endInFlight(inFlight, e)
            throw e
        }
    }

    /**
     * Ends the passes still in flight after [cause] stopped the tile loop. A cancellation lets them end (the runtime is
     * never interrupted) and drops their results, so both sessions stay usable; if one of them fails, that failure is
     * the call's and the rest are abandoned. Any other failure abandons them at once (fail closed: their sessions are
     * retired and nothing of their runs is read).
     */
    private fun endInFlight(inFlight: ArrayDeque<Started>, cause: Throwable) {
        if (cause is FaceMaskException.Cancelled) {
            while (inFlight.isNotEmpty()) {
                val s = inFlight.removeFirst()
                try {
                    s.pass.value.finish()
                } catch (e: Throwable) {
                    abandonAll(inFlight)
                    throw e
                }
            }
        } else {
            abandonAll(inFlight)
        }
    }

    private fun abandonAll(inFlight: ArrayDeque<Started>) {
        while (inFlight.isNotEmpty()) inFlight.removeFirst().pass.value.abandon()
    }

    /**
     * Test hook (ADR 0009): the boxes of the global pass alone, mapped to the image and clipped to it exactly like
     * the candidates of [detect], without the cross-pass merge.
     */
    fun globalPassOnly(image: DetectionImage): List<DetectedFace> {
        engine.beginPhoto(config.maxNmsWork)
        val out = ArrayList<DetectedFace>()
        runPass(image, intArrayOf(0, 0, image.width, image.height), isTile = false) { f, _ -> out += f }
        return out
    }

    private fun runPass(image: DetectionImage, rect: IntArray, isTile: Boolean, out: MergeCandidates) =
        runPass(image, rect, isTile) { f, cut -> addCandidate(out, f, cut) }

    private fun addCandidate(out: MergeCandidates, f: DetectedFace, cut: Boolean) {
        out.add(f.rect, f.score, cut)
        mergeInputObserver?.invoke(f, cut)
    }

    private fun runPass(image: DetectionImage, rect: IntArray, isTile: Boolean, out: (DetectedFace, Boolean) -> Unit) {
        val pass =
            withCrop(image, rect, isTile) { crop ->
                timed(stageClock, "engine") { engine.detect(crop, config.minScore, checkCancelled) }
            } ?: return
        deliver(pass.value, image, rect, isTile, pass.sx, pass.sy, out)
    }

    /** A pass handed to the engine of [lane], with what [finishPass] needs to map its boxes. */
    private class Started(val pass: Cropped<PendingPass>, val lane: Int, val rect: IntArray, val isTile: Boolean)

    /** What a pass made of a crop, and the crop's scale back to the image ([sx], [sy] source pixels per crop pixel). */
    private class Cropped<T>(val value: T, val sx: Float, val sy: Float)

    /** Crops [rect] and hands its pass to [engine] ([FaceDetectorEngine.start]); null when the crop is empty. */
    private fun startPass(
        engine: FaceDetectorEngine,
        lane: Int,
        image: DetectionImage,
        rect: IntArray,
        isTile: Boolean,
    ): Started? {
        val pass =
            withCrop(image, rect, isTile) { crop ->
                timed(stageClock, "engine") { engine.start(crop, config.minScore, checkCancelled) }
            } ?: return null
        return Started(pass, lane, rect, isTile)
    }

    /** Waits for the pass of [s] and gives its boxes to [out] as [runPass] does. */
    private fun finishPass(s: Started, image: DetectionImage, out: (DetectedFace, Boolean) -> Unit) {
        val found = timed(stageClock, "engine") { s.pass.value.finish() }
        deliver(found, image, s.rect, s.isTile, s.pass.sx, s.pass.sy, out)
    }

    /**
     * Runs [block] on the crop of [rect] (a tile at its own size, the global pass scaled to fit the model input) and
     * releases the crop afterwards; null without running [block] when the crop is empty.
     */
    private inline fun <T> withCrop(
        image: DetectionImage,
        rect: IntArray,
        isTile: Boolean,
        block: (DetectionImage) -> T,
    ): Cropped<T>? {
        val (x, y, tw, th) = rect
        val target = if (isTile) max(tw, th) else fitMaxSide(tw, th, tileWidth, tileHeight)
        val crop = timed(stageClock, "crop") { image.cropScaled(x, y, tw, th, max(1, target)) }
        try {
            if (crop.width <= 0 || crop.height <= 0) return null
            val sx = tw.toFloat() / crop.width
            val sy = th.toFloat() / crop.height
            return Cropped(block(crop), sx, sy)
        } finally {
            crop.release()
        }
    }

    /**
     * Maps the boxes [found] of the crop of [rect] (scale [sx], [sy]) back to [image], marks those at an inner tile
     * border as cut, clips them to the image and gives every non-empty one to [out].
     */
    private fun deliver(
        found: List<DetectedFace>,
        image: DetectionImage,
        rect: IntArray,
        isTile: Boolean,
        sx: Float,
        sy: Float,
        out: (DetectedFace, Boolean) -> Unit,
    ) {
        val (x, y, tw, th) = rect
        // Box edges this close to an inner tile border count as "cut" (2 % of the tile, ≥ 2 px).
        val marginX = max(2f, tw * 0.02f)
        val marginY = max(2f, th * 0.02f)
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        for (f in found) {
            f.requireValidDetection()
            val m = f.mapped(x.toFloat(), y.toFloat(), sx, sy)
            val cut =
                isTile &&
                    (
                        (x > 0 && m.rect.left <= x + marginX) ||
                            (y > 0 && m.rect.top <= y + marginY) ||
                            (x + tw < image.width && m.rect.right >= x + tw - marginX) ||
                            (y + th < image.height && m.rect.bottom >= y + th - marginY)
                        )
            // Faces at the edge are normal: clipped to the image; only a box left empty covers no pixel.
            val clipped = m.rect.clampTo(iw, ih)
            if (clipped.isEmpty) continue
            out(m.copy(rect = clipped), cut)
        }
    }

    companion object {
        /** Fraction of the smaller box that must be covered by a higher-scoring box to count as the same face. */
        const val CONTAINMENT_THRESHOLD: Float = 0.7f

        /** Evenly spaced tile starts covering [0, length) with tiles of [side] and at least [overlap]·side overlap. */
        fun axisStarts(length: Int, side: Int, overlap: Float): List<Int> {
            if (length <= side) return listOf(0)
            val n = axisCount(length, side, overlap)
            val span = length - side
            return List(n) { i -> ((i.toDouble() * span) / (n - 1)).roundToInt() }
        }

        /** Number of tiles on one axis: 1 if [length] ≤ [side], otherwise ⌈(length − side) / ((1 − overlap) side)⌉ + 1. */
        fun axisCount(length: Int, side: Int, overlap: Float): Int {
            if (length <= side) return 1
            val stride = max(1f, side * (1f - overlap))
            return ceil((length - side) / stride).toInt() + 1
        }

        /** Passes for a working image of [w] × [h]: one global pass plus the tiles (none when it fits the input). */
        fun passCount(w: Int, h: Int, inW: Int, inH: Int, overlap: Float): Long {
            val nx = axisCount(w, inW, overlap).toLong()
            val ny = axisCount(h, inH, overlap).toLong()
            return if (nx == 1L && ny == 1L) 1L else 1L + nx * ny
        }

        /** The largest square side (≤ 65,535) whose pass count fits [maxPasses]; 0 if none. */
        fun largestSquareSide(maxPasses: Int, inW: Int, inH: Int, overlap: Float): Int {
            var lo = 0
            var hi = MAX_SIDE
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (passCount(mid, mid, inW, inH, overlap) <= maxPasses) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** Longest side of [w] × [h] scaled down (never up) to fit [inW] × [inH]. */
        fun fitMaxSide(w: Int, h: Int, inW: Int, inH: Int): Int {
            if (w <= inW && h <= inH) return max(w, h)
            val s = min(inW.toDouble() / w, inH.toDouble() / h)
            return max(1, floor(max(w, h) * s + 1e-6).toInt())
        }

        private const val MAX_SIDE = 65_535
    }
}
