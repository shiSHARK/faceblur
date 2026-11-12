// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.filemode.normalised
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EACCES
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.ENOSYS
import platform.posix.ENOTSUP
import platform.posix.EOPNOTSUPP
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.arc4random_buf
import platform.posix.errno
import platform.posix.getcwd
import platform.posix.lstat
import platform.posix.stat

/** The device and inode of the file a path source was read from (ADR 0005 decision 2). */
internal data class IosFileIdentity(val device: Long, val inode: Long)

/** A directory entry as `lstat` sees it. */
internal class IosEntry(val mode: Int, val links: Long, val identity: IosFileIdentity)

/** One failed POSIX call: its `errno`, never a path or text. */
internal class PosixFailed(val code: Int) : Exception()

/**
 * The POSIX calls of the iOS path writer, one platform call each; [IosPosixCalls] in production, a fault-injecting
 * wrapper in tests. Every failure throws [PosixFailed] with the error number.
 */
internal interface IosWriteCalls {
    fun lstat(path: String): IosEntry

    /** Creates [path] exclusively (`O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC`, mode `0600`). */
    fun createExclusive(path: String): Int

    /** Writes at most [count] bytes of [from] at [offset] to [fd]; returns how many were written. */
    fun put(fd: Int, from: ByteArray, offset: Int, count: Int): Int

    fun sync(fd: Int)

    fun closeFd(fd: Int)

    fun hardLink(from: String, to: String)

    fun replace(from: String, to: String)

    fun drop(path: String)

    /** `fsync` of the directory [dir]; best effort (ignored when the file system refuses it). */
    fun syncDirectory(dir: String)
}

/** The production calls on POSIX. */
internal object IosPosixCalls : IosWriteCalls {
    override fun lstat(path: String): IosEntry = memScoped {
        val st = alloc<stat>()
        if (lstat(path, st.ptr) != 0) throw PosixFailed(errno)
        IosEntry(st.st_mode.toInt(), st.st_nlink.toLong(), IosFileIdentity(st.st_dev.toLong(), st.st_ino.toLong()))
    }

    override fun createExclusive(path: String): Int {
        val fd = platform.posix.open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, OWNER_ONLY)
        if (fd < 0) throw PosixFailed(errno)
        return fd
    }

    override fun put(fd: Int, from: ByteArray, offset: Int, count: Int): Int {
        val n = from.usePinned { platform.posix.write(fd, it.addressOf(offset), count.convert()) }
        if (n < 0) throw PosixFailed(errno)
        return n.toInt()
    }

    override fun sync(fd: Int) {
        if (platform.posix.fsync(fd) != 0) throw PosixFailed(errno)
    }

    override fun closeFd(fd: Int) {
        if (platform.posix.close(fd) != 0) throw PosixFailed(errno)
    }

    override fun hardLink(from: String, to: String) {
        if (platform.posix.link(from, to) != 0) throw PosixFailed(errno)
    }

    override fun replace(from: String, to: String) {
        if (platform.posix.rename(from, to) != 0) throw PosixFailed(errno)
    }

    override fun drop(path: String) {
        if (platform.posix.unlink(path) != 0) throw PosixFailed(errno)
    }

    override fun syncDirectory(dir: String) {
        val fd = platform.posix.open(dir, O_RDONLY or O_CLOEXEC)
        if (fd < 0) return
        platform.posix.fsync(fd)
        platform.posix.close(fd)
    }

    /** `0600`: read and write for the owner only. */
    private const val OWNER_ONLY = 0x180
}

/**
 * The iOS writer of finished files for file-system paths (ADR 0005 decision 2; the rules of the Android
 * `PathWriteRules`): the masked JPEG goes to a temporary file next to the target (`.<name>.faceblur-<random>.tmp`,
 * created exclusively, owner-only, not following a link), is written completely and synced; `NewFile` without
 * `replaceExisting` publishes it with `link(2)` (never replaces anything; an existing entry fails), every replacing
 * target renames it over the target in one step after the target was checked again (a symbolic link, a non-regular
 * file or a file with other hard links is never replaced; `OverwriteSource` must still be the decoded file). On every
 * failure the temporary file is removed and the target is untouched. When no hard link can be made (the file system
 * has none), the target is created exclusively and written directly instead (a file there before is never touched).
 * Messages are fixed and never contain a path. When the location does not allow a file next to the target (the
 * temporary file cannot be created: permission refused, read-only or unsupported file system), the call throws
 * `UnsupportedTarget` and nothing is written (ADR 0005 decision 2); other failures to create it (for example a full
 * disk) are `IoFailed`. The item-replacement directory fallback for such locations is not implemented yet.
 */
internal class IosPathWriter(private val calls: IosWriteCalls = IosPosixCalls) : TargetWriter {
    override fun check(target: OutputTarget, source: FileSource, call: CallContext) {
        val r = route(target, source)
        checkEntry(r.path, r.replace, r.identity)
    }

    override fun accepts(value: PlatformTargetValue): Boolean = false

    override fun absolutePath(path: String): String {
        if (path.startsWith("/")) return path
        val cwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.convert())?.toKString()
        } ?: throw FaceMaskException.IoFailed("the current directory could not be read")
        return if (cwd.endsWith("/")) cwd + path else "$cwd/$path"
    }

    override fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) {
        val r = route(target, source)
        val path = r.path
        checkEntry(path, r.replace, r.identity)
        val cut = path.lastIndexOf('/')
        if (cut < 0 || cut == path.length - 1) throw unwritable()
        val dir = if (cut == 0) "/" else path.substring(0, cut)
        val temp = "$dir/.${namePart(path.substring(cut + 1))}.faceblur-${randomPart()}.tmp"
        val fd = try {
            calls.createExclusive(temp)
        } catch (e: PosixFailed) {
            throw if (e.code in NO_SIBLING) noSibling() else unwritable()
        }
        var open = true
        var tempExists = true
        try {
            putAll(fd, bytes)
            io { calls.sync(fd) }
            open = false
            io { calls.closeFd(fd) }
            if (!r.replace) {
                publishNoReplace(temp, path, bytes)
            } else {
                checkEntry(path, true, r.identity)
                io { calls.replace(temp, path) }
            }
            tempExists = false
        } catch (t: Throwable) {
            if (open) quietly { calls.closeFd(fd) }
            if (tempExists) quietly { calls.drop(temp) }
            throw t
        }
        calls.syncDirectory(dir)
    }

    private class Route(val path: String, val replace: Boolean, val identity: IosFileIdentity?)

    private fun route(target: OutputTarget, source: FileSource): Route = when (target) {
        is OutputTarget.NewFile -> {
            val path = absolutePath(target.path)
            val src = (source as? FileSource.Path)?.path
            val same = src != null && normalised(absolutePath(src)) == normalised(path)
            Route(path, target.replaceExisting, if (target.replaceExisting && same) identityOf(source) else null)
        }

        OutputTarget.OverwriteSource -> {
            val src = (source as? FileSource.Path)?.path
                ?: throw FaceMaskException.UnsupportedTarget("this source has no file to replace")
            Route(absolutePath(src), true, identityOf(source))
        }

        is OutputTarget.PlatformTarget ->
            throw FaceMaskException.UnsupportedTarget("iOS has no platform targets; use a file path")
    }

    private fun identityOf(source: FileSource): IosFileIdentity =
        IosSourceIdentities.of(source) ?: throw FaceMaskException.Internal("the decoded source file is not known")

    /** The refusals that need the file system; creates, opens and changes nothing. */
    private fun checkEntry(path: String, replace: Boolean, identity: IosFileIdentity?) {
        val entry = try {
            calls.lstat(path)
        } catch (e: PosixFailed) {
            if (e.code != ENOENT) throw FaceMaskException.IoFailed("the target could not be checked")
            null
        }
        when {
            entry == null -> if (identity != null) {
                throw FaceMaskException.UnsupportedTarget(
                    "the source file is no longer at its path; nothing was written",
                )
            }

            !replace -> throw exists()

            else -> {
                val type = entry.mode and S_IFMT
                when {
                    type == S_IFLNK -> throw FaceMaskException.UnsupportedTarget(
                        "the target is a symbolic link; FaceBlur never replaces a link, because the file it points " +
                            "to would keep its content",
                    )

                    type != S_IFREG -> throw FaceMaskException.UnsupportedTarget("the target is not a regular file")

                    entry.links > 1 -> throw FaceMaskException.UnsupportedTarget(
                        "the target has other hard links, which would keep the old content",
                    )
                }
                if (identity != null && entry.identity != identity) {
                    throw FaceMaskException.UnsupportedTarget(
                        "the source file was replaced or moved during the call; nothing was written",
                    )
                }
            }
        }
    }

    private fun publishNoReplace(temp: String, path: String, bytes: ByteArray) {
        try {
            calls.hardLink(temp, path)
        } catch (e: PosixFailed) {
            when (e.code) {
                EEXIST -> throw exists()

                EPERM, EOPNOTSUPP, ENOTSUP, ENOSYS, EACCES -> {
                    quietly { calls.drop(temp) }
                    writeExclusively(path, bytes)
                    return
                }

                else -> throw FaceMaskException.IoFailed("the output file could not be published")
            }
        }
        quietly { calls.drop(temp) }
    }

    /** The fallback without hard links: creates [path] exclusively and writes into it; removes it on failure. */
    private fun writeExclusively(path: String, bytes: ByteArray) {
        val fd = try {
            calls.createExclusive(path)
        } catch (e: PosixFailed) {
            throw if (e.code == EEXIST) exists() else unwritable()
        }
        var open = true
        try {
            putAll(fd, bytes)
            io { calls.sync(fd) }
            open = false
            io { calls.closeFd(fd) }
        } catch (t: Throwable) {
            if (open) quietly { calls.closeFd(fd) }
            quietly { calls.drop(path) }
            throw t
        }
    }

    private fun putAll(fd: Int, bytes: ByteArray) {
        var done = 0
        while (done < bytes.size) {
            val n = io { calls.put(fd, bytes, done, bytes.size - done) }
            if (n <= 0) throw unwritable()
            done += n
        }
    }

    private inline fun <T> io(call: () -> T): T = try {
        call()
    } catch (e: PosixFailed) {
        throw unwritable()
    }

    private inline fun quietly(call: () -> Unit) {
        try {
            call()
        } catch (e: PosixFailed) {
            Unit
        }
    }

    private fun exists() =
        FaceMaskException.UnsupportedTarget("a file exists at the target path; use replaceExisting to replace it")

    private fun unwritable() = FaceMaskException.IoFailed("the output file could not be written")

    private fun noSibling() = FaceMaskException.UnsupportedTarget(
        "this location does not allow a temporary file next to the target; nothing was written",
    )

    internal companion object {
        /** The longest part of the target's name, in UTF-8 bytes, kept in the temporary name. */
        private const val NAME_PART_BYTES = 64

        /** The `errno` values of a refused temporary file: the location, not the call, is the problem. */
        private val NO_SIBLING = setOf(EACCES, EPERM, EROFS, ENOTSUP, EOPNOTSUPP)

        /** The start of [name] of at most [NAME_PART_BYTES] UTF-8 bytes, cut at a code point boundary. */
        fun namePart(name: String): String {
            var bytes = 0
            var end = 0
            while (end < name.length) {
                val high = name[end].isHighSurrogate() && end + 1 < name.length && name[end + 1].isLowSurrogate()
                val cp = if (high) 0x10000 else name[end].code
                val size = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > NAME_PART_BYTES) break
                bytes += size
                end += if (high) 2 else 1
            }
            return name.substring(0, end)
        }

        private fun randomPart(): String {
            val b = ByteArray(16)
            b.usePinned { arc4random_buf(it.addressOf(0), b.size.convert()) }
            return b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}

/**
 * The device and inode of the file each running path call read, keyed by the call's own [FileSource] object (one per
 * call, compared by identity) and dropped with it (weak references). The writer reads it for `OverwriteSource`
 * (ADR 0005 decision 2).
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal object IosSourceIdentities {
    private class Entry(val source: kotlin.native.ref.WeakReference<FileSource>, val identity: IosFileIdentity)

    private val known = kotlin.concurrent.AtomicReference<List<Entry>>(emptyList())

    fun record(source: FileSource, identity: IosFileIdentity) {
        while (true) {
            val now = known.value
            val next = now.filter { it.source.value.let { s -> s != null && s !== source } } +
                Entry(kotlin.native.ref.WeakReference(source), identity)
            if (known.compareAndSet(now, next)) return
        }
    }

    fun of(source: FileSource): IosFileIdentity? = known.value.firstOrNull { it.source.value === source }?.identity
}
