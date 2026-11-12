// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.internal.model.ModelLoader

/**
 * What `fstat`, `stat` or `lstat` reports: the mode bits, the size in bytes, the inode, the device and the number of
 * hard links.
 */
internal class FileFacts(val mode: Int, val size: Long, val inode: Long, val device: Long, val links: Long = 1)

/**
 * A descriptor call failed. [errno] is the platform's error number, or null when the call was interrupted. The
 * platform exception is not kept (its text can contain the path).
 */
internal class DescriptorFailed(val errno: Int?) : Exception()

/**
 * The descriptor calls of the model-file rules; each is one platform call and throws [DescriptorFailed] when it fails.
 * Android: the `android.system.Os` calls in `AndroidModelFileReader.kt`.
 */
internal interface DescriptorCalls<D> {
    fun open(path: String, flags: Int): D

    fun stat(fd: D): FileFacts

    /** Clears `O_NONBLOCK` on [fd]. */
    fun clearNonBlocking(fd: D)

    fun read(fd: D, into: ByteArray, offset: Int, count: Int): Int

    fun close(fd: D)
}

/**
 * The Android read rules of ADR 0012 decision 4 (step 2) for `ModelSource.fromFile`, on the open descriptor (never on
 * the path again):
 * - open with [openFlags] (`O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC`): a symbolic link as the last path
 *   component fails with `ELOOP` and is refused as `NOT_REGULAR_FILE`; `O_NONBLOCK` keeps a FIFO from blocking the open;
 * - `fstat` on the descriptor must report a regular file (`S_ISREG`), else `NOT_REGULAR_FILE`; then `O_NONBLOCK` is
 *   cleared;
 * - a file larger than `maxBytes` is refused as `TOO_LARGE` without reading it; otherwise exactly its size is read,
 *   plus one probe byte: a file that grew or shrank during the read (a short read, a probe byte, or another size in
 *   the second `fstat`) is refused. Replacing the path does not matter: the descriptor keeps reading the file it
 *   opened (the inode and device comparison is kept as defence in depth). A same-size rewrite in place is not
 *   detected here; the required SHA-256 check of the returned bytes catches any content change;
 * - the descriptor is closed in every case; a failed close is ignored (read-only descriptor: nothing that was read is
 *   lost, and the bytes are checked later).
 * Messages never contain the path; platform exceptions are never chained.
 */
internal object ModelFileRules {
    /** The open flags of the model file. */
    val openFlags: Int =
        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC

    @HiddenFromJava
    fun <D> read(path: String, maxBytes: Long, calls: DescriptorCalls<D>): ByteArray {
        val fd = open(path, calls)
        try {
            val before = io { calls.stat(fd) }
            if (!OsConstants.S_ISREG(before.mode)) {
                throw FaceMaskException.ModelRejected(listOf(ModelLoader.NOT_REGULAR_FILE))
            }
            if (before.size > maxBytes) throw FaceMaskException.ModelRejected(listOf(ModelLoader.TOO_LARGE))
            io { calls.clearNonBlocking(fd) }
            val bytes = ByteArray(before.size.toInt())
            var total = 0
            while (total < bytes.size) {
                val n = io { calls.read(fd, bytes, total, bytes.size - total) }
                if (n <= 0) throw changed()
                total += n
            }
            if (io { calls.read(fd, ByteArray(1), 0, 1) } > 0) throw changed()
            val after = io { calls.stat(fd) }
            if (after.size != before.size || after.inode != before.inode || after.device != before.device) {
                throw changed()
            }
            return bytes
        } finally {
            try {
                calls.close(fd)
            } catch (e: DescriptorFailed) {
                Unit
            }
        }
    }

    private fun <D> open(path: String, calls: DescriptorCalls<D>): D = try {
        calls.open(path, openFlags)
    } catch (e: DescriptorFailed) {
        throw when (e.errno) {
            OsConstants.ELOOP -> FaceMaskException.ModelRejected(listOf(ModelLoader.NOT_REGULAR_FILE))
            OsConstants.ENOENT -> FaceMaskException.IoFailed("the model file does not exist")
            else -> unreadable()
        }
    }

    private inline fun <T> io(call: () -> T): T = try {
        call()
    } catch (e: DescriptorFailed) {
        throw unreadable()
    }

    private fun unreadable() = FaceMaskException.IoFailed("the model file could not be read")

    private fun changed() = FaceMaskException.IoFailed("the model file changed while it was read")
}
