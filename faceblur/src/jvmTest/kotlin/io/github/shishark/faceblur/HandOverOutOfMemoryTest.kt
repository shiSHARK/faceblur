// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JVM case behind the fail-closed hand-over: an [OutOfMemoryError] in the caller's wait for a pass reaches the app
 * (fatal errors pass), and an app that catches it and keeps using the masker gets a new session and correct output;
 * the run still going is never shared with a later one.
 */
class HandOverOutOfMemoryTest {
    private val release = Release()
    private val threads = FaultyThreads()

    @AfterTest
    fun cleanUp() {
        release.open()
        assertNoAbandonedRunsLeft()
    }

    @Test
    fun anOutOfMemoryErrorInTheWaitReachesTheAppAndTheNextCallGetsANewSession() {
        val model = testYuNetModel()
        val sessions = ArrayList<BlockingSession>()
        // Session 0 blocks in its first pass after the contract run; later sessions never block.
        val runtime = ModelRuntime { m, _ ->
            val blockFrom = if (sessions.isEmpty()) 2 else Int.MAX_VALUE
            BlockingSession(FakeSession(OneFaceRunner(), m), release, blockFrom).also { sessions += it }
        }
        val limits = RunLimits(contractMillis = 1_000L, minPassMillis = 300L, openMillis = 500L, threads = threads)
        val masker = FaceMasker.forTest(model, FaceMaskerConfig(), runtime, runLimits = limits)
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))

        threads.waitError = { OutOfMemoryError("test: no memory for the wait") }
        threads.wait = FaultyThreads.Wait.THROW
        threads.beforeWait = { awaitBlocked(sessions[0]) }
        assertFailsWith<OutOfMemoryError> { masker.maskInPlace(img) }
        assertEquals(1, AbandonedRuns.count())

        val result = masker.maskInPlace(img)
        assertEquals(2, sessions.size, "the next call opened a new session")
        val reference = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val expected = FaceMasker.forTest(model, FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use {
            it.maskInPlace(reference)
        }
        assertEquals(expected.masks, result.masks)
        assertContentEquals(reference.data, img.data, "the same output as a normal run")

        release.open()
        assertTrue(sessions[0].awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
        assertEquals(2, sessions[0].runs, "the session of the failed wait never ran again")
        masker.close()
    }
}
