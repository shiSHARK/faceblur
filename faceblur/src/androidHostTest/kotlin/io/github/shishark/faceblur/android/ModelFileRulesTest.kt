// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.model.ModelLoader
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model-file read rules (ADR 0012 decision 4, step 2) against scripted descriptor calls. Robolectric supplies
 * `OsConstants` (flags, errno values, `S_ISREG`); the real `android.system.Os` calls run in the device test
 * (`ModelFileDeviceTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelFileRulesTest {
    private val regular get() = OsConstants.S_IFREG or 0x1A4 // rw-r--r--

    /** A scripted descriptor: the file content, what each fstat reports and which call fails. */
    private class Script(
        val content: ByteArray,
        var mode: Int,
        val chunk: Int = Int.MAX_VALUE,
        val openErrno: Int? = null,
        val failStat: Int = 0, // fail the n-th stat (1-based), 0 = never
        val failClearNonBlocking: Boolean = false,
        val failRead: Boolean = false,
        val failClose: Boolean = false,
        val extra: ByteArray = ByteArray(0), // bytes that appear after the size reported by the first stat
        val secondStat: ((FileFacts) -> FileFacts)? = null,
    ) : DescriptorCalls<Int> {
        val calls = ArrayList<String>()
        var openFlags = 0
        private var pos = 0
        private var stats = 0

        override fun open(path: String, flags: Int): Int {
            calls += "open"
            openFlags = flags
            if (openErrno != null) throw DescriptorFailed(openErrno)
            return 7
        }

        override fun stat(fd: Int): FileFacts {
            calls += "stat"
            stats++
            if (stats == failStat) throw DescriptorFailed(OsConstants.EIO)
            val facts = FileFacts(mode, content.size.toLong(), 42, 3)
            return if (stats > 1) secondStat?.invoke(facts) ?: facts else facts
        }

        override fun clearNonBlocking(fd: Int) {
            calls += "clearNonBlocking"
            if (failClearNonBlocking) throw DescriptorFailed(OsConstants.EBADF)
        }

        override fun read(fd: Int, into: ByteArray, offset: Int, count: Int): Int {
            calls += "read"
            if (failRead) throw DescriptorFailed(null)
            val all = content + extra
            val n = minOf(count, chunk, all.size - pos)
            if (n <= 0) return 0
            all.copyInto(into, offset, pos, pos + n)
            pos += n
            return n
        }

        override fun close(fd: Int) {
            calls += "close"
            if (failClose) throw DescriptorFailed(OsConstants.EIO)
        }
    }

    private fun bytes(n: Int) = Random(n).nextBytes(n)

    @Test
    fun aRegularFileIsReadExactlyWithTheSafeOpenFlagsAndClosed() {
        val s = Script(bytes(1000), regular, chunk = 300)
        assertContentEquals(bytes(1000), ModelFileRules.read("/data/model.onnx", 1000, s))
        for (flag in listOf(OsConstants.O_NOFOLLOW, OsConstants.O_NONBLOCK, OsConstants.O_CLOEXEC)) {
            assertEquals(flag, s.openFlags and flag, "open flag $flag")
        }
        assertEquals(OsConstants.O_RDONLY, s.openFlags and OsConstants.O_ACCMODE, "read-only")
        // 4 chunks of the content, then the probe read that finds the end; stat before and after.
        assertEquals(
            listOf("open", "stat", "clearNonBlocking", "read", "read", "read", "read", "read", "stat", "close"),
            s.calls,
        )
    }

    @Test
    fun anEmptyFileGivesNoBytes() {
        assertEquals(0, ModelFileRules.read("m", 10, Script(ByteArray(0), regular)).size)
    }

    @Test
    fun aSymbolicLinkIsNotARegularFile() {
        val s = Script(bytes(10), regular, openErrno = OsConstants.ELOOP)
        val e = assertFailsWith<FaceMaskException.ModelRejected> { ModelFileRules.read("/secret/link", 100, s) }
        assertEquals(listOf(ModelLoader.NOT_REGULAR_FILE), e.findings)
        assertEquals(listOf("open"), s.calls, "nothing to close: the open failed")
    }

    @Test
    fun openFailuresAreIoFailedWithoutThePath() {
        val missing =
            assertFailsWith<FaceMaskException.IoFailed> {
                ModelFileRules.read("/secret/a", 100, Script(bytes(1), regular, openErrno = OsConstants.ENOENT))
            }
        assertTrue(missing.message!!.contains("does not exist"), missing.message)
        val denied =
            assertFailsWith<FaceMaskException.IoFailed> {
                ModelFileRules.read("/secret/a", 100, Script(bytes(1), regular, openErrno = OsConstants.EACCES))
            }
        assertTrue(denied.message!!.contains("could not be read"), denied.message)
        assertFalse(missing.message!!.contains("secret") || denied.message!!.contains("secret"))
        assertEquals(null, missing.cause)
    }

    @Test
    fun aDescriptorThatIsNotARegularFileIsRefusedBeforeAnyRead() {
        val s = Script(bytes(10), OsConstants.S_IFIFO or 0x1A4)
        val e = assertFailsWith<FaceMaskException.ModelRejected> { ModelFileRules.read("fifo", 100, s) }
        assertEquals(listOf(ModelLoader.NOT_REGULAR_FILE), e.findings)
        assertEquals(listOf("open", "stat", "close"), s.calls, "O_NONBLOCK stays, nothing read, closed")
    }

    @Test
    fun aFileAboveTheLimitIsRefusedWithoutReadingIt() {
        val s = Script(bytes(101), regular)
        val e = assertFailsWith<FaceMaskException.ModelRejected> { ModelFileRules.read("big", 100, s) }
        assertEquals(listOf(ModelLoader.TOO_LARGE), e.findings)
        assertEquals(listOf("open", "stat", "close"), s.calls)
    }

    /**
     * Growth (a probe byte) and shrinking (another size in the second `fstat`) are refused; another inode or device in
     * the second `fstat` (which cannot happen on the same descriptor, kept as defence in depth) is refused as well.
     */
    @Test
    fun aFileThatGrewOrShrankDuringTheReadOrReportsAnotherFileIsRefused() {
        val grew = Script(bytes(50), regular, extra = byteArrayOf(1))
        val shrank = Script(bytes(50), regular, secondStat = { FileFacts(it.mode, it.size - 1, it.inode, it.device) })
        val otherInode = Script(bytes(50), regular, secondStat = { FileFacts(it.mode, it.size, 43, it.device) })
        val otherDevice = Script(bytes(50), regular, secondStat = { FileFacts(it.mode, it.size, it.inode, 4) })
        for (s in listOf(grew, shrank, otherInode, otherDevice)) {
            val e = assertFailsWith<FaceMaskException.IoFailed> { ModelFileRules.read("m", 100, s) }
            assertTrue(e.message!!.contains("changed while it was read"), e.message)
            assertEquals("close", s.calls.last())
        }
    }

    @Test
    fun aFileThatEndsEarlyIsRefused() {
        // fstat reports 50 bytes, the read finds only 20 (the file was truncated after the first fstat).
        val s =
            object : DescriptorCalls<Int> by Script(bytes(20), regular) {
                override fun stat(fd: Int) = FileFacts(regular, 50, 42, 3)
            }
        val e = assertFailsWith<FaceMaskException.IoFailed> { ModelFileRules.read("m", 100, s) }
        assertTrue(e.message!!.contains("changed while it was read"), e.message)
    }

    @Test
    fun descriptorFailuresAreIoFailedAndTheDescriptorIsStillClosed() {
        val cases =
            listOf(
                Script(bytes(10), regular, failStat = 1),
                Script(bytes(10), regular, failStat = 2),
                Script(bytes(10), regular, failClearNonBlocking = true),
                Script(bytes(10), regular, failRead = true),
            )
        for (s in cases) {
            val e = assertFailsWith<FaceMaskException.IoFailed> { ModelFileRules.read("/secret/m", 100, s) }
            assertTrue(e.message!!.contains("could not be read"), e.message)
            assertFalse(e.message!!.contains("secret"))
            assertEquals(null, e.cause, "the platform failure is not chained")
            assertEquals("close", s.calls.last())
        }
    }

    @Test
    fun aFailedCloseLosesNothing() {
        assertContentEquals(bytes(10), ModelFileRules.read("m", 100, Script(bytes(10), regular, failClose = true)))
    }
}
