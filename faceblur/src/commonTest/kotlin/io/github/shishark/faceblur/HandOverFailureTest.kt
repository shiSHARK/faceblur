// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.DeadlineSession
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every exit of a run or a session creation that is not its normal end fails closed: when the start or the wait
 * throws on the caller's thread, or the wait wakes before the run ended, the session is retired and never used again
 * (a late signal of that run is never read, no later run shares the session with it), the next call on the masker
 * gets a new session and correct output, and the count of abandoned runs stays exact. Also the race in which a run or
 * a creation ends just at its deadline.
 */
class HandOverFailureTest {
    private val release = Release()
    private val threads = FaultyThreads()
    private val model = testYuNetModel()

    @AfterTest
    fun cleanUp() {
        release.open()
        threads.releaseHandedOver()
        assertNoAbandonedRunsLeft()
    }

    /** A session over the one-face runner that blocks from run [blockFrom] until [release] opens. */
    private fun stuck(blockFrom: Int) = BlockingSession(FakeSession(OneFaceRunner(), model), release, blockFrom)

    private fun DeadlineSession.pass(limit: Long = 5_000L) =
        run(FloatArray(640 * 640 * 3), longArrayOf(1, 3, 640, 640), listOf("cls_8"), 1L shl 22, limit)

    @Test
    fun aWaitThatThrowsWhileTheRunIsGoingRetiresTheSessionAndTheRunClosesItWhenItReturns() {
        val inner = stuck(blockFrom = 1)
        val session = DeadlineSession(inner, threads)
        threads.wait = FaultyThreads.Wait.THROW
        threads.beforeWait = { awaitBlocked(inner) }
        assertFailsWith<WaitFailed> { session.pass() }
        assertTrue(session.retired, "a failed wait retires the session")
        assertEquals(1, AbandonedRuns.count(), "the run still going is counted once")
        assertEquals(0, inner.closes, "the session is left to its pass thread while its run is going")

        assertFailsWith<SessionTimeout> { session.pass() }
        assertEquals(1, inner.runs, "a retired session never runs again")
        session.close()
        assertEquals(0, inner.closes, "close of the retired session leaves the inner one to its pass thread")

        release.open()
        assertTrue(inner.awaitClosed(10_000L), "the pass thread closes the session when its run returns")
        assertNoAbandonedRunsLeft()
        assertEquals(1, inner.closes)
    }

    @Test
    fun aWaitThatThrowsAfterTheRunEndedRetiresAndClosesTheSessionAtOnce() {
        val inner = stuck(blockFrom = NEVER)
        val session = DeadlineSession(inner, threads)
        threads.start = FaultyThreads.Start.INLINE
        threads.wait = FaultyThreads.Wait.THROW
        assertFailsWith<WaitFailed> { session.pass() }
        assertTrue(session.retired)
        assertEquals(1, inner.closes, "closed at once: its run has ended")
        assertEquals(0, AbandonedRuns.count(), "nothing stays counted for a run that has ended")
        // The run's signal is still unread; a retired session never waits again, so nothing can read it.
        assertFailsWith<SessionTimeout> { session.pass() }
        assertEquals(1, inner.runs)
        session.close()
        assertEquals(1, inner.closes)
    }

    @Test
    fun aStartThatFailsRetiresTheSessionWithoutCountingARun() {
        val inner = stuck(blockFrom = NEVER)
        val session = DeadlineSession(inner, threads)
        threads.start = FaultyThreads.Start.FAIL
        assertFailsWith<StartFailed> { session.pass() }
        assertTrue(session.retired, "a failed start retires the session")
        assertEquals(0, AbandonedRuns.count(), "a pass that may never run is not counted")
        assertFailsWith<SessionTimeout> { session.pass() }
        assertEquals(0, inner.runs, "the session never ran")
    }

    @Test
    fun aStartThatFailsAfterTheHandOverNeverRunsTheSessionAndItsTaskClosesIt() {
        val inner = stuck(blockFrom = NEVER)
        val session = DeadlineSession(inner, threads)
        threads.start = FaultyThreads.Start.FAIL_AFTER_HAND_OVER
        assertFailsWith<StartFailed> { session.pass() }
        assertTrue(session.retired)
        assertEquals(0, AbandonedRuns.count())
        session.close()
        assertEquals(0, inner.closes, "the handed-over task owns the session")

        threads.releaseHandedOver()
        assertTrue(inner.awaitClosed(10_000L), "the task of the failed start closes the session")
        assertEquals(0, inner.runs, "the task of an abandoned start never runs the session")
        assertEquals(1, inner.closes)
        assertEquals(0, AbandonedRuns.count(), "nothing was given back that was not counted")
    }

    @Test
    fun aWaitThatWakesBeforeTheRunEndedRetiresTheSessionInsteadOfReadingNoOutput() {
        val inner = stuck(blockFrom = 1)
        val session = DeadlineSession(inner, threads)
        threads.wait = FaultyThreads.Wait.WAKE
        threads.beforeWait = { awaitBlocked(inner) }
        assertFailsWith<IllegalStateException> { session.pass() }
        assertTrue(session.retired)
        assertEquals(1, AbandonedRuns.count())
        assertFailsWith<SessionTimeout> { session.pass() }
        assertEquals(1, inner.runs)
        release.open()
        assertTrue(inner.awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
        assertEquals(1, inner.closes)
    }

    @Test
    fun aRunThatEndsJustAtItsDeadlineIsATimeoutAndItsSessionIsClosedAtOnce() {
        val inner = stuck(blockFrom = NEVER)
        val session = DeadlineSession(inner, threads)
        threads.start = FaultyThreads.Start.INLINE
        threads.wait = FaultyThreads.Wait.MISS
        assertFailsWith<SessionTimeout> { session.pass() }
        assertTrue(session.retired)
        assertEquals(1, inner.closes, "closed exactly once, at once")
        assertEquals(0, AbandonedRuns.count(), "back to zero: the run has ended")
        assertFailsWith<SessionTimeout> { session.pass() }
        assertEquals(1, inner.runs, "a retired session never runs again")
        session.close()
        assertEquals(1, inner.closes, "close of the retired session touches nothing")
    }

    @Test
    fun aCreationWhoseWaitThrowsIsAbandonedAndItsLateSessionIsClosedAndNeverUsed() {
        val rt = BlockingRuntime(release, blockOpen = { true })
        threads.wait = FaultyThreads.Wait.THROW
        threads.beforeWait = { awaitCreationStarted(rt) }
        assertFailsWith<WaitFailed> { DeadlineSession.open(5_000L, threads) { rt.open(model, 1) } }
        assertEquals(1, AbandonedRuns.count(), "the creation still going is counted once")

        release.open()
        val late = rt.sessions.single()
        assertTrue(late.awaitClosed(10_000L), "the late session is closed when its creation returns")
        assertNoAbandonedRunsLeft()
        assertEquals(0, late.runs, "the late session never ran")
        assertEquals(1, late.closes)
    }

    @Test
    fun aCreationWhoseStartFailsIsNotCounted() {
        val rt = BlockingRuntime(release, blockOpen = { false })
        threads.start = FaultyThreads.Start.FAIL
        assertFailsWith<StartFailed> { DeadlineSession.open(5_000L, threads) { rt.open(model, 1) } }
        assertEquals(0, AbandonedRuns.count())
        assertEquals(0, rt.opens)

        threads.start = FaultyThreads.Start.FAIL_AFTER_HAND_OVER
        assertFailsWith<StartFailed> { DeadlineSession.open(5_000L, threads) { rt.open(model, 1) } }
        threads.releaseHandedOver()
        pause(200L)
        assertEquals(0, rt.opens, "the task of an abandoned start never creates a session")
        assertEquals(0, AbandonedRuns.count())
    }

    @Test
    fun aCreationThatEndsJustAtItsDeadlineIsATimeoutAndItsSessionIsClosedAtOnce() {
        val rt = BlockingRuntime(release, blockOpen = { false })
        threads.start = FaultyThreads.Start.INLINE
        threads.wait = FaultyThreads.Wait.MISS
        assertFailsWith<SessionTimeout> { DeadlineSession.open(5_000L, threads) { rt.open(model, 1) } }
        val session = rt.sessions.single()
        assertEquals(1, session.closes, "closed exactly once, at once")
        assertEquals(0, session.runs)
        assertEquals(0, AbandonedRuns.count())
    }

    @Test
    fun aFailedWaitInACallLeavesTheMaskerOnANewSessionWithCorrectOutput() {
        val sessions = ArrayList<BlockingSession>()
        // Session 0 blocks in its first pass after the contract run; later sessions never block.
        val runtime = ModelRuntime { m, _ ->
            val i = sessions.size
            BlockingSession(FakeSession(OneFaceRunner(), m), release, if (i == 0) 2 else NEVER).also { sessions += it }
        }
        val limits = RunLimits(contractMillis = 1_000L, minPassMillis = 300L, openMillis = 500L, threads = threads)
        val masker = FaceMasker.forTest(model, FaceMaskerConfig(), runtime, runLimits = limits)
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val before = img.data.copyOf()

        threads.wait = FaultyThreads.Wait.THROW
        threads.beforeWait = { awaitBlocked(sessions[0]) }
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(1, AbandonedRuns.count(), "the run still going is counted once")

        val result = masker.maskInPlace(img)
        assertEquals(2, sessions.size, "the next call opened a new session")
        assertEquals(2, sessions[0].runs, "the session of the failed wait never ran again")
        val reference = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val expected = FaceMasker.forTest(model, FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use {
            it.maskInPlace(reference)
        }
        assertEquals(expected.masks, result.masks)
        assertEquals(1, result.masks.size)
        assertContentEquals(reference.data, img.data, "the same output as a normal run")

        release.open()
        assertTrue(sessions[0].awaitClosed(10_000L), "the abandoned session is closed when its run returns")
        assertNoAbandonedRunsLeft()
        assertEquals(2, sessions[0].runs)
        masker.close()
        assertEquals(1, sessions[0].closes, "closing the masker does not touch the abandoned session")
        assertEquals(1, sessions[1].closes)
        assertFalse(sessions.size > 2)
    }

    /** Waits up to 10 s until a creation of [rt] has started (it records its session before it blocks). */
    private fun awaitCreationStarted(rt: BlockingRuntime) {
        var waited = 0L
        while (rt.sessions.isEmpty() && waited < 10_000L) {
            pause(5L)
            waited += 5L
        }
        assertEquals(1, rt.sessions.size, "the creation has started")
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
    }
}
