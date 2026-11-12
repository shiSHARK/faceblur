// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.os.Debug
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.model.ModelLoader
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android model-file read rules (ADR 0012 decision 4 step 2, on the descriptor) and the model buffer outside the
 * Java heap (ADR 0012 decision 8) with the platform's real `Os` and `SharedMemory`.
 */
@RunWith(AndroidJUnit4::class)
class ModelFileDeviceTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        DeviceFixtures.logHeader()
        dir = DeviceFixtures.scratch("model-files")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun rejected(path: String, max: Long = 1_000_000L): List<String> =
        assertFailsWith<FaceMaskException.ModelRejected> { AndroidModelFileReader.read(path, max) }.findings

    @Test
    fun aRegularFileIsReadExactlyUpToTheCap() {
        val bytes = Random(3).nextBytes(10_000)
        val f = File(dir, "model.bin").apply { writeBytes(bytes) }
        assertContentEquals(bytes, AndroidModelFileReader.read(f.path, 10_000))
        assertContentEquals(bytes, AndroidModelFileReader.read(f.path, 50_000))
        assertEquals(listOf(ModelLoader.TOO_LARGE), rejected(f.path, 9_999))
        assertContentEquals(
            ByteArray(0),
            AndroidModelFileReader.read(
                File(dir, "empty").apply {
                    writeBytes(ByteArray(0))
                }.path,
                10,
            ),
        )
    }

    @Test
    fun linksDirectoriesAndFifosAreRefusedWithoutBlocking() {
        val target = File(dir, "model.bin").apply { writeBytes(ByteArray(16)) }
        val link = File(dir, "link.bin")
        Os.symlink(target.path, link.path)
        assertEquals(listOf(ModelLoader.NOT_REGULAR_FILE), rejected(link.path))
        assertEquals(listOf(ModelLoader.NOT_REGULAR_FILE), rejected(dir.path))
        val fifo = File(dir, "fifo")
        Os.mkfifo(fifo.path, 384) // 0600
        assertEquals(listOf(ModelLoader.NOT_REGULAR_FILE), rejected(fifo.path))
    }

    @Test
    fun aMissingFileIsIoFailedWithoutThePath() {
        val e =
            assertFailsWith<FaceMaskException.IoFailed> {
                AndroidModelFileReader.read(File(dir, "secret-name.onnx").path, 10)
            }
        assertFalse(e.message!!.contains("secret-name"), e.message)
    }

    @Test
    fun bothBufferPathsHoldExactlyTheBytesReadOnly() {
        for (shared in listOf(true, false)) {
            ModelBuffer.sharedMemoryAvailable = shared
            try {
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
                assertFailsWith<java.nio.ReadOnlyBufferException> { buffer.duplicate().put(0, 1) }
                buffer.duplicate().get(ByteArray(10))
                assertEquals(0, buffer.duplicate().position(), "each session gets its own duplicate")
            } finally {
                ModelBuffer.sharedMemoryAvailable = true
            }
        }
    }

    @Test
    fun theModelBufferLivesOutsideTheJavaHeap() {
        val size = 40 * 1024 * 1024
        val bytes = Random(5).nextBytes(size)
        fun heapUsed(): Long {
            repeat(3) {
                System.gc()
                System.runFinalization()
            }
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }
        ModelBuffer.sharedMemoryAvailable = true
        val before = heapUsed()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val shared = ModelBuffer.of(bytes)
        val sharedDelta = heapUsed() - before
        assertTrue(shared.isDirect && shared.isReadOnly)
        assertEquals(size, shared.remaining())
        val probe = ByteArray(4096)
        shared.duplicate().apply { position(size - 4096) }.get(probe)
        assertContentEquals(bytes.copyOfRange(size - 4096, size), probe)
        try {
            ModelBuffer.sharedMemoryAvailable = false
            val before2 = heapUsed()
            val direct = ModelBuffer.of(bytes)
            val directDelta = heapUsed() - before2
            assertEquals(size, direct.remaining())
            Log.i(
                DeviceFixtures.TAG,
                "model buffer of %d MiB (debug build): Java heap +%.1f MiB with shared memory, +%.1f MiB with allocateDirect; native heap +%.1f MiB"
                    .format(
                        size / 1048576,
                        sharedDelta / 1048576.0,
                        directDelta / 1048576.0,
                        (Debug.getNativeHeapAllocatedSize() - nativeBefore) / 1048576.0,
                    ),
            )
            assertTrue(sharedDelta < size / 4, "the shared-memory buffer is not on the Java heap: +$sharedDelta bytes")
        } finally {
            ModelBuffer.sharedMemoryAvailable = true
        }
    }
}
