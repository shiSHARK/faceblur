// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `close()` from another thread while a call is inside the runtime, and the JVM fatal-error rule for the release. */
class CloseConcurrencyTest {
    private class BlockingRunner : TensorRunner by OneFaceRunner() {
        val inRun = AtomicBoolean(false)
        val closedWhileRunning = AtomicBoolean(false)
        val closes = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        /** Off during the contract run at creation. */
        @Volatile
        var armed = false

        override fun run(input: FloatArray): Map<String, FloatArray> {
            if (!armed) return OneFaceRunner().run(input)
            inRun.set(true)
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            Thread.sleep(5) // stay inside "the runtime" after the gate opens, where an early release would land
            val result = OneFaceRunner().run(input)
            inRun.set(false)
            return result
        }

        override fun close() {
            if (inRun.get()) closedWhileRunning.set(true)
            closes.incrementAndGet()
        }
    }

    @Test
    fun closeFromAnotherThreadDuringACallDefersTheRelease() {
        repeat(20) {
            val runner = BlockingRunner()
            val m = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
            runner.armed = true
            val error = AtomicReference<Throwable?>(null)
            val faces = AtomicInteger(-1)
            val t =
                Thread {
                    try {
                        faces.set(m.detect(IntArrayPixels(640, 480, gradientPixels(640, 480))).faces.size)
                    } catch (e: Throwable) {
                        error.set(e)
                    }
                }
            t.start()
            assertTrue(runner.entered.await(10, TimeUnit.SECONDS))
            m.close() // returns at once, without releasing
            assertEquals(0, runner.closes.get(), "released while a call was running")
            assertFailsWith<FaceMaskException.Closed> { m.detect(IntArrayPixels(64, 48)) }
            runner.gate.countDown()
            t.join(10_000)
            assertNull(error.get(), "the running call failed")
            assertEquals(1, faces.get(), "the running call returned its result")
            assertFalse(runner.closedWhileRunning.get(), "the session was closed while the runtime was in use")
            assertEquals(1, runner.closes.get(), "released exactly once, after the call returned")
            m.close()
            assertEquals(1, runner.closes.get())
        }
    }

    /** ADR 0002 decision 9: a fatal JVM error from the session's close passes unchanged; other errors are absorbed. */
    @Test
    fun aFatalErrorFromTheSessionClosePassesUnchanged() {
        val fatal = OutOfMemoryError("x")
        val runner =
            object : TensorRunner by OneFaceRunner() {
                override fun close() = throw fatal
            }
        val m = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
        assertSame(fatal, runCatching { m.close() }.exceptionOrNull())
        m.close() // released once; nothing is retried
        assertFailsWith<FaceMaskException.Closed> { m.detect(IntArrayPixels(64, 48)) }
    }
}
