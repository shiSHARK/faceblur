// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Two passes at a time through the masker: the first detection with at least two tiles opens a second session (with
 * the same checks and limits as the first) and runs every other tile on it. A run that never returns or fails in
 * either session fails the call closed with the existing errors, nothing of either run is used again (the next call
 * opens two new sessions and gives the output of a normal run), and stuck runs of both sessions count toward the
 * process-wide bound. A second session that cannot be created leaves the masker on one session with the same result.
 * Runs on every platform of the common tests (JVM, Android host, iOS simulator), on the platform's pass threads.
 */
class TwoSessionsTest {
    private val release = Release()

    /** Short limits: contract run 1 s, passes 300 ms, session creation 500 ms. */
    private val short = RunLimits(contractMillis = 1_000L, minPassMillis = 300L, openMillis = 500L)

    @AfterTest
    fun cleanUp() {
        release.open()
        assertNoAbandonedRunsLeft()
    }

    /** 1280 x 960: the global pass and 6 tiles (3 x 2); tiles 0, 2, 4 on the first session, 1, 3, 5 on the second. */
    private fun image() = IntArrayPixels(1280, 960, gradientPixels(1280, 960))

    /** The masks and pixels of a normal run on one session. */
    private fun reference(): Pair<MaskResult, IntArray> {
        val img = image()
        val result = FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(onnxThreads = 1),
            RunnerRuntime(OneFaceRunner()),
        ).use {
            it.maskInPlace(img)
        }
        return result to img.data
    }

    /**
     * Sessions over the one-face runner, recorded in creation order (thread-safe: creations run on pass threads).
     * Session i blocks from run [blockFrom] (i) (1 = its contract run) until [release] opens, fails from run
     * [failFrom] (i) with a runtime error, and reports the execution provider [provider] (i); creation i waits on
     * [release] when [blockOpen] (i) and throws [openFailure] (i) when that is not null.
     */
    private inner class Sessions(
        private val blockFrom: (Int) -> Int = { NEVER },
        private val failFrom: (Int) -> Int = { NEVER },
        private val blockOpen: (Int) -> Boolean = { false },
        private val openFailure: (Int) -> Throwable? = { null },
        private val provider: (Int) -> String = { "CPU" },
    ) : ModelRuntime {
        private val opened = AtomicReference<List<BlockingSession>>(emptyList())
        private val openCount = AtomicInt(0)

        val sessions: List<BlockingSession> get() = opened.load()
        val opens: Int get() = openCount.load()

        override fun open(model: DetectorModel, threads: Int): ModelSession {
            val i = openCount.addAndFetch(1) - 1
            openFailure(i)?.let { throw it }
            val failing =
                FailingSession(FakeSession(OneFaceRunner(), model, executionProvider = provider(i)), failFrom(i))
            val session = BlockingSession(failing, release, blockFrom(i))
            while (true) {
                val before = opened.load()
                if (opened.compareAndSet(before, before + session)) break
            }
            if (blockOpen(i)) release.await()
            return session
        }
    }

    /** A session whose run number [failFrom] and every later one throw a runtime error (with runtime text). */
    private class FailingSession(private val base: ModelSession, private val failFrom: Int) : ModelSession by base {
        private val runs = AtomicInt(0)

        override fun run(
            input: FloatArray,
            inputShape: LongArray,
            outputNames: List<String>,
            maxElements: Long,
            timeoutMillis: Long,
        ): Map<String, PassOutput> {
            if (runs.addAndFetch(1) >= failFrom) throw IllegalStateException("runtime text that names a model file")
            return base.run(input, inputShape, outputNames, maxElements, timeoutMillis)
        }
    }

    private fun masker(rt: ModelRuntime, config: FaceMaskerConfig = FaceMaskerConfig()) =
        FaceMasker.forTest(testYuNetModel(), config, rt, runLimits = short)

    /** Waits up to 10 s without CPU until exactly [n] abandoned runs are left, then asserts it. */
    private fun awaitAbandonedRuns(n: Int) {
        var waited = 0L
        while (AbandonedRuns.count() != n && waited < 10_000L) {
            pause(5L)
            waited += 5L
        }
        assertEquals(n, AbandonedRuns.count())
    }

    /**
     * The run that never returns is the first tile run of session [stuck] (0: the first session, its run 3 after the
     * contract run and the global pass; 1: the second session, its run 2 after its contract run).
     */
    private fun aStuckRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(stuck: Int) {
        val rt = Sessions(blockFrom = { i -> if (i == stuck) (if (stuck == 0) 3 else 2) else NEVER })
        val masker = masker(rt)
        val img = image()
        val before = img.data.copyOf()
        var e: FaceMaskException.ModelOutputInvalid? = null
        val took = millisOf { e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) } }
        assertEquals(listOf("RUN_TIMEOUT"), e?.findings)
        val limit = rt.sessions[stuck].blockedLimit
        assertTrue(limit >= short.minPassMillis, "the pass limit of that session applies: $limit")
        assertTrue(took >= limit && took <= limit + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms, $limit ms")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(2, rt.opens, "both sessions were in use")
        val other = 1 - stuck
        assertTrue(rt.sessions[other].awaitClosed(10_000L), "the other session is closed, not kept")
        awaitAbandonedRuns(1)
        assertEquals(0, rt.sessions[stuck].closes, "the stuck session is left to its pass thread")
        val runs = rt.sessions.map { it.runs }

        val result = masker.maskInPlace(img)
        assertEquals(4, rt.opens, "the next call opened two new sessions")
        assertEquals(runs, rt.sessions.take(2).map { it.runs }, "neither session of the failed call ran again")
        val (expected, pixels) = reference()
        assertEquals(expected.masks, result.masks)
        assertContentEquals(pixels, img.data, "the same output as a normal run")

        release.open()
        assertTrue(rt.sessions[stuck].awaitClosed(10_000L), "the stuck session is closed when its run returns")
        assertNoAbandonedRunsLeft()
        masker.close()
        assertEquals(listOf(1, 1, 1, 1), rt.sessions.map { it.closes }, "every session closed exactly once")
    }

    @Test
    fun aRunThatNeverReturnsInTheFirstSessionFailsTheCallClosed() =
        aStuckRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(0)

    @Test
    fun aRunThatNeverReturnsInTheSecondSessionFailsTheCallClosed() =
        aStuckRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(1)

    private fun aFailingRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(failing: Int) {
        val rt = Sessions(failFrom = { i -> if (i == failing) (if (failing == 0) 4 else 3) else NEVER })
        val masker = masker(rt)
        val img = image()
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertTrue(e.message?.contains("model file") != true, "no runtime text")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(2, rt.opens)
        assertTrue(rt.sessions.all { it.awaitClosed(10_000L) }, "both sessions are closed, not kept")
        assertNoAbandonedRunsLeft()
        val runs = rt.sessions.map { it.runs }

        val result = masker.maskInPlace(img)
        assertEquals(4, rt.opens, "the next call opened two new sessions")
        assertEquals(runs, rt.sessions.take(2).map { it.runs }, "neither session of the failed call ran again")
        val (expected, pixels) = reference()
        assertEquals(expected.masks, result.masks)
        assertContentEquals(pixels, img.data)
        masker.close()
        assertEquals(listOf(1, 1, 1, 1), rt.sessions.map { it.closes })
    }

    @Test
    fun aRunThatFailsInTheFirstSessionFailsTheCallClosed() =
        aFailingRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(0)

    @Test
    fun aRunThatFailsInTheSecondSessionFailsTheCallClosed() =
        aFailingRunFailsTheCallClosedAndNothingOfEitherSessionIsReused(1)

    @Test
    fun aSecondSessionTheRuntimeCannotCreateLeavesOneSessionWithTheSameResult() {
        val failures = listOf<() -> Throwable>({ SessionRejected() }, { IllegalStateException("no memory") })
        for (failure in failures) {
            val rt = Sessions(openFailure = { i -> if (i == 1) failure() else null })
            val masker = masker(rt)
            val (expected, pixels) = reference()
            repeat(2) {
                val img = image()
                val result = masker.maskInPlace(img)
                assertEquals(expected.masks, result.masks)
                assertEquals(expected.faces, result.faces)
                assertEquals(expected.timings.passes, result.timings.passes)
                assertContentEquals(pixels, img.data, "the same output on one session")
            }
            assertEquals(2, rt.opens, "one attempt, not retried while the first session lasts")
            assertEquals(1, rt.sessions.size)
            masker.close()
            assertEquals(1, rt.sessions[0].closes)
        }
    }

    @Test
    fun aSecondSessionReportedDifferentlyIsClosedAndNotUsed() {
        val rt = Sessions(provider = { i -> if (i == 1) "XNNPACK" else "CPU" })
        val masker = masker(rt)
        val img = image()
        val result = masker.maskInPlace(img)
        val (expected, pixels) = reference()
        assertEquals(expected.masks, result.masks)
        assertContentEquals(pixels, img.data)
        assertEquals(2, rt.opens)
        assertEquals(1, rt.sessions[1].closes, "closed at once")
        assertEquals(1, rt.sessions[1].runs, "only its contract run")
        masker.close()
    }

    @Test
    fun aSecondSessionCreationPastItsLimitFailsTheCallWithRunTimeout() {
        val rt = Sessions(blockOpen = { it == 1 })
        val masker = masker(rt)
        val img = image()
        val before = img.data.copyOf()
        var e: FaceMaskException.ModelOutputInvalid? = null
        val took = millisOf { e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) } }
        assertEquals(listOf("RUN_TIMEOUT"), e?.findings, "not one session: a creation past its limit fails closed")
        assertTrue(took >= short.openMillis && took <= short.openMillis + TIMEOUT_MARGIN_MILLIS, "$took ms")
        assertContentEquals(before, img.data)
        assertEquals(1, AbandonedRuns.count(), "the stuck creation counts toward the bound")

        val result = masker.maskInPlace(img)
        assertEquals(3, rt.opens, "the next call tried again")
        assertContentEquals(reference().second, img.data)
        assertEquals(reference().first.masks, result.masks)
        release.open()
        assertTrue(rt.sessions[1].awaitClosed(10_000L), "the late session is closed when its creation returns")
        assertEquals(0, rt.sessions[1].runs, "and never ran")
        assertNoAbandonedRunsLeft()
        masker.close()
    }

    @Test
    fun aSecondSessionWhoseContractRunNeverReturnsFailsTheCallWithRunTimeout() {
        val rt = Sessions(blockFrom = { i -> if (i == 1) 1 else NEVER })
        val masker = masker(rt)
        val img = image()
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        assertContentEquals(before, img.data)
        release.open()
        assertTrue(rt.sessions[1].awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
        masker.maskInPlace(img)
        assertContentEquals(reference().second, img.data)
        masker.close()
    }

    @Test
    fun stuckRunsOfBothSessionsCountTowardTheProcessWideBound() {
        // Every first session blocks in its first tile run, every second session in its first tile run: each call
        // leaves two stuck runs (the second one abandoned when the first one's limit ends the call).
        val rt = Sessions(blockFrom = { i -> if (i % 2 == 0) 3 else 2 })
        val masker = masker(rt)
        repeat(AbandonedRuns.MAX / 2) {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(image()) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        awaitAbandonedRuns(AbandonedRuns.MAX)
        val opened = rt.opens
        val took = millisOf {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(image()) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        assertTrue(took < short.minPassMillis, "refused without waiting: $took ms")
        assertEquals(opened, rt.opens, "no session was created at the bound")
        release.open()
        assertNoAbandonedRunsLeft()
        assertTrue(rt.sessions.all { it.awaitClosed(10_000L) }, "every stuck session was closed")
        masker.close()
    }

    @Test
    fun aCancellationDuringTheTilesLetsThePassesInFlightEndAndKeepsBothSessions() {
        val rt = Sessions()
        val masker = masker(rt)
        // Reads: before detection, after the session, before the global pass, then one per tile: the fourth tile.
        val token = object : CancellationToken {
            var reads = 0
            override val isCancellationRequested: Boolean get() = ++reads >= 7
        }
        val img = image()
        val before = img.data.copyOf()
        assertFailsWith<FaceMaskException.Cancelled> { masker.maskInPlace(img, token) }
        assertContentEquals(before, img.data)
        assertEquals(2, rt.opens)
        assertEquals(0, AbandonedRuns.count(), "the passes in flight ended")
        assertEquals(listOf(0, 0), rt.sessions.map { it.closes }, "both sessions kept")
        val result = masker.maskInPlace(img)
        assertEquals(2, rt.opens, "no new session")
        assertEquals(reference().first.masks, result.masks)
        assertContentEquals(reference().second, img.data)
        masker.close()
        assertEquals(listOf(1, 1), rt.sessions.map { it.closes })
    }

    @Test
    fun aReplacementSessionReportedDifferentlyFailsTheCallAndIsNotUsed() {
        // Session 0 blocks in its global pass (one-pass image); every replacement reports another execution provider.
        val rt =
            Sessions(
                blockFrom = { i -> if (i == 0) 2 else NEVER },
                provider = { i -> if (i == 0) "CPU" else "XNNPACK" },
            )
        val masker = masker(rt)
        val small = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val first = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(small) }
        assertEquals(listOf("RUN_TIMEOUT"), first.findings)
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(small) }
        assertEquals(listOf("RUN_FAILED"), e.findings, "not the session this masker reported in modelInfo")
        assertEquals(1, rt.sessions[1].closes, "closed at once")
        assertEquals(1, rt.sessions[1].runs, "only its contract run")
        assertEquals("CPU", masker.modelInfo.executionProvider)
        release.open()
        assertNoAbandonedRunsLeft()
        masker.close()
    }

    @Test
    fun aCancellationRequestedWhileAReplacementOpensIsHonouredBeforeAnyPass() {
        val rt = Sessions(blockFrom = { i -> if (i == 0) 2 else NEVER })
        val masker = masker(rt)
        val small = IntArrayPixels(640, 480, gradientPixels(640, 480))
        assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(small) }
        // Read 1: before detection; read 2: after the replacement was opened.
        val token = object : CancellationToken {
            var reads = 0
            override val isCancellationRequested: Boolean get() = ++reads >= 2
        }
        assertFailsWith<FaceMaskException.Cancelled> { masker.detect(small, token) }
        assertEquals(2, rt.opens, "the replacement was opened")
        assertEquals(1, rt.sessions[1].runs, "no pass ran after the cancellation, only the contract run")
        release.open()
        assertNoAbandonedRunsLeft()
        masker.close()
    }

    @Test
    fun oneConfiguredThreadUsesOneSessionAndTwoOrMoreSplitThem() {
        assertEquals(2, FaceMasker.leadThreads(FaceMaskerConfig()))
        assertEquals(2, FaceMasker.secondThreads(FaceMaskerConfig()))
        assertEquals(1, FaceMasker.leadThreads(FaceMaskerConfig(onnxThreads = 1)))
        assertEquals(0, FaceMasker.secondThreads(FaceMaskerConfig(onnxThreads = 1)))
        assertEquals(2, FaceMasker.leadThreads(FaceMaskerConfig(onnxThreads = 3)))
        assertEquals(1, FaceMasker.secondThreads(FaceMaskerConfig(onnxThreads = 3)))
        assertEquals(2, FaceMasker.leadThreads(FaceMaskerConfig(onnxThreads = 4)))
        assertEquals(2, FaceMasker.secondThreads(FaceMaskerConfig(onnxThreads = 4)))
        val threads = ArrayList<Int>()
        val rt = ModelRuntime { m, t -> FakeSession(OneFaceRunner(), m).also { threads += t } }
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(onnxThreads = 1), rt).use { it.detect(image()) }
        assertEquals(listOf(1), threads, "one session with the one thread")
        threads.clear()
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), rt).use { it.detect(image()) }
        assertEquals(listOf(2, 2), threads, "two sessions of 2 threads")
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
    }
}
