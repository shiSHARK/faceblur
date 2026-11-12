// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.MaskStageLimits
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A candidate box for [Nms.mergeCandidates]; [cut] = touches an inner tile border (probably a partial face). */
internal class MergeCandidate(val face: DetectedFace, val cut: Boolean)

/**
 * The candidates of one photo in primitive arrays (four box values, the score and the cut flag per candidate), in
 * chunks so that growing never copies. At most [maxCandidates]; one more is `TOO_MANY_MERGE_CANDIDATES` (never a
 * truncation).
 */
internal class MergeCandidates(private val maxCandidates: Int = Limits.MAX_MERGE_CANDIDATES_PER_PHOTO) {
    private val left = ArrayList<FloatArray>()
    private val top = ArrayList<FloatArray>()
    private val right = ArrayList<FloatArray>()
    private val bottom = ArrayList<FloatArray>()
    private val score = ArrayList<FloatArray>()
    private val cut = ArrayList<BooleanArray>()

    /** Number of candidates added. */
    var size: Int = 0
        private set

    fun add(rect: RectF, score: Float, cut: Boolean) {
        if (size >= maxCandidates) throw OutputCheckFailed(listOf(ModelDetector.TOO_MANY_MERGE_CANDIDATES))
        val c = size ushr CHUNK_BITS
        val i = size and CHUNK_MASK
        if (i == 0) {
            left += FloatArray(CHUNK)
            top += FloatArray(CHUNK)
            right += FloatArray(CHUNK)
            bottom += FloatArray(CHUNK)
            this.score += FloatArray(CHUNK)
            this.cut += BooleanArray(CHUNK)
        }
        left[c][i] = rect.left
        top[c][i] = rect.top
        right[c][i] = rect.right
        bottom[c][i] = rect.bottom
        this.score[c][i] = score
        this.cut[c][i] = cut
        size++
    }

    fun score(i: Int): Float = score[i ushr CHUNK_BITS][i and CHUNK_MASK]

    /** The values of field [f] (0 left, 1 top, 2 right, 3 bottom, 4 score) in the order [order]; frees the chunks. */
    fun takeFloats(f: Int, order: IntArray): FloatArray {
        val chunks = listOf(left, top, right, bottom, score)[f]
        val out = FloatArray(order.size) { chunks[order[it] ushr CHUNK_BITS][order[it] and CHUNK_MASK] }
        chunks.clear()
        return out
    }

    /** The cut flags in the order [order]; frees the chunks. */
    fun takeCut(order: IntArray): BooleanArray {
        val out = BooleanArray(order.size) { cut[order[it] ushr CHUNK_BITS][order[it] and CHUNK_MASK] }
        cut.clear()
        return out
    }

    private companion object {
        const val CHUNK_BITS = 16
        const val CHUNK = 1 shl CHUNK_BITS
        const val CHUNK_MASK = CHUNK - 1
    }
}

/** One part mask of a merged face: an earlier state of the face's box (ADR 0012 decision 3, part masks). */
internal class MergedPart(
    /** Index of the face in [MergeResult.faces]. */
    val face: Int,
    val rect: RectF,
)

/**
 * The merged faces in keep order, their part masks grouped by face, the faces drawn as one rectangle cover, and the
 * merge's work count.
 */
internal class MergeResult(
    val faces: List<DetectedFace>,
    val parts: List<MergedPart>,
    /** Indexes into [faces] of the faces that grew more than [MaskStageLimits.MAX_PART_MASK_GROWTH] times. */
    val covers: Set<Int>,
    /** Kept boxes that the index returned for a candidate (each then gets the overlap test). */
    val pairChecks: Long,
    /** All counted work: [pairChecks] plus the index's cell lookups and skipped entries (the bound applies to it). */
    val work: Long,
)

/** Duplicate removal for boxes from different passes (global pass and tiles) over the same image. */
internal object Nms {
    /**
     * Greedy merge by score (stable for equal scores): a box is a duplicate of the first kept box (in keep order) with
     * IoU > [iouThreshold] or whose intersection covers ≥ [containmentThreshold] of the smaller of the two. Of a
     * duplicate the higher-scoring kept box stays; when the kept box is cut (touches an inner tile border), it becomes
     * the union of both boxes (with the higher score). A kept box therefore never shrinks or disappears when more,
     * lower-scored candidates are added (union property, ADR 0012 decision 3).
     *
     * Part masks: when a kept box grows, its previous state is kept as a part of that face unless the mask of that
     * state lies inside the mask of the face's final box ([PartCover]). A lower threshold repeats the merge of a
     * higher one step by step before it adds lower-scored candidates, so every mask drawn at the higher threshold
     * (a final box or a part) is drawn again, bit for bit, or lies inside a mask that is drawn.
     *
     * Growth cap (ADR 0013 decision 1): the merge counts how often each kept box grew. A face that grew more than
     * [MaskStageLimits.MAX_PART_MASK_GROWTH] times keeps no part masks and is listed in [MergeResult.covers]: it is
     * drawn as one rectangle cover. Each evaluation of the part test counts one unit of work. More than [maxMasks]
     * masks (faces plus kept part masks) is `TOO_MANY_MASKS`, checked on the arrays before any result object exists.
     *
     * Kept boxes are found through a loose multi-level grid (each box listed once, in the level whose cell side is at
     * least the box side), so the cost grows sub-quadratically for face-like candidate sets and the memory is a few
     * integers per box; the result is identical to the plain pairwise merge (same boxes, same order). The worst case
     * is still quadratic, so the work is counted ([MergeResult.work]): every kept box the index returns for a
     * candidate, every cell lookup and every skipped index entry. More than [maxWork] is
     * `TOO_MANY_MERGE_CANDIDATES` before any pixel is written. [checkCancelled] is called every 4,096 candidates
     * and every 65,536 units of work.
     */
    fun merge(
        candidates: MergeCandidates,
        iouThreshold: Float,
        containmentThreshold: Float,
        maxWork: Long = Limits.MAX_MERGE_PAIR_CHECKS_PER_PHOTO,
        maxMasks: Int = MaskStageLimits.MAX_MASKS_PER_IMAGE,
        checkCancelled: () -> Unit = {},
    ): MergeResult {
        val n = candidates.size
        if (n == 0) return MergeResult(emptyList(), emptyList(), emptySet(), 0L, 0L)
        val order = sortedOrder(candidates)
        val l = candidates.takeFloats(0, order)
        val t = candidates.takeFloats(1, order)
        val r = candidates.takeFloats(2, order)
        val b = candidates.takeFloats(3, order)
        val s = candidates.takeFloats(4, order)
        val cut = candidates.takeCut(order)
        val state = ByteArray(n)
        val growth = ByteArray(n)
        val index = LooseGrid(n, l, t, r, b, maxWork, checkCancelled)
        for (c in 0 until n) {
            if (c > 0 && c and CANCEL_CHECK_MASK == 0) checkCancelled()
            val dup = index.firstSameFace(c, iouThreshold, containmentThreshold)
            if (dup < 0) {
                state[c] = KEPT
                index.insert(c)
                continue
            }
            // Standard NMS: the higher-scoring whole box stays and the candidate is dropped.
            if (!cut[dup]) continue
            val nl = min(l[dup], l[c])
            val nt = min(t[dup], t[c])
            val nr = max(r[dup], r[c])
            val nb = max(b[dup], b[c])
            if (nl != l[dup] || nt != t[dup] || nr != r[dup] || nb != b[dup]) {
                // The consumed candidate's slot keeps the previous state of the kept box as a possible part.
                l[c] = l[dup]
                t[c] = t[dup]
                r[c] = r[dup]
                b[c] = b[dup]
                state[c] = PART
                index.setOwner(c, dup)
                index.move(dup, nl, nt, nr, nb)
                if (growth[dup] < MaskStageLimits.GROWTH_SATURATION) growth[dup]++
            }
            s[dup] = max(s[dup], s[c])
            // A whole box makes the kept box whole; two cut boxes stay cut.
            cut[dup] = cut[c]
        }
        return collect(Boxes(l, t, r, b, s), state, growth, index, maxMasks)
    }

    /** Convenience form for lists (tests and small inputs); same result as [merge]. */
    fun mergeCandidates(
        candidates: List<MergeCandidate>,
        iouThreshold: Float,
        containmentThreshold: Float,
        maxWork: Long = Limits.MAX_MERGE_PAIR_CHECKS_PER_PHOTO,
        maxMasks: Int = MaskStageLimits.MAX_MASKS_PER_IMAGE,
        checkCancelled: () -> Unit = {},
    ): MergeResult {
        val store = MergeCandidates(Int.MAX_VALUE)
        for (c in candidates) store.add(c.face.rect, c.face.score, c.cut)
        return merge(store, iouThreshold, containmentThreshold, maxWork, maxMasks, checkCancelled)
    }

    /** The candidates' edges and scores in merge order. */
    private class Boxes(val l: FloatArray, val t: FloatArray, val r: FloatArray, val b: FloatArray, val s: FloatArray) {
        fun rect(i: Int) = RectF(l[i], t[i], r[i], b[i])
    }

    private fun collect(
        boxes: Boxes,
        state: ByteArray,
        growth: ByteArray,
        index: LooseGrid,
        maxMasks: Int,
    ): MergeResult {
        val n = state.size
        // Faces in keep order, counted on the arrays first: no result object exists before the mask limit is checked.
        val faceOf = IntArray(n) { -1 }
        var faceCount = 0
        for (i in 0 until n) if (state[i] == KEPT) faceOf[i] = faceCount++
        // Parts grouped by face (counting sort by face index), in creation order within a face. A face drawn as a
        // cover needs no part test; a part whose mask lies inside its face's final mask is not needed.
        val counts = IntArray(faceCount + 1)
        val keep = BooleanArray(n)
        var masks = faceCount.toLong()
        for (i in 0 until n) {
            if (state[i] != PART) continue
            val owner = index.owner(i)
            if (growth[owner] > MaskStageLimits.MAX_PART_MASK_GROWTH) continue
            if (!PartCover.covers(boxes.rect(owner), boxes.rect(i), index::countWork)) {
                keep[i] = true
                counts[faceOf[owner] + 1]++
                masks++
            }
        }
        if (masks > maxMasks) throw OutputCheckFailed(listOf(ModelDetector.TOO_MANY_MASKS), masks, maxMasks)
        val faces = ArrayList<DetectedFace>(faceCount)
        val covers = HashSet<Int>()
        for (i in 0 until n) {
            if (state[i] != KEPT) continue
            if (growth[i] > MaskStageLimits.MAX_PART_MASK_GROWTH) covers += faces.size
            faces += DetectedFace(boxes.rect(i), boxes.s[i])
        }
        for (f in 1..faceCount) counts[f] += counts[f - 1]
        val parts = arrayOfNulls<MergedPart>(counts[faceCount])
        for (i in 0 until n) {
            if (!keep[i]) continue
            val f = faceOf[index.owner(i)]
            parts[counts[f]++] = MergedPart(f, boxes.rect(i))
        }
        return MergeResult(faces, parts.map { it!! }, covers, index.pairChecks, index.work)
    }

    /** Candidate indexes by descending score, ties in input order (the order of a stable sort by score). */
    private fun sortedOrder(c: MergeCandidates): IntArray {
        val keys = LongArray(c.size) { i ->
            // Float total order (as Float.compareTo) mapped to a signed Int, inverted for descending order.
            val bits = c.score(i).toRawBits()
            val ascending = bits xor ((bits shr 31) and 0x7FFFFFFF)
            (ascending.inv().toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        keys.sort()
        return IntArray(keys.size) { keys[it].toInt() }
    }

    fun sameFace(a: RectF, b: RectF, iouThreshold: Float, containment: Float): Boolean {
        val inter = a.intersectionArea(b)
        if (inter <= 0f) return false
        val union = a.area + b.area - inter
        if (union > 0f && inter / union > iouThreshold) return true
        val smaller = min(a.area, b.area)
        return smaller > 0f && inter / smaller >= containment
    }

    fun union(a: RectF, b: RectF) =
        RectF(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

    private const val KEPT: Byte = 1
    private const val PART: Byte = 2
    private const val CANCEL_CHECK_MASK = 0xFFF
}

/**
 * The index over kept boxes: a loose grid with levels of cell side 8 × 2^level px. A box is listed once, in the
 * first level whose cell side is at least its longer side, in the cell of its top-left corner, so it overlaps at
 * most that cell and the next one on each axis. A query looks, per non-empty level, at the cells its box overlaps
 * plus one cell to the left and above, or at the whole level when that is fewer entries. Cells share hash buckets;
 * an entry of another cell in a bucket is skipped (and counted). Boxes larger than the last level go to a list that
 * every query reads. The arrays are indexed by candidate position (sorted order).
 */
private class LooseGrid(
    n: Int,
    private val l: FloatArray,
    private val t: FloatArray,
    private val r: FloatArray,
    private val b: FloatArray,
    private val maxWork: Long,
    private val checkCancelled: () -> Unit,
) {
    var work = 0L
        private set
    var pairChecks = 0L
        private set

    private val level = ByteArray(n)
    private val cellNext = IntArray(n)
    private val levelNext = IntArray(n)
    private val levelPrev = IntArray(n)
    private val levelHead = IntArray(LEVELS) { NONE }
    private val levelCount = IntArray(LEVELS)
    private val levelBox = DoubleArray(LEVELS * 4)
    private var buckets = IntArray(16) { NONE }
    private var live = 0

    /** The owner of a part slot (a consumed candidate is never in the index, so its cell link is free). */
    fun setOwner(part: Int, owner: Int) {
        cellNext[part] = owner
    }

    fun owner(part: Int): Int = cellNext[part]

    /** The first kept box in keep order that is the same face as candidate [c], or -1. */
    fun firstSameFace(c: Int, iou: Float, containment: Float): Int {
        val q = RectF(l[c], t[c], r[c], b[c])
        dup = -1
        for (lv in 0 until LEVELS) {
            if (levelCount[lv] == 0 || !mayOverlap(lv, q)) continue
            if (lv == HUGE) {
                scanLevel(lv, q, iou, containment)
                continue
            }
            val side = cellSide(lv)
            val x0 = cell(q.left, side) - 1
            val x1 = cell(q.right, side)
            val y0 = cell(q.top, side) - 1
            val y1 = cell(q.bottom, side)
            // A cell lookup also walks its bucket (about live / buckets entries; the table is kept at most half full),
            // so the whole level is read when that is cheaper.
            val lookups = (x1 - x0 + 1) * (y1 - y0 + 1)
            val bucketCount = buckets.size.toLong()
            if (lookups > levelCount[lv] || lookups * (bucketCount + live) > levelCount[lv] * bucketCount) {
                scanLevel(lv, q, iou, containment)
                continue
            }
            for (cy in y0..y1) {
                for (cx in x0..x1) {
                    tick()
                    var j = buckets[bucket(lv, cx, cy)]
                    while (j != NONE) {
                        tick()
                        if (level[j].toInt() == lv && cell(l[j], side) == cx && cell(t[j], side) == cy) {
                            visit(j, q, iou, containment)
                        }
                        j = cellNext[j]
                    }
                }
            }
        }
        return dup
    }

    /** The current query's best duplicate (lowest keep index so far). */
    private var dup = -1

    private fun visit(j: Int, q: RectF, iou: Float, containment: Float) {
        pairChecks++
        if ((dup < 0 || j < dup) && Nms.sameFace(RectF(l[j], t[j], r[j], b[j]), q, iou, containment)) dup = j
    }

    private fun scanLevel(lv: Int, q: RectF, iou: Float, containment: Float) {
        var j = levelHead[lv]
        while (j != NONE) {
            tick()
            visit(j, q, iou, containment)
            j = levelNext[j]
        }
    }

    fun insert(j: Int) {
        val lv = levelOf(j)
        level[j] = lv.toByte()
        levelPrev[j] = NONE
        levelNext[j] = levelHead[lv]
        if (levelHead[lv] != NONE) levelPrev[levelHead[lv]] = j
        levelHead[lv] = j
        val k = lv * 4
        if (levelCount[lv] == 0) {
            levelBox[k] = l[j].toDouble()
            levelBox[k + 1] = t[j].toDouble()
            levelBox[k + 2] = r[j].toDouble()
            levelBox[k + 3] = b[j].toDouble()
        } else {
            levelBox[k] = min(levelBox[k], l[j].toDouble())
            levelBox[k + 1] = min(levelBox[k + 1], t[j].toDouble())
            levelBox[k + 2] = max(levelBox[k + 2], r[j].toDouble())
            levelBox[k + 3] = max(levelBox[k + 3], b[j].toDouble())
        }
        levelCount[lv]++
        live++
        if (live * 2 > buckets.size) rehash() else link(j)
    }

    /** Gives kept box [j] the new edges; re-lists it when its level or cell changes. */
    fun move(j: Int, nl: Float, nt: Float, nr: Float, nb: Float) {
        val lv = level[j].toInt()
        val oldX = if (lv == HUGE) 0L else cell(l[j], cellSide(lv))
        val oldY = if (lv == HUGE) 0L else cell(t[j], cellSide(lv))
        remove(j, lv, oldX, oldY)
        l[j] = nl
        t[j] = nt
        r[j] = nr
        b[j] = nb
        insert(j)
    }

    private fun remove(j: Int, lv: Int, cx: Long, cy: Long) {
        if (levelPrev[j] != NONE) levelNext[levelPrev[j]] = levelNext[j] else levelHead[lv] = levelNext[j]
        if (levelNext[j] != NONE) levelPrev[levelNext[j]] = levelPrev[j]
        levelCount[lv]--
        live--
        if (lv == HUGE) return
        val h = bucket(lv, cx, cy)
        var prev = NONE
        var k = buckets[h]
        while (k != j) {
            tick()
            prev = k
            k = cellNext[k]
        }
        if (prev == NONE) buckets[h] = cellNext[j] else cellNext[prev] = cellNext[j]
    }

    private fun link(j: Int) {
        val lv = level[j].toInt()
        if (lv == HUGE) return
        val side = cellSide(lv)
        val h = bucket(lv, cell(l[j], side), cell(t[j], side))
        cellNext[j] = buckets[h]
        buckets[h] = j
    }

    private fun rehash() {
        buckets = IntArray(buckets.size * 2) { NONE }
        for (lv in 0 until HUGE) {
            var j = levelHead[lv]
            while (j != NONE) {
                link(j)
                j = levelNext[j]
            }
        }
    }

    /** One unit of work outside the index (an evaluation of the part test), under the same bound. */
    fun countWork() = tick()

    /** One unit of counted work: the bound and the cancellation read are applied here. */
    private fun tick() {
        work++
        if (work > maxWork) throw OutputCheckFailed(listOf(ModelDetector.TOO_MANY_MERGE_CANDIDATES))
        if (work and WORK_CANCEL_MASK == 0L) checkCancelled()
    }

    private fun mayOverlap(lv: Int, q: RectF): Boolean {
        val k = lv * 4
        return q.right > levelBox[k] && q.left < levelBox[k + 2] && q.bottom > levelBox[k + 1] &&
            q.top < levelBox[k + 3]
    }

    /** The first level whose cell side is at least the longer side of box [j] (exact in double), else [HUGE]. */
    private fun levelOf(j: Int): Int {
        val side = max(r[j].toDouble() - l[j].toDouble(), b[j].toDouble() - t[j].toDouble())
        var lv = 0
        while (lv < HUGE && side > cellSide(lv)) lv++
        return lv
    }

    private fun bucket(lv: Int, cx: Long, cy: Long): Int {
        var h = lv.toLong() * -0x61c8864680b583ebL + cx * -0x3d4d51c2d82b14b1L + cy * 0x165667b19e3779f9L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 33)
        return (h.toInt()) and (buckets.size - 1)
    }

    private companion object {
        const val NONE = -1

        /** Regular levels 0..27 (cell side 8 px .. 2^30 px) and the list of larger boxes. */
        const val HUGE = 28
        const val LEVELS = HUGE + 1
        const val WORK_CANCEL_MASK = 0xFFFFL
        const val MAX_CELL = 1L shl 30

        fun cellSide(lv: Int): Double = 8.0 * (1L shl lv).toDouble()

        /** The cell of coordinate [v] (exact: the side is a power of two), clamped to ±2^30 (monotonic). */
        fun cell(v: Float, side: Double): Long =
            floor(v.toDouble() / side).coerceIn(-MAX_CELL.toDouble(), MAX_CELL.toDouble()).toLong()
    }
}

/**
 * Whether the mask of a part box lies inside the mask of its face's final box, for every allowed expansion and both
 * shapes. Exact test of the ellipses at the smallest allowed expansion (×1.6 / ×1.8, shift up 0.08 × height; a
 * part inside there stays inside at every larger expansion, and a rectangle mask is inside whenever the ellipse
 * test holds for boxes inside each other), with a margin of 0.05 px plus 1e-4 relative on both ellipses so that
 * the float evaluation of mask pixels can never put a part pixel outside the face's mask. Undecided cases count as
 * not covered (the part is drawn). Containment is about the drawn masks, whose radii the stage raises to at least
 * 1 px: when any radius of the part or the face at the smallest expansion is below [MIN_TESTED_RADIUS], the part is
 * drawn. [onEvaluation] is called once per evaluation of the boundary function (at most 4,096 per part).
 */
internal object PartCover {
    /** A radius below this at the smallest expansion: the part is drawn (ADR 0013 decision 1, small boxes). */
    const val MIN_TESTED_RADIUS = 1.05
    private const val MARGIN_PX = 0.05
    private const val MARGIN_REL = 1e-4
    private const val MAX_EVALUATIONS = 4_096
    private const val TWO_PI = 6.283185307179586

    fun covers(face: RectF, part: RectF, onEvaluation: () -> Unit = {}): Boolean {
        val ex = MaskSettings.DEFAULT_EXPAND_X.toDouble()
        val ey = MaskSettings.DEFAULT_EXPAND_Y.toDouble()
        val shift = MaskSettings.DEFAULT_SHIFT_UP.toDouble()
        fun centreX(rr: RectF) = (rr.left.toDouble() + rr.right.toDouble()) / 2.0
        fun centreY(rr: RectF): Double {
            val h = rr.bottom.toDouble() - rr.top.toDouble()
            return (rr.top.toDouble() + rr.bottom.toDouble()) / 2.0 - shift * h
        }
        val pa = ex * (part.right.toDouble() - part.left.toDouble()) / 2.0
        val pb = ey * (part.bottom.toDouble() - part.top.toDouble()) / 2.0
        val fa = ex * (face.right.toDouble() - face.left.toDouble()) / 2.0
        val fb = ey * (face.bottom.toDouble() - face.top.toDouble()) / 2.0
        if (!(pa > 0.0 && pb > 0.0 && fa > 0.0 && fb > 0.0)) return false
        // The stage raises radii below 1 px around the same centre; a raised part could stick out of the drawn face.
        if (pa < MIN_TESTED_RADIUS || pb < MIN_TESTED_RADIUS || fa < MIN_TESTED_RADIUS || fb < MIN_TESTED_RADIUS) {
            return false
        }
        val grow = 1.0 + MARGIN_REL + MARGIN_PX / min(pa, pb)
        val shrink = 1.0 - MARGIN_REL - MARGIN_PX / min(fa, fb)
        if (shrink <= 0.0) return false
        val c = fa * shrink
        val d = fb * shrink
        // In the face ellipse's unit frame the part is the ellipse (u + α cos θ, v + β sin θ); it is inside the unit
        // circle when F(θ) = (u + α cos θ)² + (v + β sin θ)² ≤ 1 for every θ.
        val u = (centreX(part) - centreX(face)) / c
        val v = (centreY(part) - centreY(face)) / d
        val alpha = pa * grow / c
        val beta = pb * grow / d
        // The extreme points at θ = 0, π (or ±π/2) already stick out: not covered.
        if (abs(u) + alpha > 1.0 || abs(v) + beta > 1.0) return false
        // Branch and bound with a bound on F'' (M): F(θ) ≤ F(m) + |F'(m)| h + M h² / 2 on [m - h, m + h].
        val m2 = 2 * alpha * alpha + 2 * alpha * (abs(u) + alpha) + 2 * beta * beta + 2 * beta * (abs(v) + beta)
        val stack = ArrayDeque<Double>()
        val start = 64
        val h0 = TWO_PI / start / 2.0
        for (i in 0 until start) {
            stack.addLast((2 * i + 1) * h0)
            stack.addLast(h0)
        }
        var evaluations = 0
        while (stack.isNotEmpty()) {
            val h = stack.removeLast()
            val mid = stack.removeLast()
            if (++evaluations > MAX_EVALUATIONS) return false
            onEvaluation()
            val cs = cos(mid)
            val sn = sin(mid)
            val x = u + alpha * cs
            val y = v + beta * sn
            val f = x * x + y * y
            if (f > 1.0) return false
            val df = -2 * alpha * sn * x + 2 * beta * cs * y
            if (f + abs(df) * h + m2 * h * h / 2.0 <= 1.0) continue
            if (h < 1e-9) return false
            stack.addLast(mid - h / 2)
            stack.addLast(h / 2)
            stack.addLast(mid + h / 2)
            stack.addLast(h / 2)
        }
        return true
    }
}
