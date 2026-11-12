// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.ImageDecoder
import android.system.OsConstants
import java.nio.ByteBuffer

/**
 * An in-memory file system behind [FileCalls] for the path rules: entries by absolute path (regular files with
 * content, symbolic links, FIFOs, directories), inodes shared by hard links, POSIX semantics of `link` (fails for an
 * existing entry), `rename` (replaces the entry, not what a link points to) and exclusive create. Every call is
 * recorded; [fail] makes the next call of an operation fail with an errno, and [before] runs a hook before an
 * operation (for "changed between check and publication" cases). The decoder source reads the node's current bytes
 * (no copy), so a test can change a source in place between the codec's passes.
 */
internal class FakeFiles : FileCalls<Int> {
    class Node(val inode: Long, var mode: Int, var content: ByteArray = ByteArray(0), var target: String? = null) {
        var links: Long = 0
    }

    private class Open(val node: Node, val flags: Int) {
        var nonBlocking = flags and OsConstants.O_NONBLOCK != 0
    }

    val entries = LinkedHashMap<String, Node>()
    val calls = ArrayList<String>()
    val createModes = HashMap<String, Int>()
    val createFlags = HashMap<String, Int>()
    private val fail = HashMap<String, ArrayDeque<Int>>()
    private val before = HashMap<String, () -> Unit>()
    private val open = HashMap<Int, Open>()
    private var nextFd = 3
    private var nextInode = 100L
    val device = 7L

    /** At most this many bytes per `write` (short writes). */
    var writeChunk = Int.MAX_VALUE

    val regular = OsConstants.S_IFREG or 0x1A4

    fun file(path: String, content: ByteArray = ByteArray(0)): Node = add(path, Node(nextInode++, regular, content))

    fun symlink(path: String, target: String): Node =
        add(path, Node(nextInode++, OsConstants.S_IFLNK or 0x1FF, target = target))

    fun fifo(path: String): Node = add(path, Node(nextInode++, OsConstants.S_IFIFO or 0x1A4))

    fun dir(path: String): Node = add(path, Node(nextInode++, OsConstants.S_IFDIR or 0x1ED))

    fun hardLink(existing: String, path: String): Node = add(path, entries.getValue(existing))

    private fun add(path: String, node: Node): Node {
        entries[path]?.let { it.links-- }
        node.links++
        entries[path] = node
        return node
    }

    fun content(path: String): ByteArray? = entries[path]?.content

    /** The names of temporary files that still exist. */
    fun temps(): List<String> = entries.keys.filter { it.contains(".faceblur-") && it.endsWith(".tmp") }

    val openDescriptors: Int get() = open.size

    fun fail(op: String, errno: Int) {
        fail.getOrPut(op) { ArrayDeque() }.addLast(errno)
    }

    fun before(op: String, hook: () -> Unit) {
        before[op] = hook
    }

    private fun enter(op: String, detail: String) {
        calls += "$op $detail"
        before.remove(op)?.invoke()
        fail[op]?.removeFirstOrNull()?.let { throw DescriptorFailed(it) }
    }

    private fun resolve(path: String, depth: Int = 0): Node {
        val n = entries[path] ?: throw DescriptorFailed(OsConstants.ENOENT)
        if (OsConstants.S_ISLNK(n.mode)) {
            if (depth > 8) throw DescriptorFailed(OsConstants.ELOOP)
            return resolve(n.target!!, depth + 1)
        }
        return n
    }

    private fun facts(n: Node) = FileFacts(n.mode, n.content.size.toLong(), n.inode, device, n.links)

    private fun node(fd: Int): Open = open[fd] ?: throw DescriptorFailed(OsConstants.EBADF)

    override fun open(path: String, flags: Int): Int {
        enter("open", path)
        val n = resolve(path)
        val fd = nextFd++
        open[fd] = Open(n, flags)
        return fd
    }

    override fun stat(fd: Int): FileFacts {
        enter("fstat", "$fd")
        return facts(node(fd).node)
    }

    override fun clearNonBlocking(fd: Int) {
        enter("clearNonBlocking", "$fd")
        node(fd).nonBlocking = false
    }

    override fun read(fd: Int, into: ByteArray, offset: Int, count: Int): Int {
        enter("read", "$fd")
        return 0
    }

    // As in POSIX, the descriptor is released even when close reports an error.
    override fun close(fd: Int) {
        val released = open.remove(fd)
        enter("close", "$fd")
        released ?: throw DescriptorFailed(OsConstants.EBADF)
    }

    override fun statPath(path: String): FileFacts {
        enter("stat", path)
        return facts(resolve(path))
    }

    override fun lstat(path: String): FileFacts {
        enter("lstat", path)
        return facts(entries[path] ?: throw DescriptorFailed(OsConstants.ENOENT))
    }

    override fun create(path: String, flags: Int, mode: Int): Int {
        enter("create", path)
        createModes[path] = mode
        createFlags[path] = flags
        val existing = entries[path]
        if (existing != null) {
            if (flags and OsConstants.O_EXCL != 0) throw DescriptorFailed(OsConstants.EEXIST)
            if (OsConstants.S_ISLNK(existing.mode) && flags and OsConstants.O_NOFOLLOW != 0) {
                throw DescriptorFailed(OsConstants.ELOOP)
            }
        }
        val n = existing ?: file(path)
        if (flags and OsConstants.O_TRUNC != 0) n.content = ByteArray(0)
        val fd = nextFd++
        open[fd] = Open(n, flags)
        return fd
    }

    override fun pread(fd: Int, into: ByteArray, offset: Int, count: Int, position: Long): Int {
        enter("pread", "$fd")
        val c = node(fd).node.content
        if (position >= c.size) return 0
        val n = minOf(count.toLong(), c.size - position).toInt()
        c.copyInto(into, offset, position.toInt(), position.toInt() + n)
        return n
    }

    override fun write(fd: Int, from: ByteArray, offset: Int, count: Int): Int {
        enter("write", "$fd")
        val o = node(fd)
        val n = minOf(count, writeChunk)
        o.node.content += from.copyOfRange(offset, offset + n)
        return n
    }

    override fun fsync(fd: Int) {
        enter("fsync", "$fd")
        node(fd)
    }

    override fun link(from: String, to: String) {
        enter("link", "$from -> $to")
        val n = entries[from] ?: throw DescriptorFailed(OsConstants.ENOENT)
        if (entries.containsKey(to)) throw DescriptorFailed(OsConstants.EEXIST)
        add(to, n)
    }

    override fun rename(from: String, to: String) {
        enter("rename", "$from -> $to")
        val n = entries.remove(from) ?: throw DescriptorFailed(OsConstants.ENOENT)
        n.links--
        add(to, n)
    }

    override fun unlink(path: String) {
        enter("unlink", path)
        val n = entries.remove(path) ?: throw DescriptorFailed(OsConstants.ENOENT)
        n.links--
    }

    override fun decoderSource(fd: Int): ImageDecoder.Source =
        ImageDecoder.createSource(ByteBuffer.wrap(node(fd).node.content))
}
