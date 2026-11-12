// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The run timeout of the Android runner (ADR 0012 decision 6) and its helpers, without ONNX Runtime: the timer
 * terminates a run that reaches its limit, never terminates after the run's options were released, and the
 * thread plan follows the benchmark-proven defaults. The real runtime is exercised by the device test.
 */
class RunTimerTest {
    @Test
    fun aRunThatReachesItsLimitIsTerminatedOnce() {
        val terminated = CountDownLatch(1)
        val calls = AtomicInteger()
        val timer = RunTimer.start(20) {
            calls.incrementAndGet()
            terminated.countDown()
        }
        assertTrue(terminated.await(5, TimeUnit.SECONDS), "terminate was called")
        assertTrue(timer.fired)
        var released = false
        timer.finish { released = true }
        assertTrue(released)
        assertEquals(1, calls.get())
    }

    @Test
    fun aRunThatEndsInTimeIsNeverTerminated() {
        val calls = AtomicInteger()
        val timer = RunTimer.start(200) { calls.incrementAndGet() }
        timer.finish { }
        Thread.sleep(400)
        assertFalse(timer.fired)
        assertEquals(0, calls.get())
    }

    @Test
    fun noLimitMeansNoTimer() {
        val calls = AtomicInteger()
        val timer = RunTimer.start(0) { calls.incrementAndGet() }
        Thread.sleep(50)
        timer.finish { }
        assertFalse(timer.fired)
        assertEquals(0, calls.get())
    }

    @Test
    fun terminateAndReleaseNeverOverlapAndTerminateNeverFollowsRelease() {
        // Many timers racing their release at about the limit: a terminate either completes before the release
        // starts, or does not happen at all.
        repeat(300) { i ->
            val released = AtomicBoolean(false)
            val inTerminate = AtomicBoolean(false)
            val violation = AtomicBoolean(false)
            val timer = RunTimer.start(1) {
                inTerminate.set(true)
                if (released.get()) violation.set(true)
                Thread.yield()
                inTerminate.set(false)
            }
            if (i % 2 == 0) Thread.sleep(1)
            timer.finish {
                if (inTerminate.get()) violation.set(true)
                released.set(true)
            }
            Thread.sleep(2)
            assertFalse(violation.get(), "terminate overlapped or followed the release (round $i)")
        }
    }

    @Test
    fun theThreadPlanFollowsTheBenchmarkDefaults() {
        assertEquals(4, xnnpackThreads(0, 8), "automatic: at most 4")
        assertEquals(2, xnnpackThreads(0, 2), "automatic: at most the processor count")
        assertEquals(1, xnnpackThreads(0, 0), "automatic: at least 1")
        assertEquals(6, xnnpackThreads(6, 8), "a configured value is used as is")
        assertEquals(minOf(4, Runtime.getRuntime().availableProcessors()), xnnpackThreads(0))
    }

    @Test
    fun runtimeElementTypesMapToOnnxCodes() {
        assertEquals(1, onnxCode("ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT"))
        assertEquals(7, onnxCode("ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64"))
        assertEquals(11, onnxCode("ONNX_TENSOR_ELEMENT_DATA_TYPE_DOUBLE"))
        assertEquals(-1, onnxCode("ONNX_TENSOR_ELEMENT_DATA_TYPE_SOMETHING_NEW"))
    }
}
