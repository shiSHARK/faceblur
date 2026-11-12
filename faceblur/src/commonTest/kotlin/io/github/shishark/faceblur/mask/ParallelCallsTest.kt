// SPDX-License-Identifier: Apache-2.0
@file:OptIn(
    ExperimentalAtomicApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Parallel calls on separate instances share one process-wide count of reserved bytes (ADR 0013 decision 6): with a
 * memory figure that fits K working areas, exactly K of N overlapping calls proceed; the others get
 * `NotEnoughMemory` from the check (no out-of-memory cause, no allocation request except the two small tile tables
 * that size the rest, no read, no write, pixels unchanged). Runs in every platform's test run.
 */
class ParallelCallsTest {
    private val w = 200
    private val h = 150
    private val plan = MaskPlan(
        w,
        h,
        listOf(Mask.exact(MaskShape.ELLIPSE, 60f, 70f, 40f, 50f), Mask.exact(MaskShape.RECTANGLE, 150f, 60f, 30f, 30f)),
    )

    private fun masker() = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))

    /** The bytes one call of [plan] reserves with the default masking. */
    private val bytes: Long = MaskEngine.workingAreaBytes(
        w,
        h,
        plan.masks.map { it.toGeometry() },
        io.github.shishark.faceblur.MaskingConfig().toSettings(),
    )

    /** Waits (spinning, bounded) until [condition] holds. */
    private fun await(condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(start.elapsedNow().inWholeSeconds < 30) { "parallel test did not converge" }
        }
    }

    /**
     * An allocator that holds a call at its first request after the two tile tables (that is, after the second
     * memory check passed) until every call has either got there or been refused, so all checks overlap while the
     * admitted calls still hold their bytes in the count.
     */
    private class GateAllocator(private val gate: () -> Unit) : StageAllocator {
        val inner = RecordingAllocator()
        private var seen = 0

        private fun first() {
            if (++seen == TILE_TABLES + 1) gate()
        }

        override fun ints(size: Int): IntArray {
            first()
            return inner.ints(size)
        }

        override fun longs(size: Int): LongArray {
            first()
            return inner.longs(size)
        }

        override fun floats(size: Int): FloatArray {
            first()
            return inner.floats(size)
        }

        override fun bytes(size: Int): ByteArray {
            first()
            return inner.bytes(size)
        }

        override fun intTable(size: Int): Array<IntArray?> {
            first()
            return inner.intTable(size)
        }
    }

    private class Outcome(val refused: FaceMaskException.NotEnoughMemory?, val image: RecordingImage, val requests: Int)

    private fun runParallel(calls: Int, fits: Int, order: List<Int>): List<Outcome> {
        val probe = FixedProbe(MemoryRule.HEAP_REMAINING, fits * bytes + bytes - 1)
        val started = AtomicInt(0)
        val settled = AtomicInt(0)
        val maskers = List(calls) { masker() }
        val images = List(calls) { RecordingImage(stageImage(w, h, 7)) }
        val allocators = List(calls) {
            GateAllocator {
                settled.addAndFetch(1)
                await { settled.load() == calls }
            }
        }
        for (i in 0 until calls) maskers[i].useStageSeamsForTest(StageSeams(allocators[i], probe))
        val pool = newFixedThreadPoolContext(calls, "parallel-calls")
        try {
            return runBlocking(pool) {
                order.map { i ->
                    async {
                        started.addAndFetch(1)
                        await { started.load() == calls }
                        val refused = try {
                            maskers[i].applyMasks(images[i], plan)
                            null
                        } catch (e: FaceMaskException.NotEnoughMemory) {
                            settled.addAndFetch(1)
                            e
                        }
                        i to Outcome(refused, images[i], allocators[i].inner.requests.size)
                    }
                }.awaitAll().sortedBy { it.first }.map { it.second }
            }
        } finally {
            pool.close()
            maskers.forEach { it.close() }
        }
    }

    private val alone: IntArray = RecordingImage(stageImage(w, h, 7)).also { img ->
        masker().use { it.applyMasks(img, plan) }
    }.base.data

    private fun assertOutcomes(outcomes: List<Outcome>, fits: Int, what: String) {
        val before = checksum(stageImage(w, h, 7).data)
        assertEquals(fits, outcomes.count { it.refused == null }, "$what: exactly $fits calls proceed")
        for (o in outcomes) {
            val r = o.refused
            if (r == null) {
                assertContentEquals(alone, o.image.base.data, "$what: equal to the same call run alone")
            } else {
                assertNull(r.cause, "$what: refused by the check, not the catch")
                assertEquals(TILE_TABLES, o.requests, "$what: no allocation request but the two tile tables")
                assertEquals(0, o.image.calls, "$what: no read, no write")
                assertEquals(before, checksum(o.image.base.data))
            }
        }
        assertEquals(0L, ReservedBytes.current(), "$what: the count returns to 0")
    }

    private companion object {
        /** The first part of every reservation: the shadow and coverage tile tables. */
        const val TILE_TABLES = 2
    }

    @Test
    fun twoCallsWithMemoryForOneLetExactlyOneProceed() {
        assertTrue(bytes > 0)
        assertOutcomes(runParallel(2, 1, listOf(0, 1)), 1, "2 calls, fits 1")
    }

    @Test
    fun eightCallsWithMemoryForThreeLetExactlyThreeProceedInAnyStartOrder() {
        val random = Random(2026)
        repeat(100) { round ->
            val order = (0 until 8).shuffled(random)
            assertOutcomes(runParallel(8, 3, order), 3, "round $round, order $order")
        }
    }
}
