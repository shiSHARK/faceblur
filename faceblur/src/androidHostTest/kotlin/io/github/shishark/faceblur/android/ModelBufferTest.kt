// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The model's direct buffer (ADR 0012 decision 8, ST-049 AC1): exactly the verified bytes, direct, read-only, position
 * 0 and limit = size; the source array is not kept. On the host the real shared-memory copy cannot run (Robolectric's
 * shared-memory shadow does not work on this JDK): the choice between it and the `allocateDirect` fallback is tested
 * with a stand-in copy, and the shared-memory path itself, and whether it stays off the Java heap, run in the device
 * test (`ModelFileDeviceTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelBufferTest {
    @After
    fun restore() {
        ModelBuffer.sharedMemoryAvailable = true
    }

    @Test
    fun theFallbackPathHoldsExactlyTheBytesReadOnly() {
        ModelBuffer.sharedMemoryAvailable = false
        val bytes = Random(7).nextBytes(100_003)
        val buffer = ModelBuffer.of(bytes)
        bytes.fill(0) // the buffer is a copy
        assertTrue(buffer.isDirect, "the runtime needs a direct buffer")
        assertTrue(buffer.isReadOnly, "the runtime never gets a writable view")
        assertEquals(0, buffer.position())
        assertEquals(100_003, buffer.limit())
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        assertContentEquals(Random(7).nextBytes(100_003), copy)
        assertFailsWith<ReadOnlyBufferException> { buffer.duplicate().put(0, 1) }
        // Each session gets its own duplicate: positions are independent.
        val a = buffer.duplicate()
        a.get(ByteArray(10))
        assertEquals(0, buffer.duplicate().position())
    }

    @Test
    fun theSharedMemoryCopyIsUsedWhenItIsAvailable() {
        val region = ByteBuffer.allocateDirect(3).apply {
            put(byteArrayOf(1, 2, 3))
            flip()
        }.asReadOnlyBuffer()
        assertSame(region, ModelBuffer.of(byteArrayOf(1, 2, 3)) { region })
    }

    @Test
    fun aSharedMemoryCopyThatFailsOrCannotBeMadeReadOnlyFallsBackToTheSameBytes() {
        val failing: (ByteArray) -> ByteBuffer? = { throw ErrnoException("memfd_create", OsConstants.ENOMEM) }
        val notReadOnly: (ByteArray) -> ByteBuffer? = { null }
        for (copy in listOf(failing, notReadOnly)) {
            val buffer = ModelBuffer.of(byteArrayOf(4, 5, 6), copy)
            assertTrue(buffer.isDirect && buffer.isReadOnly)
            assertContentEquals(byteArrayOf(4, 5, 6), ByteArray(3).also { buffer.duplicate().get(it) })
        }
    }

    /** A copy of the wrong shape is never handed to the runtime: the fallback holds the same bytes instead. */
    @Test
    fun aSharedMemoryCopyOfTheWrongShapeIsNotUsed() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        fun exact(): ByteBuffer = ByteBuffer.allocateDirect(4).apply {
            put(bytes)
            flip()
        }
        val shapes: Map<String, ByteBuffer> =
            mapOf(
                "writable" to exact(),
                "not direct" to ByteBuffer.wrap(bytes.copyOf()).asReadOnlyBuffer(),
                "too short" to ByteBuffer.allocateDirect(3).asReadOnlyBuffer(),
                "too long" to ByteBuffer.allocateDirect(5).asReadOnlyBuffer(),
                "not at position 0" to ByteBuffer.allocateDirect(5).apply {
                    put(0)
                    put(bytes)
                    flip()
                    position(1)
                }.asReadOnlyBuffer(),
            )
        for ((name, wrong) in shapes) {
            val buffer = ModelBuffer.of(bytes) { wrong }
            assertTrue(buffer !== wrong, "$name: not used")
            assertTrue(buffer.isDirect && buffer.isReadOnly, name)
            assertEquals(0, buffer.position(), name)
            assertContentEquals(bytes, ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }, name)
        }
        // Control: the exact shape, read-only, is used.
        val right = exact().asReadOnlyBuffer()
        assertSame(right, ModelBuffer.of(bytes) { right })
    }

    @Test
    fun withSharedMemoryOffTheCopyIsNotTried() {
        ModelBuffer.sharedMemoryAvailable = false
        var tried = false
        val buffer =
            ModelBuffer.of(byteArrayOf(7)) {
                tried = true
                null
            }
        assertEquals(false, tried)
        assertEquals(1, buffer.remaining())
    }
}
