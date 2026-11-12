// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.internal.runtime.SessionRunFailed
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The runtime-independent rules of the Android model session (SessionRules.kt): the timed pass, the reused input
 * buffer and the conversions of the runtime's tensor descriptions and outputs. The ONNX Runtime calls that use them
 * run in the device tests.
 */
class SessionRulesTest {
    private class Calls {
        val log = ArrayList<String>()

        @Synchronized
        fun add(s: String) {
            log += s
        }
    }

    @Test
    fun aPassWithoutLimitReturnsItsResultAndReleasesOnce() {
        val calls = Calls()
        val r = timedPass(0, { calls.add("terminate") }, { calls.add("release") }) { "out" }
        assertEquals("out", r)
        assertEquals(listOf("release"), calls.log)
    }

    @Test
    fun aRefusedPassIsRunFailedAfterTheRelease() {
        val calls = Calls()
        assertFailsWith<SessionRunFailed> {
            timedPass<String>(10_000, { calls.add("terminate") }, { calls.add("release") }) { null }
        }
        assertEquals(listOf("release"), calls.log)
    }

    @Test
    fun anExceptionOfThePassPassesUnchangedAfterTheRelease() {
        val calls = Calls()
        val boom = IllegalStateException("boom")
        val e =
            assertFailsWith<IllegalStateException> {
                timedPass<String>(10_000, { calls.add("terminate") }, { calls.add("release") }) { throw boom }
            }
        assertSame(boom, e)
        assertEquals(listOf("release"), calls.log)
    }

    @Test
    fun aPassThatReachedItsLimitIsATimeoutEvenWithAResult() {
        val calls = Calls()
        val terminated = CountDownLatch(1)
        assertFailsWith<SessionTimeout> {
            timedPass(
                timeoutMillis = 20,
                terminate = {
                    calls.add("terminate")
                    terminated.countDown()
                },
                release = { calls.add("release") },
            ) {
                // The runtime finishes the pass anyway: its output must not be used.
                assertTrue(terminated.await(5, TimeUnit.SECONDS), "the limit fires during the pass")
                "late output"
            }
        }
        assertEquals(listOf("terminate", "release"), calls.log, "terminate first, release last")
    }

    @Test
    fun aTerminateThatFailsIsStillATimeout() {
        val fired = CountDownLatch(1)
        assertFailsWith<SessionTimeout> {
            timedPass(
                timeoutMillis = 20,
                terminate = {
                    fired.countDown()
                    throw IllegalStateException("terminate failed")
                },
                release = {},
            ) {
                assertTrue(fired.await(5, TimeUnit.SECONDS))
                "late output"
            }
        }
    }

    @Test
    fun theInputBufferIsDirectNativeOrderAndReusedWhileTheSizeStays() {
        val input = InputBuffer()
        val a = input.fill(floatArrayOf(1f, 2f, 3f))
        assertTrue(a.isDirect, "the runtime wraps a direct buffer without a copy")
        assertEquals(ByteOrder.nativeOrder(), a.order())
        assertEquals(0, a.position())
        assertEquals(3, a.limit())
        val back = FloatArray(3).also { a.duplicate().get(it) }
        assertContentEquals(floatArrayOf(1f, 2f, 3f), back)

        val b = input.fill(floatArrayOf(4f, 5f, 6f))
        assertSame(a, b, "same size: the buffer is reused")
        assertEquals(0, b.position())
        assertContentEquals(floatArrayOf(4f, 5f, 6f), FloatArray(3).also { b.duplicate().get(it) })

        val c = input.fill(floatArrayOf(7f, 8f))
        assertNotSame(a, c, "another size: a new buffer")
        assertEquals(2, c.limit())
        assertContentEquals(floatArrayOf(7f, 8f), FloatArray(2).also { c.duplicate().get(it) })
    }

    @Test
    fun onlyAFloatOutputWithinTheLimitIsRead() {
        var reads = 0
        val read = {
            reads++
            floatArrayOf(0.5f, 0.25f)
        }
        val ok = passOutput("ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT", longArrayOf(1, 2), true, 2, 2, read)
        assertEquals(1, ok.elementType)
        assertContentEquals(longArrayOf(1, 2), ok.shape)
        assertContentEquals(floatArrayOf(0.5f, 0.25f), ok.data)
        assertEquals(1, reads)

        val tooMany = passOutput("ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT", longArrayOf(1, 3), true, 3, 2, read)
        assertNull(tooMany.data, "above the element limit: not read")
        val unknownCount = passOutput("ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT", longArrayOf(-1), true, -1, 2, read)
        assertNull(unknownCount.data, "a negative element count is never read")
        val notFloat = passOutput("ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64", longArrayOf(2), false, 2, 2, read)
        assertNull(notFloat.data)
        assertEquals(7, notFloat.elementType)
        assertEquals(1, reads, "no read for refused outputs")
    }

    @Test
    fun tensorDescriptionsKeepNamesAndTurnDynamicDimensionsIntoNull() {
        val t = sessionTensor("input", "ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT", longArrayOf(1, 3, -1, 640))
        assertEquals("input", t.name)
        assertEquals(1, t.elementType)
        assertEquals(listOf(1L, 3L, null, 640L), t.shape)
        val opaque = sessionTensor("map", null, null)
        assertNull(opaque.elementType, "not a tensor: no element type")
        assertNull(opaque.shape)
    }

    /** OrtSessions' close after a failed session start: a close that works is called once and throws nothing. */
    @Test
    fun closeAfterFailureClosesOnce() {
        var closed = 0
        closeAfterFailure { closed++ }
        assertEquals(1, closed)
    }

    /** A close that fails (not fatally) is dropped, so the original failure stays the one reported. */
    @Test
    fun aNonFatalCloseFailureIsDroppedAndTheOriginalFailureStays() {
        val original = IllegalArgumentException("original")
        val thrown = assertFailsWith<IllegalArgumentException> {
            try {
                throw original
            } catch (e: IllegalArgumentException) {
                closeAfterFailure { throw IllegalStateException("close failed") }
                throw e
            }
        }
        assertSame(original, thrown)
        assertEquals(0, thrown.suppressed.size, "the close failure is not attached")
        closeAfterFailure { throw RuntimeException("close failed") }
        closeAfterFailure { throw Exception("checked close failure") }
    }

    /** A fatal error of the JVM while closing is never swallowed. */
    @Test
    fun aFatalCloseFailurePasses() {
        val fatal = OutOfMemoryError("planted")
        assertSame(fatal, assertFailsWith<OutOfMemoryError> { closeAfterFailure { throw fatal } })
        val overflow = StackOverflowError("planted")
        assertSame(overflow, assertFailsWith<StackOverflowError> { closeAfterFailure { throw overflow } })
    }
}
