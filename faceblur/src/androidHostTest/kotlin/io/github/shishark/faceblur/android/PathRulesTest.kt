// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The path source and path writer rules (ADR 0005 decision 2, ADR 0015 decision 4) against an in-memory file system
 * with POSIX link and rename semantics ([FakeFiles]). Robolectric supplies `OsConstants`; the real `android.system.Os`
 * calls run in the device tests, and [HostFilesTest] runs the same rules on the host's real file system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PathRulesTest {
    private val out = "/data/out/photo.jpg"
    private val bytes = ByteArray(1000) { (it * 7).toByte() }
    private val old = "ORIGINAL".encodeToByteArray()

    private fun fs() = FakeFiles().apply { dir("/data/out") }

    private fun assertNoPath(e: Throwable, vararg paths: String) {
        val m = e.message.orEmpty()
        for (p in listOf(*paths, "/data", ".faceblur-")) assertFalse(m.contains(p), "no path in: $m")
        assertNull(e.cause)
        assertEquals(0, e.suppressed.size)
    }

    @Test
    fun aNewFileIsPublishedByAHardLinkFromAnOwnerOnlyTemporaryFile() {
        val fs = fs()
        PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs)
        assertContentEquals(bytes, fs.content(out))
        assertEquals(emptyList(), fs.temps(), "the temporary name is removed")
        val temp = fs.createModes.keys.single()
        assertTrue(temp.startsWith("/data/out/.photo.jpg.faceblur-") && temp.endsWith(".tmp"), temp)
        assertEquals(0x180, fs.createModes[temp], "0600")
        val flags = fs.createFlags.getValue(temp)
        for (f in listOf(OsConstants.O_CREAT, OsConstants.O_EXCL, OsConstants.O_NOFOLLOW, OsConstants.O_WRONLY)) {
            assertTrue(flags and f == f, "create flag $f")
        }
        assertTrue(fs.calls.any { it.startsWith("link ") }, "published by link")
        assertFalse(fs.calls.any { it.startsWith("rename ") }, "never a rename for a new file")
        assertTrue(fs.calls.indexOfFirst { it.startsWith("fsync") } < fs.calls.indexOfFirst { it.startsWith("link") })
        assertEquals(1L, fs.entries.getValue(out).links)
        assertEquals(0, fs.openDescriptors)
    }

    /**
     * Review 044 L-1: the target's name is cut by UTF-8 bytes at a code point boundary, so a long name of four-byte
     * characters keeps the temporary name under the 255-byte limit and never splits a surrogate pair.
     */
    @Test
    fun theTemporaryNameKeepsAtMost64BytesOfTheTargetNameAtACodePointBoundary() {
        val emoji = "\uD83D\uDE00" // one code point, four UTF-8 bytes
        val cut = PathWriteRules.namePart(emoji.repeat(64))
        assertEquals(emoji.repeat(16), cut)
        assertEquals(64, cut.encodeToByteArray().size)
        assertEquals("a".repeat(64), PathWriteRules.namePart("a".repeat(100)))
        // 63 ASCII bytes + a 2-byte character would make 65: the character is left out, not split.
        assertEquals("a".repeat(63), PathWriteRules.namePart("a".repeat(63) + "\u00E9x"))
        assertEquals("a".repeat(62) + "\u00E9", PathWriteRules.namePart("a".repeat(62) + "\u00E9x"))
        assertEquals("short.jpg", PathWriteRules.namePart("short.jpg"))
        val fs = fs()
        val target = "/data/out/" + emoji.repeat(60) + ".jpg"
        PathWriteRules.write(target, PathMode.NO_REPLACE, null, bytes, fs)
        assertContentEquals(bytes, fs.content(target))
        val temp = fs.createModes.keys.single().substringAfterLast('/')
        assertTrue(temp.encodeToByteArray().size <= 255, "temporary name of ${temp.encodeToByteArray().size} bytes")
        assertTrue(temp.startsWith("." + emoji.repeat(16) + ".faceblur-"), temp)
    }

    /** Review 044 L-9: a relative target is written through its absolute path in every call. */
    @Test
    fun aRelativeTargetIsCheckedAndPublishedThroughItsAbsolutePath() {
        val absolute = java.io.File("relative-out.jpg").absolutePath
        val fs = FakeFiles().apply { dir(absolute.substringBeforeLast('/')) }
        PathWriteRules.write("relative-out.jpg", PathMode.NO_REPLACE, null, bytes, fs)
        assertContentEquals(bytes, fs.content(absolute))
        val touched = fs.calls.filter { it.contains("relative-out.jpg") && !it.contains(".faceblur-") }
        assertTrue(touched.isNotEmpty(), "the target was used")
        assertTrue(touched.all { it.contains(absolute) }, "every call names the absolute path: $touched")
    }

    @Test
    fun anExistingTargetIsNeverReplacedAlsoWhenItAppearsAfterTheCheck() {
        val fs = fs()
        fs.file(out, old)
        val e1 = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.check(out, PathMode.NO_REPLACE, null, fs)
        }
        assertNoPath(e1, out)
        assertFalse(fs.calls.any { it.startsWith("create") }, "the check creates nothing")
        // A dangling symbolic link is an existing entry too.
        val fs2 = fs()
        fs2.symlink(out, "/nowhere")
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs2)
        }
        // Created between the check and the publication: link fails with EEXIST, the target is untouched.
        val fs3 = fs()
        fs3.before("link") { fs3.file(out, old) }
        val e3 = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs3)
        }
        assertNoPath(e3, out)
        assertContentEquals(old, fs3.content(out))
        assertEquals(emptyList(), fs3.temps())
        assertEquals(0, fs3.openDescriptors)
    }

    @Test
    fun withoutHardLinksTheTargetIsCreatedExclusivelyAndRemovedOnFailure() {
        for (errno in listOf(OsConstants.EPERM, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS, OsConstants.EACCES)) {
            val fs = fs()
            fs.fail("link", errno)
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs)
            assertContentEquals(bytes, fs.content(out), "errno $errno")
            assertEquals(emptyList(), fs.temps())
            assertEquals(0x180, fs.createModes[out])
            assertTrue(fs.createFlags.getValue(out) and OsConstants.O_EXCL != 0)
        }
        // The fallback never replaces: a target that appeared meanwhile is refused.
        val exists = fs()
        exists.fail("link", OsConstants.EPERM)
        exists.before("unlink") { exists.file(out, old) }
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, exists)
        }
        assertContentEquals(old, exists.content(out))
        // A failure while writing the fallback target removes the file the call created.
        val broken = fs()
        broken.fail("link", OsConstants.EPERM)
        broken.writeChunk = 100
        broken.before("create") { broken.before("create") { broken.fail("fsync", OsConstants.EIO) } }
        assertFailsWith<FaceMaskException.IoFailed> {
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, broken)
        }
        assertNull(broken.content(out), "the partly written fallback file is removed")
        assertEquals(emptyList(), broken.temps())
    }

    @Test
    fun everyOtherLinkErrorFailsTheCall() {
        for (errno in listOf(OsConstants.EXDEV, OsConstants.EMLINK, OsConstants.EIO, OsConstants.EROFS)) {
            val fs = fs()
            fs.fail("link", errno)
            val e = assertFailsWith<FaceMaskException.IoFailed>("errno $errno") {
                PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs)
            }
            assertNoPath(e, out)
            assertNull(fs.content(out))
            assertEquals(emptyList(), fs.temps())
            assertFalse(fs.calls.any { it == "create $out" }, "no fallback for errno $errno")
        }
    }

    @Test
    fun replacingRenamesInOneStepAndRefusesLinksOtherTypesAndFilesWithOtherNames() {
        val fs = fs()
        fs.file(out, old)
        PathWriteRules.write(out, PathMode.REPLACE, null, bytes, fs)
        assertContentEquals(bytes, fs.content(out))
        assertTrue(fs.calls.any { it.startsWith("rename ") })
        assertEquals(emptyList(), fs.temps())
        // A missing target is created by the rename.
        val fresh = fs()
        PathWriteRules.write(out, PathMode.REPLACE, null, bytes, fresh)
        assertContentEquals(bytes, fresh.content(out))

        val cases = listOf<Pair<String, (FakeFiles) -> Unit>>(
            "symbolic link" to { f ->
                f.file("/data/out/real.jpg", old)
                f.symlink(out, "/data/out/real.jpg")
            },
            "fifo" to { f -> f.fifo(out) },
            "directory" to { f -> f.dir(out) },
            "second hard link" to { f ->
                f.file(out, old)
                f.hardLink(out, "/data/out/other-name.jpg")
            },
        )
        for ((name, setUp) in cases) {
            val f = fs()
            setUp(f)
            val before = f.entries.mapValues { it.value.content.copyOf() }
            val e = assertFailsWith<FaceMaskException.UnsupportedTarget>(name) {
                PathWriteRules.write(out, PathMode.REPLACE, null, bytes, f)
            }
            assertNoPath(e, out)
            assertFalse(f.calls.any { it.startsWith("create") }, "$name: refused before a temporary file exists")
            assertEquals(before.keys, f.entries.keys, name)
            for ((k, v) in before) assertContentEquals(v, f.content(k), "$name: $k untouched")
        }
    }

    @Test
    fun overwriteSourceReplacesExactlyTheDecodedFile() {
        val fs = fs()
        val node = fs.file(out, old)
        val id = SourceIdentity(fs.device, node.inode)
        PathWriteRules.write(out, PathMode.REPLACE, id, bytes, fs)
        assertContentEquals(bytes, fs.content(out))
        // Replaced by another file (same path, other inode) during the call: refused, nothing written.
        val moved = fs()
        val decoded = moved.file(out, old)
        val swapped = moved.file("/data/out/x", "OTHER".encodeToByteArray())
        moved.entries[out] = swapped
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.write(out, PathMode.REPLACE, SourceIdentity(moved.device, decoded.inode), bytes, moved)
        }
        assertNoPath(e, out)
        assertContentEquals("OTHER".encodeToByteArray(), moved.content(out))
        // Gone: refused.
        val gone = fs()
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.check(out, PathMode.REPLACE, SourceIdentity(gone.device, 1), gone)
        }
        // Another device with the same inode number: refused.
        val dev = fs()
        val n = dev.file(out, old)
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.check(out, PathMode.REPLACE, SourceIdentity(dev.device + 1, n.inode), dev)
        }
    }

    @Test
    fun theChecksRunAgainRightBeforeTheRename() {
        val fs = fs()
        val node = fs.file(out, old)
        // While the temporary file is written, the source is turned into a symbolic link to another file.
        fs.before("fsync") {
            fs.file("/data/out/elsewhere.jpg", old)
            fs.symlink(out, "/data/out/elsewhere.jpg")
        }
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            PathWriteRules.write(out, PathMode.REPLACE, SourceIdentity(fs.device, node.inode), bytes, fs)
        }
        assertFalse(fs.calls.any { it.startsWith("rename") })
        assertEquals(emptyList(), fs.temps())
        assertContentEquals(old, fs.content("/data/out/elsewhere.jpg"))
    }

    @Test
    fun everyFailureRemovesTheTemporaryFileAndLeavesTheTargetUntouched() {
        for (op in listOf("create", "write", "fsync", "close", "rename")) {
            val fs = fs()
            fs.file(out, old)
            if (op ==
                "create"
            ) {
                fs.fail(op, OsConstants.ENOSPC)
            } else {
                fs.before("create") { fs.fail(op, OsConstants.EIO) }
            }
            val e = assertFailsWith<FaceMaskException.IoFailed>(op) {
                PathWriteRules.write(out, PathMode.REPLACE, null, bytes, fs)
            }
            assertNoPath(e, out)
            assertContentEquals(old, fs.content(out), "$op: target untouched")
            assertEquals(emptyList(), fs.temps(), "$op: no temporary file left")
            assertEquals(0, fs.openDescriptors, "$op: nothing left open")
        }
        // A write that makes no progress fails instead of looping.
        val stuck = fs()
        stuck.writeChunk = 0
        assertFailsWith<FaceMaskException.IoFailed> {
            PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, stuck)
        }
        assertEquals(emptyList(), stuck.temps())
        // A target that cannot be checked (other than missing) fails the call.
        val unchecked = fs()
        unchecked.fail("lstat", OsConstants.EACCES)
        assertFailsWith<FaceMaskException.IoFailed> { PathWriteRules.check(out, PathMode.REPLACE, null, unchecked) }
    }

    @Test
    fun shortWritesAreCompletedAndADirectoryThatRefusesFsyncDoesNotFailThePublishedFile() {
        val fs = fs()
        fs.writeChunk = 7
        var publishedFsyncs = 0
        fs.before("link") {
            fs.before("fsync") {
                publishedFsyncs++
                fs.fail("fsync", OsConstants.EINVAL)
            }
        }
        PathWriteRules.write(out, PathMode.NO_REPLACE, null, bytes, fs)
        assertContentEquals(bytes, fs.content(out))
        assertEquals(1, publishedFsyncs, "the directory fsync ran and its refusal is ignored")
        val longName = "/data/out/" + "n".repeat(250) + ".jpg"
        PathWriteRules.write(longName, PathMode.NO_REPLACE, null, bytes, fs)
        assertTrue(fs.createModes.keys.all { it.substringAfterLast('/').length <= 255 }, "temporary names fit")
    }

    @Test
    fun aPathSourceIsARegularFileOpenedWithoutBlockingAndCheckedOnItsDescriptor() {
        val fs = FakeFiles()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200)
        val node = fs.file("/data/in.jpg", jpeg)
        fs.symlink("/data/link.jpg", "/data/in.jpg")
        for (path in listOf("/data/in.jpg", "/data/link.jpg")) {
            val opened = PathSourceRules.open(path, fs)
            assertEquals(64, opened.head.size)
            assertContentEquals(jpeg.copyOf(64), opened.head)
            assertEquals(node.inode, opened.inode, "the identity of the opened file")
            assertEquals(fs.device, opened.device)
            PathSourceRules.closeQuietly(opened.fd, fs)
        }
        val flags = PathSourceRules.openFlags
        assertTrue(flags and OsConstants.O_NONBLOCK != 0 && flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
        assertTrue(fs.calls.contains("clearNonBlocking 3"))
        // A short file gives a short head.
        fs.file("/data/short", byteArrayOf(1, 2))
        assertEquals(2, PathSourceRules.open("/data/short", fs).head.size)

        // FIFOs and directories are refused before they are opened.
        fs.fifo("/data/fifo")
        fs.dir("/data/dir")
        for (p in listOf("/data/fifo", "/data/dir")) {
            val e = assertFailsWith<FaceMaskException.UnsupportedImage>(p) { PathSourceRules.open(p, fs) }
            assertNoPath(e, p)
            assertFalse(fs.calls.contains("open $p"), "$p is never opened")
        }
        // Swapped for a FIFO between the check and the open: the descriptor's type refuses it and it is closed.
        val swap = FakeFiles()
        swap.file("/data/in.jpg", jpeg)
        swap.before("open") { swap.fifo("/data/in.jpg") }
        assertFailsWith<FaceMaskException.UnsupportedImage> { PathSourceRules.open("/data/in.jpg", swap) }
        assertEquals(0, swap.openDescriptors)
        // Missing: IoFailed without the path; other errors: IoFailed; a failing read closes the descriptor.
        val missing = assertFailsWith<FaceMaskException.IoFailed> { PathSourceRules.open("/data/none.jpg", fs) }
        assertTrue(missing.message!!.contains("does not exist"))
        assertNoPath(missing, "/data/none.jpg")
        val denied = FakeFiles()
        denied.file("/data/in.jpg", jpeg)
        denied.fail("open", OsConstants.EACCES)
        assertFailsWith<FaceMaskException.IoFailed> { PathSourceRules.open("/data/in.jpg", denied) }
        val unreadable = FakeFiles()
        unreadable.file("/data/in.jpg", jpeg)
        unreadable.fail("pread", OsConstants.EIO)
        assertFailsWith<FaceMaskException.IoFailed> { PathSourceRules.open("/data/in.jpg", unreadable) }
        assertEquals(0, unreadable.openDescriptors)
        val statFails = FakeFiles()
        statFails.file("/data/in.jpg", jpeg)
        statFails.fail("stat", OsConstants.EACCES)
        assertFailsWith<FaceMaskException.IoFailed> { PathSourceRules.open("/data/in.jpg", statFails) }
        // A failing close of a read-only source is ignored.
        val closeFails = FakeFiles()
        closeFails.file("/data/in.jpg", jpeg)
        val o = PathSourceRules.open("/data/in.jpg", closeFails)
        closeFails.fail("close", OsConstants.EIO)
        PathSourceRules.closeQuietly(o.fd, closeFails)
    }
}
