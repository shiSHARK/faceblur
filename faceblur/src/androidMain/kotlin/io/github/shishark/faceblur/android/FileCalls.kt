// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.ImageDecoder

/**
 * The file-system calls of file mode on top of the model reader's descriptor calls: each is one platform call and
 * throws [DescriptorFailed] (the platform's error number, never its text) when it fails. Android: the
 * `android.system.Os` calls in `AndroidModelFileReader.kt` (phone-only); every rule that uses them is in host-tested
 * code ([PathSourceRules], [PathWriteRules]).
 */
internal interface FileCalls<D> : DescriptorCalls<D> {
    /** `stat(2)` of [path], following a symbolic link. */
    fun statPath(path: String): FileFacts

    /** `lstat(2)` of [path]: the entry itself, a symbolic link is not followed. */
    fun lstat(path: String): FileFacts

    /** `open(2)` of [path] with [flags] and the permission bits [mode] for a file it creates. */
    fun create(path: String, flags: Int, mode: Int): D

    /** `pread(2)`: reads at most [count] bytes at [position] without moving the file offset; 0 at the end. */
    fun pread(fd: D, into: ByteArray, offset: Int, count: Int, position: Long): Int

    /** `write(2)`: writes at most [count] bytes; returns how many were written. */
    fun write(fd: D, from: ByteArray, offset: Int, count: Int): Int

    /** `fsync(2)`. */
    fun fsync(fd: D)

    /** `link(2)`: a new name [to] for the file [from]; fails with `EEXIST` when [to] exists (also as a link). */
    fun link(from: String, to: String)

    /** `rename(2)`: replaces [to] in one step. */
    fun rename(from: String, to: String)

    /** `unlink(2)`. */
    fun unlink(path: String)

    /**
     * A decoder source that reads the file open at [fd] (the descriptor whose type was checked), never the path
     * again. The caller keeps [fd] open while the source is used and closes it afterwards.
     */
    fun decoderSource(fd: D): ImageDecoder.Source
}
