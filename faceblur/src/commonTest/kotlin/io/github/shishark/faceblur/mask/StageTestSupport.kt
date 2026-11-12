// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random

/**
 * An image that counts every call, fails the test if an array passed to it does not have exactly `w × h` elements,
 * and can throw from a chosen read or write.
 */
internal class RecordingImage(val base: IntArrayPixels) : MutablePixelImage {
    override val width: Int get() = base.width
    override val height: Int get() = base.height
    var reads = 0
        private set
    var writes = 0
        private set
    val runLengths = HashSet<Int>()
    var sizeViolations = 0
        private set

    /** Throws this from the n-th write (1-based), or from the n-th read. */
    var failWriteAt = 0
    var failReadAt = 0
    var failure: () -> Throwable = { IllegalStateException("planted image failure") }

    /** Called before every write (for example to request cancellation). */
    var onWrite: () -> Unit = {}

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        reads++
        if (dst.size != w * h) sizeViolations++
        if (h == 1) runLengths += w
        if (reads == failReadAt) throw failure()
        base.readPixels(x, y, w, h, dst)
    }

    override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        writes++
        if (src.size != w * h) sizeViolations++
        if (h == 1) runLengths += w
        onWrite()
        if (writes == failWriteAt) throw failure()
        base.writePixels(x, y, w, h, src)
    }

    val calls: Int get() = reads + writes
}

/** One request of the working area. */
internal class Request(val kind: String, val size: Int, val step: Int, val array: Any)

/**
 * Records every request of the working area (with the step it happened in) and can throw at a chosen request
 * (1-based). [bytes] is the sum by the same rule as [AreaLayout] (elements plus header, references for tables).
 */
internal class RecordingAllocator(
    private val failAt: Int = 0,
    private val failure: () -> Throwable = { OutOfMemoryError("planted") },
) : StageAllocator {
    val requests = ArrayList<Request>()
    var step = 1
    var bytes = 0L
        private set

    private fun <T : Any> record(kind: String, size: Int, elementBytes: Long, make: () -> T): T {
        if (requests.size + 1 == failAt) throw failure()
        val a = make()
        requests += Request(kind, size, step, a)
        bytes += size * elementBytes + AreaLayout.ARRAY_HEADER
        return a
    }

    override fun ints(size: Int) = record("int", size, 4L) { IntArray(size) }

    override fun longs(size: Int) = record("long", size, 8L) { LongArray(size) }

    override fun floats(size: Int) = record("float", size, 4L) { FloatArray(size) }

    override fun bytes(size: Int) = record("byte", size, 1L) { ByteArray(size) }

    override fun intTable(size: Int) = record("table", size, AreaLayout.REFERENCE) { arrayOfNulls<IntArray>(size) }

    /**
     * True when the only requests were the two tile tables of a [w] × [h] image (the first part of the working
     * area, reserved before the rest is sized): a call refused by the second memory check made these and no other.
     */
    fun onlyTileTables(w: Int, h: Int): Boolean {
        val tiles = ((w + 63) / 64) * ((h + 63) / 64)
        return requests.size == 2 && requests.all { it.kind == "int" && it.size == tiles }
    }

    /** True when every recorded array (except those in [skip], compared by identity) holds only zeros. */
    fun allZero(skip: List<Any> = emptyList()): Boolean =
        requests.filter { r -> skip.none { it === r.array } }.all { r ->
            when (val a = r.array) {
                is IntArray -> a.all { it == 0 }
                is LongArray -> a.all { it == 0L }
                is FloatArray -> a.all { it == 0f }
                is ByteArray -> a.all { it.toInt() == 0 }
                else -> true
            }
        }
}

/** Records the step boundaries, the cancellation reads and the reserved area. */
internal class StepRecorder(private val allocator: RecordingAllocator? = null) : StageObserver {
    val steps = ArrayList<Pair<Int, Long>>()
    val reads = ArrayList<Long>()
    var area: WorkingArea? = null
    var zeroAfterReserve: Boolean? = null

    /** At step 2 every array is still zero except the per-mask arrays, which step 1 fills after the touch. */
    var zeroAtStep2: Boolean? = null
    var onStep: (Int) -> Unit = {}

    override fun step(step: Int, work: Long) {
        steps += step to work
        allocator?.step = step
        val a = area
        if (step == 2 && a != null) zeroAtStep2 = allocator?.allZero(listOf(a.maskFloats, a.maskInts, a.maskFlags))
        onStep(step)
    }

    override fun reserved(area: WorkingArea) {
        this.area = area
        zeroAfterReserve = allocator?.allZero()
    }

    override fun cancellationRead(work: Long) {
        reads += work
    }

    fun unitsAt(step: Int): Long = steps.first { it.first == step }.second
}

/**
 * A probe that reports chosen figures (one per read, the last one repeated): [figures] are the heap maximum or the
 * available memory, [inUse] the heap in use; it counts the collection requests.
 */
internal class FixedProbe(
    override val rule: MemoryRule,
    private vararg val figures: Long,
    private val inUse: LongArray = longArrayOf(0L),
) : MemoryProbe {
    var readsDone = 0
    var inUseReads = 0
    var collections = 0

    override fun figure(): Long = figures[minOf(readsDone++, figures.size - 1)]

    override fun inUse(): Long = inUse[minOf(inUseReads++, inUse.size - 1)]

    override fun collect() {
        collections++
    }
}

internal fun checksum(a: IntArray): Long {
    var h = 1125899906842597L
    for (v in a) h = 31 * h + v
    return h
}

internal fun settings(style: MaskStyle, shape: MaskShape = MaskShape.ELLIPSE) =
    MaskSettings(style = style, shape = shape)

/** Random masks (both shapes) over a [w] × [h] image, many of them overlapping, some at or beyond the edges. */
internal fun randomMasks(random: Random, w: Int, h: Int, n: Int, maxRadius: Float = 60f): List<MaskGeometry> = List(n) {
    val shape = if (random.nextBoolean()) MaskShape.ELLIPSE else MaskShape.RECTANGLE
    val rx = 0.3f + random.nextFloat() * maxRadius
    val ry = 0.3f + random.nextFloat() * maxRadius
    MaskGeometry(-20f + random.nextFloat() * (w + 40), -20f + random.nextFloat() * (h + 40), rx, ry, shape)
}

/** An opaque random image with some smooth structure. */
internal fun stageImage(w: Int, h: Int, seed: Int): IntArrayPixels {
    val r = Random(seed)
    val data = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val base = (x * 7 + y * 3) and 0xFF
        (0xFF shl 24) or (base shl 16) or (((y * 5) and 0xFF) shl 8) or (r.nextInt(256))
    }
    return IntArrayPixels(w, h, data)
}
