// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.ImageDecoder
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * [FileCalls] on the host's real file system (java.nio, the JVM's own POSIX calls), so the path rules run against
 * real link, rename, hard-link counts, symbolic links and FIFOs in the host tests. Differences to `android.system.Os`
 * (test adapter only): `fstat` reads the attributes of the opened path, and the decoder source is a copy of the
 * opened file's bytes.
 * Counts the descriptors it has open.
 */
internal object HostFiles : FileCalls<HostFiles.Fd> {
    class Fd(val path: String, val channel: FileChannel?)

    @Volatile
    var openCount: Int = 0
        private set

    private fun <T> posix(call: () -> T): T = try {
        call()
    } catch (e: FileAlreadyExistsException) {
        throw DescriptorFailed(OsConstants.EEXIST)
    } catch (e: NoSuchFileException) {
        throw DescriptorFailed(OsConstants.ENOENT)
    } catch (e: AccessDeniedException) {
        throw DescriptorFailed(OsConstants.EACCES)
    } catch (e: IOException) {
        throw DescriptorFailed(
            if (e.message.orEmpty().contains("symbolic link")) OsConstants.ELOOP else OsConstants.EIO,
        )
    }

    private fun facts(path: String, vararg options: LinkOption): FileFacts = posix {
        val a = Files.readAttributes(Paths.get(path), "unix:mode,size,ino,dev,nlink", *options)
        FileFacts(
            a.getValue("mode") as Int,
            a.getValue("size") as Long,
            a.getValue("ino") as Long,
            a.getValue("dev") as Long,
            (a.getValue("nlink") as Int).toLong(),
        )
    }

    @Synchronized
    private fun opened(fd: Fd): Fd = fd.also { openCount++ }

    override fun open(path: String, flags: Int): Fd = posix {
        if (File(path).isDirectory) {
            opened(Fd(path, null))
        } else {
            opened(Fd(path, FileChannel.open(Paths.get(path), StandardOpenOption.READ)))
        }
    }

    override fun stat(fd: Fd): FileFacts = facts(fd.path)

    override fun clearNonBlocking(fd: Fd) = Unit

    override fun read(fd: Fd, into: ByteArray, offset: Int, count: Int): Int =
        posix { maxOf(0, fd.channel!!.read(ByteBuffer.wrap(into, offset, count))) }

    @Synchronized
    override fun close(fd: Fd) {
        openCount--
        posix { fd.channel?.close() }
    }

    override fun statPath(path: String): FileFacts = facts(path)

    override fun lstat(path: String): FileFacts = facts(path, LinkOption.NOFOLLOW_LINKS)

    override fun create(path: String, flags: Int, mode: Int): Fd = posix {
        val options = HashSet<OpenOption>()
        options += StandardOpenOption.WRITE
        options += if (flags and OsConstants.O_EXCL != 0) StandardOpenOption.CREATE_NEW else StandardOpenOption.CREATE
        if (flags and OsConstants.O_NOFOLLOW != 0) options += LinkOption.NOFOLLOW_LINKS
        if (flags and OsConstants.O_TRUNC != 0) options += StandardOpenOption.TRUNCATE_EXISTING
        val perms = PosixFilePermissions.asFileAttribute(permissions(mode))
        opened(Fd(path, FileChannel.open(Paths.get(path), options, perms)))
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> {
        val all = listOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        return all.filterIndexed { i, _ -> mode and (1 shl (8 - i)) != 0 }.toSet()
    }

    override fun pread(fd: Fd, into: ByteArray, offset: Int, count: Int, position: Long): Int =
        posix { maxOf(0, fd.channel!!.read(ByteBuffer.wrap(into, offset, count), position)) }

    override fun write(fd: Fd, from: ByteArray, offset: Int, count: Int): Int =
        posix { fd.channel!!.write(ByteBuffer.wrap(from, offset, count)) }

    override fun fsync(fd: Fd) = posix {
        val c = fd.channel ?: throw IOException("a directory channel")
        c.force(true)
    }

    override fun link(from: String, to: String) = posix {
        Files.createLink(Paths.get(to), Paths.get(from))
        Unit
    }

    override fun rename(from: String, to: String) = posix {
        Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override fun unlink(path: String) = posix { Files.delete(Paths.get(path)) }

    /** Robolectric's decoder reads no file or descriptor source: the opened file's bytes, read through [fd]. */
    override fun decoderSource(fd: Fd): ImageDecoder.Source = posix {
        val c = fd.channel!!
        val buffer = ByteBuffer.allocate(c.size().toInt())
        while (buffer.hasRemaining() && c.read(buffer, buffer.position().toLong()) > 0) Unit
        ImageDecoder.createSource(ByteBuffer.wrap(buffer.array(), 0, buffer.position()))
    }
}
