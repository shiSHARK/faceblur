// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.res.AssetFileDescriptor
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.github.shishark.faceblur.internal.runtime.ModelFileReader
import java.io.FileDescriptor
import java.io.InterruptedIOException

/**
 * The Android model-file reader for `ModelSource.fromFile`: the read rules of [ModelFileRules] on `android.system.Os`
 * descriptors.
 *
 * Phone-only (the host has no libcore natives; Robolectric's `Os` calls do nothing): this file holds only the `Os`
 * calls, each one mapped to [DescriptorFailed] without the platform's text; every rule is in `ModelFileRules.kt`,
 * `PathSourceRules.kt` and `PathWriteRules.kt` (host-tested).
 */
internal object AndroidModelFileReader : ModelFileReader {
    override fun read(path: String, maxBytes: Long): ByteArray = ModelFileRules.read(path, maxBytes, OsDescriptors)
}

/** The `android.system.Os` descriptor and file calls (model files and file mode). */
internal object OsDescriptors : FileCalls<FileDescriptor> {
    /**
     * Opens [path] for reading only: flags that could write, create, truncate or append are refused before the
     * platform call ([PathWriteRules.requireReadOnly]), so a plain open can never change the file system whoever calls
     * it. Files are created only by [create] (the masked output's temporary file).
     */
    override fun open(path: String, flags: Int): FileDescriptor {
        PathWriteRules.requireReadOnly(flags)
        return try {
            Os.open(path, flags, 0)
        } catch (e: ErrnoException) {
            throw DescriptorFailed(e.errno)
        }
    }

    override fun stat(fd: FileDescriptor): FileFacts {
        val st =
            try {
                Os.fstat(fd)
            } catch (e: ErrnoException) {
                throw DescriptorFailed(e.errno)
            }
        return FileFacts(st.st_mode, st.st_size, st.st_ino, st.st_dev)
    }

    override fun clearNonBlocking(fd: FileDescriptor) {
        try {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
        } catch (e: ErrnoException) {
            throw DescriptorFailed(e.errno)
        }
    }

    override fun read(fd: FileDescriptor, into: ByteArray, offset: Int, count: Int): Int = try {
        Os.read(fd, into, offset, count)
    } catch (e: ErrnoException) {
        throw DescriptorFailed(e.errno)
    } catch (e: InterruptedIOException) {
        throw DescriptorFailed(null)
    }

    override fun close(fd: FileDescriptor) = try {
        Os.close(fd)
    } catch (e: ErrnoException) {
        throw DescriptorFailed(e.errno)
    }

    // File mode (ADR 0005 decision 2, ADR 0015 decision 4): one call per function, mapped like the calls above.

    override fun statPath(path: String): FileFacts = facts(os { Os.stat(path) })

    override fun lstat(path: String): FileFacts = facts(os { Os.lstat(path) })

    override fun create(path: String, flags: Int, mode: Int): FileDescriptor = os { Os.open(path, flags, mode) }

    override fun pread(fd: FileDescriptor, into: ByteArray, offset: Int, count: Int, position: Long): Int =
        os { Os.pread(fd, into, offset, count, position) }

    override fun write(fd: FileDescriptor, from: ByteArray, offset: Int, count: Int): Int =
        os { Os.write(fd, from, offset, count) }

    override fun fsync(fd: FileDescriptor) = os { Os.fsync(fd) }

    override fun link(from: String, to: String) = os { Os.link(from, to) }

    override fun rename(from: String, to: String) = os { Os.rename(from, to) }

    override fun unlink(path: String) = os { Os.remove(path) }

    // ImageDecoder closes each descriptor it is given; the source therefore hands it a duplicate of [fd] per decode.
    override fun decoderSource(fd: FileDescriptor): ImageDecoder.Source = ImageDecoder.createSource {
        AssetFileDescriptor(ParcelFileDescriptor.dup(fd), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun facts(st: StructStat) = FileFacts(st.st_mode, st.st_size, st.st_ino, st.st_dev, st.st_nlink)

    private inline fun <T> os(call: () -> T): T = try {
        call()
    } catch (e: ErrnoException) {
        throw DescriptorFailed(e.errno)
    } catch (e: InterruptedIOException) {
        throw DescriptorFailed(null)
    }
}
