// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.O_ACCMODE
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.fcntl
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.symlink
import platform.posix.unlink
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ModelSource.fromFile` on iOS (`IosModelFileReader`): the model file is opened read-only, with close-on-exec and
 * without following a symbolic link, and nothing is created, truncated or changed. The files live in a folder of this
 * test only, removed afterwards.
 */
class IosModelFileReaderTest {
    private class Stat(val size: Long, val mtimeSec: Long, val mtimeNsec: Long, val mode: Int)

    private fun stat(path: String): Stat? = memScoped {
        val st = alloc<stat>()
        if (lstat(path, st.ptr) != 0) {
            null
        } else {
            Stat(st.st_size, st.st_mtimespec.tv_sec, st.st_mtimespec.tv_nsec, st.st_mode.toInt())
        }
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 0x180) // 0600, this test's own file
        assertTrue(fd >= 0, "test file created")
        try {
            bytes.usePinned { assertEquals(bytes.size.toLong(), write(fd, it.addressOf(0), bytes.size.convert())) }
        } finally {
            close(fd)
        }
    }

    @Test
    fun opensTheModelFileReadOnlyWithoutCreatingTruncatingOrFollowingLinks() {
        val dir = NSTemporaryDirectory() + "faceblur-reader-test-" + NSUUID().UUIDString
        assertEquals(0, mkdir(dir, 0x1C0.convert())) // 0700
        val file = "$dir/model.bin"
        val link = "$dir/link.bin"
        val missing = "$dir/missing.bin"
        val bytes = ByteArray(3000) { (it * 31 + 7).toByte() }
        try {
            writeFile(file, bytes)
            // Owner read-only: an open for writing or truncation would fail with EACCES (and so this test).
            assertEquals(0, chmod(file, 0x100.convert())) // 0400
            val before = stat(file)!!

            var accessMode = -1
            var statusFlags = -1
            var descriptorFlags = -1
            val read = IosModelFileReader.read(file, 10_000L) { fd ->
                statusFlags = fcntl(fd, F_GETFL)
                accessMode = statusFlags and O_ACCMODE
                descriptorFlags = fcntl(fd, F_GETFD)
            }
            assertContentEquals(bytes, read)
            assertEquals(O_RDONLY, accessMode, "opened read-only")
            assertEquals(0, statusFlags and O_APPEND, "no append")
            assertTrue(descriptorFlags >= 0 && (descriptorFlags and FD_CLOEXEC) != 0, "close-on-exec")
            val after = stat(file)!!
            assertEquals(before.size, after.size, "not truncated or extended")
            assertEquals(before.mtimeSec, after.mtimeSec, "not modified")
            assertEquals(before.mtimeNsec, after.mtimeNsec, "not modified")
            assertEquals(before.mode, after.mode, "mode unchanged")

            // The size cap: at most max + 1 bytes are read (the caller then refuses the model as too large).
            assertEquals(101, IosModelFileReader.read(file, 100L).size)

            // No create: a missing file stays missing.
            assertFailsWith<FaceMaskException.IoFailed> { IosModelFileReader.read(missing, 10_000L) }
            assertEquals(null, stat(missing), "nothing was created")

            // No follow: a symbolic link as the last component is refused; the link and its target are unchanged.
            assertEquals(0, symlink(file, link))
            assertFailsWith<FaceMaskException.IoFailed> { IosModelFileReader.read(link, 10_000L) }
            val target = stat(file)!!
            assertEquals(before.size, target.size)
            assertEquals(before.mtimeNsec, target.mtimeNsec)
            assertNotEquals(null, stat(link))

            // A folder is not a regular file.
            assertFailsWith<FaceMaskException.ModelRejected> { IosModelFileReader.read(dir, 10_000L) }

            // It is the reader behind ModelSource.fromFile on iOS.
            assertSame(IosModelFileReader, FaceBlurRuntime.modelFiles())
        } finally {
            unlink(link)
            chmod(file, 0x180.convert())
            unlink(file)
            rmdir(dir)
        }
        assertEquals(null, stat(dir), "the test folder is removed")
    }
}
