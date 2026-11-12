// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.realheap

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.HeapRemainingProbe
import io.github.shishark.faceblur.mask.MaskEngine
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.checksum
import io.github.shishark.faceblur.mask.remainingHeap
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JVM remaining-heap check with the real heap and no test seam (ADR 0013 decision 6), in its own process
 * (`jvmRealHeapTest`, small maximum heap): two parallel calls whose working areas do not both fit; one succeeds,
 * the other gets `NotEnoughMemory` from the check (no out-of-memory cause, pixels unchanged), and no thread of the
 * process sees an `OutOfMemoryError`. With one call and a remaining heap below one working area, the check refuses.
 *
 * The two-call test needs the second call's check to run while the first call still holds its working area, that
 * is before the first call returns. Both calls start from a barrier and the 25 MP compute and copy take far longer
 * than a check, so this holds in practice; it is timing, not a guarantee (the deterministic version is the common
 * `ParallelCallsTest`).
 */
class RealHeapParallelTest {
    private val w = 6_000
    private val h = 4_166
    private val masking = MaskingConfig()
    private val plan = MaskPlan(w, h, listOf(Mask.exact(MaskShape.ELLIPSE, w / 2f, h / 2f, w * 0.5f, h * 0.5f)))
    private val area = MaskEngine.workingAreaBytes(w, h, plan.masks.map { it.toGeometry() }, masking.toSettings())
    private val oomSeen = Collections.synchronizedList(ArrayList<Throwable>())

    @AfterTest
    fun uninstall() {
        FaceBlurRuntime.installMemoryProbe(null)
        assertEquals(0L, ReservedBytes.current())
    }

    private fun remaining(): Long {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return remainingHeap(rt.maxMemory(), rt.totalMemory() - rt.freeMemory(), 0)
    }

    private fun masker() = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))

    private fun image(seed: Int) = IntArrayPixels(
        w,
        h,
        IntArray(w * h) {
            (0xFF shl 24) or
                ((it * 31 + seed) and 0xFFFFFF)
        },
    )

    @Test
    fun twoParallelCallsWhoseAreasDoNotBothFitGiveOneSuccessAndOneRefusalWithoutAnOutOfMemoryError() {
        FaceBlurRuntime.installMemoryProbe(HeapRemainingProbe)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> if (e is OutOfMemoryError) oomSeen += e }
        try {
            val images = listOf(image(1), image(2))
            val before = images.map { checksum(it.data) }
            val free = remaining()
            println("REALHEAP max ${Runtime.getRuntime().maxMemory()} remaining $free working area $area")
            assertTrue(
                free >= area && free < 2 * area,
                "remaining heap $free must fit one working area ($area), not two",
            )
            val barrier = CyclicBarrier(2)
            val outcomes = arrayOfNulls<Throwable>(2)
            val done = BooleanArray(2)
            val threads = (0..1).map { i ->
                Thread {
                    try {
                        masker().use { m ->
                            barrier.await()
                            m.applyMasks(images[i], plan)
                        }
                        done[i] = true
                    } catch (t: Throwable) {
                        outcomes[i] = t
                        if (t is OutOfMemoryError) oomSeen += t
                    }
                }.also { it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> oomSeen += e } }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertEquals(emptyList(), oomSeen.toList(), "no OutOfMemoryError on any thread")
            assertEquals(1, done.count { it }, "exactly one call succeeded; outcomes ${outcomes.toList()}")
            val refused = (0..1).single { !done[it] }
            val e = outcomes[refused]
            assertTrue(e is FaceMaskException.NotEnoughMemory, "the other got NotEnoughMemory: $e")
            assertNull(e.cause, "refused by the check, not the catch")
            assertEquals(before[refused], checksum(images[refused].data), "the refused image is untouched")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun oneCallWithLessRemainingHeapThanItsWorkingAreaIsRefusedByTheCheck() {
        FaceBlurRuntime.installMemoryProbe(HeapRemainingProbe)
        val img = image(3)
        val before = checksum(img.data)
        // The instance (its detector buffers) exists before the ballast, so that only the mask stage runs short.
        val masker = masker()
        val e = masker.use { m ->
            // Ballast so that the remaining heap, as the check computes it, is below one working area (about three
            // quarters of it). The ballast is made of small arrays (256 KiB, below the collector's large-object size
            // for this heap), so it never needs a contiguous free block; the remaining heap is read again after each
            // round. A margin of a quarter area (about 27 MB) is kept because the collector cannot hand out all of
            // the computed remaining heap (G1 keeps a reserve for its own copying). An OutOfMemoryError here is not
            // caught: it fails the test.
            val ballast = ArrayList<IntArray>(4_096)
            var now = remaining()
            var rounds = 0
            while (now >= area && rounds < 8) {
                var target = now - area * 3 / 4
                while (target > 0) {
                    ballast += IntArray(BALLAST_CHUNK_INTS)
                    target -= BALLAST_CHUNK_INTS * 4L
                }
                now = remaining()
                rounds++
            }
            println("REALHEAP ballast ${ballast.size} chunks, remaining $now, working area $area, rounds $rounds")
            assertTrue(now < area, "remaining $now below the working area $area")
            val thrown = kotlin.runCatching { m.applyMasks(img, plan) }.exceptionOrNull()
            assertTrue(ballast.isNotEmpty())
            thrown
        }
        assertTrue(e is FaceMaskException.NotEnoughMemory, "NotEnoughMemory: $e")
        assertNull(e.cause, "refused by the check, not the catch")
        assertEquals(before, checksum(img.data))
    }

    private companion object {
        /** 256 KiB per ballast array. */
        const val BALLAST_CHUNK_INTS = 65_536
    }
}
