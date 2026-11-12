// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.runtime.ModelFileReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_CLOEXEC
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.read
import platform.posix.stat

/** `ModelSource.fromFile` on iOS: a regular file, no symbolic link as the last component, at most max + 1 bytes. */
internal object IosModelFileReader : ModelFileReader {
    override fun read(path: String, maxBytes: Long): ByteArray = read(path, maxBytes) {}

    /** [opened] sees the open descriptor before anything else uses it (tests read its flags there). */
    internal fun read(path: String, maxBytes: Long, opened: (Int) -> Unit): ByteArray {
        val fd = open(path, O_RDONLY or O_NOFOLLOW or O_CLOEXEC)
        if (fd < 0) {
            throw FaceMaskException.IoFailed("the model file could not be opened")
        }
        try {
            opened(fd)
            val size =
                memScoped {
                    val st = alloc<stat>()
                    if (fstat(fd, st.ptr) != 0 || (st.st_mode.toInt() and S_IFMT) != S_IFREG) {
                        throw FaceMaskException.ModelRejected(listOf("NOT_REGULAR_FILE"))
                    }
                    st.st_size
                }
            val limit = minOf(size, maxBytes + 1)
            if (limit > Int.MAX_VALUE - 8) throw FaceMaskException.ModelRejected(listOf("TOO_LARGE"))
            val out = ByteArray(limit.toInt())
            var done = 0
            out.usePinned { p ->
                while (done < out.size) {
                    val n = read(fd, p.addressOf(done), (out.size - done).convert()).toInt()
                    if (n < 0) throw FaceMaskException.IoFailed("the model file could not be read")
                    if (n == 0) break
                    done += n
                }
            }
            return if (done == out.size) out else out.copyOf(done)
        } finally {
            close(fd)
        }
    }
}
