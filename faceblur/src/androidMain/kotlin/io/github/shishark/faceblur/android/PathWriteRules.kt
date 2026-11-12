// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import java.io.File
import java.security.SecureRandom

/** The device and inode of the file a path source was decoded from (ADR 0005 decision 2). */
internal class SourceIdentity(val device: Long, val inode: Long)

/** How a path target may be written. */
internal enum class PathMode {
    /** A new file only: an existing entry (also a symbolic link) is never replaced. */
    NO_REPLACE,

    /** Replace an existing regular file with one name in one step, or create the file. */
    REPLACE,
}

/**
 * The write rules for file-system paths (ADR 0005 decision 2; ADR 0015 decision 4, writer rules), on [FileCalls]:
 * - the finished bytes go to a temporary file next to the target (`.<name>.faceblur-<random>.tmp`, created
 *   exclusively without following a link, owner-only `0600`), are written completely and `fsync`ed;
 * - [PathMode.NO_REPLACE] publishes with `link(2)`, which fails for any existing entry, also one created after the
 *   check; only a "hard links not supported" error (`EPERM`, `EOPNOTSUPP`/`ENOTSUP`, `ENOSYS`, and `EACCES`, with
 *   which Android refuses hard links for apps) falls back to creating the target exclusively (`O_EXCL`) and writing
 *   into it, so the target can hold an incomplete masked file until that write ends; every other `link` error fails
 *   the call. A rename is never used here;
 * - [PathMode.REPLACE] renames the temporary file over the target in one step; before that, and again right before the
 *   rename, the target's own entry (`lstat`, the last component is not followed) must be absent or a regular file
 *   with one name: a symbolic link, any other type or a file with other hard links is refused, because a rename would
 *   leave the old content readable under the other name. With a [SourceIdentity] (`OverwriteSource`) the entry must
 *   also still be the decoded file;
 * - the directory is `fsync`ed after publication (best effort: some file systems refuse it, the file is already in
 *   place);
 * - on every failure the temporary file is removed and the target is untouched (in the fallback, the file the call
 *   created is removed). Messages are fixed and never contain a path or the temporary name; platform exceptions are
 *   never chained.
 * The published file keeps the temporary file's owner-only permissions.
 */
internal object PathWriteRules {
    /** `0600`: read and write for the owner only. */
    const val OWNER_ONLY: Int = 0x180

    /**
     * The longest part of the target's name, in UTF-8 bytes, kept in the temporary name (file names are at most 255
     * bytes; the rest of the temporary name takes 47).
     */
    private const val NAME_PART_BYTES = 64

    /** Flags of every file the writer creates. */
    val createFlags: Int =
        OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or
            OsConstants.O_CLOEXEC

    private val random = SecureRandom()

    /**
     * The rule of every plain open (`FileCalls.open`, applied by its implementation before the platform call): the
     * access mode is `O_RDONLY` and none of `O_CREAT`, `O_TRUNC`, `O_APPEND` is set; anything else is refused with
     * [IllegalArgumentException] (no path in the message). Files are created only by `FileCalls.create` with
     * [createFlags].
     */
    fun requireReadOnly(flags: Int) {
        val writing = OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_APPEND
        require((flags and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY && (flags and writing) == 0) {
            "a read-only open was given flags that can write"
        }
    }

    /**
     * The errors of `link(2)` that mean "no hard links here": the file system has none (`EPERM`, `EOPNOTSUPP` /
     * `ENOTSUP`, `ENOSYS`), or the platform does not let apps create them (`EACCES`: Android's SELinux policy refuses
     * `link` for app processes, also in the app's own storage). The fallback
     * only ever creates the target exclusively, so it never replaces anything either; a directory the app cannot
     * write fails there as well.
     */
    private val linksNotSupported: Set<Int> =
        setOf(OsConstants.EPERM, OsConstants.EOPNOTSUPP, OsConstants.ENOTSUP, OsConstants.ENOSYS, OsConstants.EACCES)

    /**
     * The refusals that need the file system, before anything is decoded; creates, opens and changes nothing.
     * [identity] non-null: the target is the source and must still be that file.
     */
    @HiddenFromJava
    fun <D> check(path: String, mode: PathMode, identity: SourceIdentity?, calls: FileCalls<D>) {
        val entry = entryOrNull(path, calls)
        when {
            entry == null -> if (identity != null) {
                throw FaceMaskException.UnsupportedTarget(
                    "the source file is no longer at its path; nothing was written",
                )
            }

            mode == PathMode.NO_REPLACE -> throw exists()

            else -> {
                requireReplaceable(entry)
                if (identity != null && (entry.device != identity.device || entry.inode != identity.inode)) {
                    throw FaceMaskException.UnsupportedTarget(
                        "the source file was replaced or moved during the call; nothing was written",
                    )
                }
            }
        }
    }

    /** Writes [bytes] to [path] once, after the checks of [check] ran again. */
    @HiddenFromJava
    fun <D> write(path: String, mode: PathMode, identity: SourceIdentity?, bytes: ByteArray, calls: FileCalls<D>) {
        val file = File(path).absoluteFile
        // Every call uses the absolute path, so a change of the process's working directory cannot split the checks
        // and the publication between two directories.
        val target = file.path
        check(target, mode, identity, calls)
        val dir = file.parent ?: throw unwritable()
        val temp = "$dir/.${namePart(file.name)}.faceblur-${randomPart()}.tmp"
        val fd =
            try {
                calls.create(temp, createFlags, OWNER_ONLY)
            } catch (e: DescriptorFailed) {
                throw unwritable()
            }
        var open = true
        var tempExists = true
        try {
            writeAll(fd, bytes, calls)
            io { calls.fsync(fd) }
            open = false
            io { calls.close(fd) }
            if (mode == PathMode.NO_REPLACE) {
                publishNoReplace(temp, target, bytes, calls)
            } else {
                check(target, mode, identity, calls)
                io { calls.rename(temp, target) }
            }
            tempExists = false
        } catch (t: Throwable) {
            if (open) closeQuietly(fd, calls)
            if (tempExists) unlinkQuietly(temp, calls)
            throw t
        }
        syncDirectory(dir, calls)
    }

    /**
     * Publishes [temp] at [path] without replacing anything; removes [temp] afterwards. Falls back to an exclusive
     * create only when the file system has no hard links.
     */
    private fun <D> publishNoReplace(temp: String, path: String, bytes: ByteArray, calls: FileCalls<D>) {
        try {
            calls.link(temp, path)
        } catch (e: DescriptorFailed) {
            val errno = e.errno
            when {
                errno == OsConstants.EEXIST -> throw exists()

                errno != null && errno in linksNotSupported -> {
                    unlinkQuietly(temp, calls)
                    writeExclusively(path, bytes, calls)
                    return
                }

                else -> throw FaceMaskException.IoFailed("the output file could not be published")
            }
        }
        // The target holds the masked bytes; the temporary name (masked bytes only) is removed.
        unlinkQuietly(temp, calls)
    }

    /** The fallback without hard links: creates [path] exclusively and writes into it; removes it on failure. */
    private fun <D> writeExclusively(path: String, bytes: ByteArray, calls: FileCalls<D>) {
        val fd =
            try {
                calls.create(path, createFlags, OWNER_ONLY)
            } catch (e: DescriptorFailed) {
                throw if (e.errno == OsConstants.EEXIST) exists() else unwritable()
            }
        var open = true
        try {
            writeAll(fd, bytes, calls)
            io { calls.fsync(fd) }
            open = false
            io { calls.close(fd) }
        } catch (t: Throwable) {
            if (open) closeQuietly(fd, calls)
            unlinkQuietly(path, calls)
            throw t
        }
    }

    private fun <D> writeAll(fd: D, bytes: ByteArray, calls: FileCalls<D>) {
        var done = 0
        while (done < bytes.size) {
            val n = io { calls.write(fd, bytes, done, bytes.size - done) }
            if (n <= 0) throw unwritable()
            done += n
        }
    }

    /** The target's own entry (`lstat`), or `null` when there is none. */
    private fun <D> entryOrNull(path: String, calls: FileCalls<D>): FileFacts? = try {
        calls.lstat(path)
    } catch (e: DescriptorFailed) {
        if (e.errno == OsConstants.ENOENT) null else throw FaceMaskException.IoFailed("the target could not be checked")
    }

    private fun requireReplaceable(entry: FileFacts) {
        when {
            OsConstants.S_ISLNK(entry.mode) -> throw FaceMaskException.UnsupportedTarget(
                "the target is a symbolic link; FaceBlur never replaces a link, because the file it points to would " +
                    "keep its content",
            )

            !OsConstants.S_ISREG(entry.mode) -> throw FaceMaskException.UnsupportedTarget(
                "the target is not a regular file",
            )

            entry.links > 1 -> throw FaceMaskException.UnsupportedTarget(
                "the target has other hard links, which would keep the old content",
            )
        }
    }

    /** `fsync` of the directory so the new name survives a power loss; refused by some file systems (ignored). */
    private fun <D> syncDirectory(dir: String, calls: FileCalls<D>) {
        val fd =
            try {
                calls.open(dir, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC)
            } catch (e: DescriptorFailed) {
                return
            }
        try {
            calls.fsync(fd)
        } catch (e: DescriptorFailed) {
            Unit
        } finally {
            closeQuietly(fd, calls)
        }
    }

    private fun <D> closeQuietly(fd: D, calls: FileCalls<D>) {
        try {
            calls.close(fd)
        } catch (e: DescriptorFailed) {
            Unit
        }
    }

    private fun <D> unlinkQuietly(path: String, calls: FileCalls<D>) {
        try {
            calls.unlink(path)
        } catch (e: DescriptorFailed) {
            Unit
        }
    }

    /**
     * The start of [name] of at most [NAME_PART_BYTES] UTF-8 bytes, cut at a code point boundary (never inside a
     * surrogate pair or a multi-byte sequence).
     */
    @HiddenFromJava
    fun namePart(name: String): String {
        var bytes = 0
        var end = 0
        while (end < name.length) {
            val cp = name.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > NAME_PART_BYTES) break
            bytes += size
            end += Character.charCount(cp)
        }
        return name.substring(0, end)
    }

    private fun randomPart(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private inline fun <T> io(call: () -> T): T = try {
        call()
    } catch (e: DescriptorFailed) {
        throw unwritable()
    }

    private fun exists() =
        FaceMaskException.UnsupportedTarget("a file exists at the target path; use replaceExisting to replace it")

    private fun unwritable() = FaceMaskException.IoFailed("the output file could not be written")
}
