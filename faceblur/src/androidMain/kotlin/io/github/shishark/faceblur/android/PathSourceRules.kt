// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.filemode.ImageFormat

/**
 * A path source opened for decoding: the descriptor whose type was checked, the device and inode of the file it
 * reads (what `OverwriteSource` may replace, ADR 0005 decision 2) and its first bytes for the format check.
 */
internal class OpenedFile<D>(val fd: D, val device: Long, val inode: Long, @get:HiddenFromJava val head: ByteArray)

/**
 * The open rules of a file-mode path source (ADR 0015 decision 4, codec rules), on [FileCalls]:
 * - the path must name a regular file (`stat`, a symbolic link to a regular file is read as that file); a FIFO,
 *   socket, device or directory is refused as `UnsupportedImage` before it is opened;
 * - the open itself is non-blocking (`O_NONBLOCK`), so a path swapped for a FIFO after the check cannot block the
 *   call, and the opened descriptor's type is checked again (`fstat`); then `O_NONBLOCK` is cleared;
 * - the first [ImageFormat.SNIFF_BYTES] bytes are read with `pread` (the decoder later reads from offset 0).
 * The descriptor is closed on every failure. Messages are fixed and never contain the path; platform exceptions are
 * never chained.
 */
internal object PathSourceRules {
    /** The open flags of a path source. */
    val openFlags: Int = OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC

    @HiddenFromJava
    fun <D> open(path: String, calls: FileCalls<D>): OpenedFile<D> {
        val entry =
            try {
                calls.statPath(path)
            } catch (e: DescriptorFailed) {
                throw unopened(e)
            }
        if (!OsConstants.S_ISREG(entry.mode)) throw notRegular()
        val fd =
            try {
                calls.open(path, openFlags)
            } catch (e: DescriptorFailed) {
                throw unopened(e)
            }
        try {
            val facts = io { calls.stat(fd) }
            if (!OsConstants.S_ISREG(facts.mode)) throw notRegular()
            io { calls.clearNonBlocking(fd) }
            val head = ByteArray(ImageFormat.SNIFF_BYTES)
            var n = 0
            while (n < head.size) {
                val got = io { calls.pread(fd, head, n, head.size - n, n.toLong()) }
                if (got <= 0) break
                n += got
            }
            return OpenedFile(fd, facts.device, facts.inode, head.copyOf(n))
        } catch (t: Throwable) {
            closeQuietly(fd, calls)
            throw t
        }
    }

    /** Closes [fd] of a read-only source; a failed close loses nothing and is ignored. */
    @HiddenFromJava
    fun <D> closeQuietly(fd: D, calls: FileCalls<D>) {
        try {
            calls.close(fd)
        } catch (e: DescriptorFailed) {
            Unit
        }
    }

    private fun unopened(e: DescriptorFailed) = when (e.errno) {
        OsConstants.ENOENT -> FaceMaskException.IoFailed("the source file does not exist")
        else -> unreadable()
    }

    private inline fun <T> io(call: () -> T): T = try {
        call()
    } catch (e: DescriptorFailed) {
        throw unreadable()
    }

    private fun notRegular() = FaceMaskException.UnsupportedImage("the source is not a regular file")

    private fun unreadable() = FaceMaskException.IoFailed("the source file could not be read")
}
