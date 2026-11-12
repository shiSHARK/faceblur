// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `close()` never throws and releases the runtime session only when no call is running (ADR 0002 decisions 2 and
 * 8); a failure of the session's own close never escapes `close()` and never replaces a call's result.
 */
class CloseReleaseTest {
    /** A runner that can call back into the masker during `run()` and whose `close()` can fail. */
    private class Runner(private val closeFailure: Throwable? = null) : TensorRunner by OneFaceRunner() {
        var masker: FaceMasker? = null
        var closeDuringRun = false
        var inRun = false
        var closes = 0
        var closedWhileRunning = false

        override fun run(input: FloatArray): Map<String, FloatArray> {
            inRun = true
            try {
                if (closeDuringRun) masker?.close() // as another thread would, while the call is inside the runtime
                return OneFaceRunner().run(input)
            } finally {
                inRun = false
            }
        }

        override fun close() {
            if (inRun) closedWhileRunning = true
            closes++
            closeFailure?.let { throw it }
        }
    }

    private fun masker(runner: Runner) =
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner)).also { runner.masker = it }

    private fun image() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    @Test
    fun closeDuringACallReleasesOnlyWhenTheCallReturns() {
        val runner = Runner().apply { closeDuringRun = true }
        val m = masker(runner)
        val result = m.detect(image())
        assertEquals(1, result.faces.size, "the running call completes normally")
        assertTrue(!runner.closedWhileRunning, "the session was not released while the runtime was in use")
        assertEquals(1, runner.closes, "released once, when the call returned")
        assertFailsWith<FaceMaskException.Closed> { m.detect(image()) }
        m.close()
        assertEquals(1, runner.closes, "a second close changes nothing")
    }

    @Test
    fun aFailingSessionCloseNeverEscapesClose() {
        for (failure in listOf<Throwable>(IllegalStateException("x"), AssertionError("x"), RuntimeException("x"))) {
            val runner = Runner(failure)
            val m = masker(runner)
            m.close() // must not throw
            assertEquals(1, runner.closes, "${failure::class.simpleName}")
            m.close()
            assertEquals(1, runner.closes, "not retried")
            assertFailsWith<FaceMaskException.Closed> { m.detect(image()) }
        }
    }

    @Test
    fun aFailingSessionCloseNeverReplacesTheResultOfTheRunningCall() {
        for (failure in listOf<Throwable>(IllegalStateException("x"), AssertionError("x"))) {
            val runner = Runner(failure).apply { closeDuringRun = true }
            val m = masker(runner)
            val result = m.maskInPlace(image())
            assertEquals(1, result.faces.size, "${failure::class.simpleName}: the call's result is returned")
            assertEquals(1, runner.closes)
            assertFailsWith<FaceMaskException.Closed> { m.detect(image()) }
        }
        // The same when the running call fails: its own typed error is reported, not the close failure.
        val failing =
            object : TensorRunner by OneFaceRunner(nan = true) {
                var masker: FaceMasker? = null
                var closes = 0

                override fun run(input: FloatArray): Map<String, FloatArray> {
                    masker?.close()
                    // Valid output for the contract run at creation, NaN scores afterwards.
                    return OneFaceRunner(nan = masker != null).run(input)
                }

                override fun close() {
                    closes++
                    throw IllegalStateException("x")
                }
            }
        val m = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(failing))
        failing.masker = m
        assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.detect(image()) }
        assertEquals(1, failing.closes)
    }
}
