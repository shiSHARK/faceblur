// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.ReservedBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSFileManager
import platform.posix.EACCES
import platform.posix.EIO
import platform.posix.ENOTSUP
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.chmod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The writer's calls with a fault or an action injected before one call (tests only). */
internal class InjectedCalls(private val inner: IosWriteCalls = IosPosixCalls) : IosWriteCalls by inner {
    var before: (call: String, path: String?) -> Unit = { _, _ -> }
    var failAt: String? = null
    var failWith: Int = EIO
    val seen = ArrayList<String>()

    private fun step(call: String, path: String? = null) {
        seen += call
        before(call, path)
        if (failAt == call) throw PosixFailed(failWith)
    }

    override fun createExclusive(path: String): Int = step("create", path).let { inner.createExclusive(path) }

    override fun put(fd: Int, from: ByteArray, offset: Int, count: Int): Int =
        step("put").let { inner.put(fd, from, offset, count) }

    override fun sync(fd: Int) = step("sync").let { inner.sync(fd) }

    override fun hardLink(from: String, to: String) = step("link", from).let { inner.hardLink(from, to) }

    override fun replace(from: String, to: String) = step("rename", from).let { inner.replace(from, to) }
}

/**
 * ST-028 AC4 and ST-029 AC3 (write side) on the simulator: the iOS path writer behind `maskFile` (new file,
 * replace, overwrite of the source), refusals of links and other names, fault injection, the no-hard-link fallback
 * and the snapshot of the app's folders around the call.
 */
class IosPathWriterTest {
    private val dirs = ArrayList<String>()

    @AfterTest
    fun cleanUp() {
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        dirs.forEach { IosFiles.removeDir(it) }
        assertEquals(0L, ReservedBytes.current())
    }

    private fun jpeg(w: Int = 900, h: Int = 700): ByteArray {
        val img = IosFiles.imageOf(w, h, gradientPixels(w, h))
        val props = IosFiles.cannedProperties(6)
        try {
            return IosFiles.encode(img, "public.jpeg", props, xmp = true)
        } finally {
            platform.CoreFoundation.CFRelease(props)
            CGImageRelease(img)
        }
    }

    /** A folder with `in.jpg` (a JPEG with orientation 6, canaries, GPS and XMP); returns the folder. */
    private fun setUp(): String {
        val d = IosFiles.newDir().also { dirs += it }
        IosFiles.store("$d/in.jpg", jpeg())
        return d
    }

    private fun names(d: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val all = NSFileManager.defaultManager.contentsOfDirectoryAtPath(d, null) as List<String>
        return all.sorted()
    }

    private fun assertMasked(path: String) {
        val out = IosFiles.read(path)
        assertTrue(IosFiles.inspect(out).clean, IosFiles.inspect(out).toString())
        assertEquals(
            emptyList(),
            io.github.shishark.faceblur.filemode.OutputValidator.findings(
                out,
                io.github.shishark.faceblur.MetadataPolicy.StripAll,
            ),
        )
        IosFiles.keepForChecker("outputs", "writer-" + path.substringAfterLast('/'), out)
    }

    private fun mode(path: String): Int = IosPosixCalls.lstat(path).mode

    @Test
    fun aNewFileIsTheOnlyThingWrittenAndIsOwnerOnly() {
        val d = setUp()
        val input = IosFiles.read("$d/in.jpg")
        val before = IosFiles.snapshot()
        val r = IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg")) }
        assertTrue(r.masks.isNotEmpty())
        assertMasked("$d/out.jpg")
        assertContentEquals(input, IosFiles.read("$d/in.jpg"), "input unchanged")
        assertEquals(listOf("in.jpg", "out.jpg"), names(d))
        assertEquals(listOf("$d/out.jpg"), IosFiles.diff(before, IosFiles.snapshot()), "only the output was added")
        assertEquals(S_IFREG or 0x180, mode("$d/out.jpg") and (S_IFMT or 0x1FF), "owner-only regular file")
    }

    @Test
    fun duringTheCallOnlyTheMaskedTemporaryFileExistsNextToTheTarget() {
        for (replace in listOf(false, true)) {
            val d = setUp()
            if (replace) IosFiles.store("$d/out.jpg", byteArrayOf(1, 2, 3))
            val calls = InjectedCalls()
            var during: List<String>? = null
            var tempBytes: ByteArray? = null
            var tempMode = 0
            calls.before = { call, path ->
                if ((call == "link" || call == "rename") && path != null) {
                    during = names(d)
                    tempBytes = IosFiles.read(path)
                    tempMode = mode(path) and 0x1FF
                }
            }
            FaceBlurRuntime.installTargetWriter(IosPathWriter(calls))
            IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg", replace)) }
            val seen = requireNotNull(during)
            val temp = seen.single { it != "in.jpg" && it != "out.jpg" }
            assertTrue(temp.startsWith(".out.jpg.faceblur-") && temp.endsWith(".tmp"), temp)
            assertEquals(0x180, tempMode, "temporary file is owner-only")
            assertContentEquals(IosFiles.read("$d/out.jpg"), tempBytes, "it held only the masked output")
            assertEquals(listOf("in.jpg", "out.jpg"), names(d), "and is gone afterwards")
            assertEquals(if (replace) "rename" else "link", calls.seen.last { it == "link" || it == "rename" })
            assertFalse(replace && "link" in calls.seen, "a replace never links; a new file never renames")
            assertFalse(!replace && "rename" in calls.seen)
        }
    }

    @Test
    fun replaceExistingAndOverwriteSourceReplaceInOneStep() {
        val d = setUp()
        IosFiles.store("$d/out.jpg", ByteArray(500_000) { 9 })
        IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg", replaceExisting = true)) }
        assertMasked("$d/out.jpg")
        IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.OverwriteSource) }
        assertMasked("$d/in.jpg")
        assertEquals(listOf("in.jpg", "out.jpg"), names(d))
    }

    @Test
    fun anExistingFileLinksAndOtherNamesAreNeverReplaced() {
        val d = setUp()
        val planted = byteArrayOf(7, 7, 7)
        IosFiles.store("$d/exists.jpg", planted)
        IosFiles.store("$d/pointee.jpg", planted)
        IosFiles.store("$d/twice.jpg", planted)
        val fm = NSFileManager.defaultManager
        check(fm.createSymbolicLinkAtPath("$d/link.jpg", "$d/pointee.jpg", null))
        check(fm.createSymbolicLinkAtPath("$d/dangling.jpg", "$d/nothing-here.jpg", null))
        check(fm.linkItemAtPath("$d/twice.jpg", "$d/twice-other-name.jpg", null))
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        val cases = listOf(
            OutputTarget.NewFile("$d/exists.jpg"),
            OutputTarget.NewFile("$d/link.jpg"),
            OutputTarget.NewFile("$d/dangling.jpg"),
            OutputTarget.NewFile("$d/link.jpg", replaceExisting = true),
            OutputTarget.NewFile("$d/dangling.jpg", replaceExisting = true),
            OutputTarget.NewFile("$d/twice.jpg", replaceExisting = true),
            OutputTarget.NewFile(d, replaceExisting = true),
        )
        val before = names(d)
        for (t in cases) {
            assertFailsWith<FaceMaskException.UnsupportedTarget>(t.toString()) {
                IosFiles.masker().use { it.maskFile("$d/in.jpg", t) }
            }
        }
        assertEquals(0, codec.decodes, "every refusal came before decoding")
        assertEquals(before, names(d))
        for (n in listOf("exists.jpg", "pointee.jpg", "twice.jpg", "twice-other-name.jpg", "link.jpg")) {
            assertContentEquals(planted, IosFiles.read("$d/$n"), n)
        }
        // A source behind a symbolic link is refused by the read-only open (no link followed); nothing changes.
        assertFailsWith<FaceMaskException>("source link") {
            IosFiles.masker().use { it.maskFile("$d/link.jpg", OutputTarget.OverwriteSource) }
        }
        assertContentEquals(planted, IosFiles.read("$d/pointee.jpg"))
    }

    @Test
    fun aFaultAtAnyStepLeavesTheTargetAndNoTemporaryFile() {
        for (replace in listOf(false, true)) {
            for (at in listOf("create", "put", "sync", if (replace) "rename" else "link")) {
                val d = setUp()
                val old = byteArrayOf(4, 5, 6)
                if (replace) IosFiles.store("$d/out.jpg", old)
                val calls = InjectedCalls().apply { failAt = at }
                FaceBlurRuntime.installTargetWriter(IosPathWriter(calls))
                assertFailsWith<FaceMaskException.IoFailed>("$at replace=$replace") {
                    IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg", replace)) }
                }
                assertEquals(if (replace) listOf("in.jpg", "out.jpg") else listOf("in.jpg"), names(d), at)
                if (replace) assertContentEquals(old, IosFiles.read("$d/out.jpg"), at)
            }
        }
    }

    /** ADR 0005 decision 2: a location without a sibling temporary file is an `UnsupportedTarget`; nothing written. */
    @Test
    fun aLocationThatRefusesTheTemporaryFileIsAnUnsupportedTargetAndNothingIsWritten() {
        val targets = listOf<(String) -> OutputTarget>(
            { d -> OutputTarget.NewFile("$d/out.jpg") },
            { d -> OutputTarget.NewFile("$d/out.jpg", replaceExisting = true) },
            { _ -> OutputTarget.OverwriteSource },
        )
        for ((k, target) in targets.withIndex()) {
            for (code in listOf(EACCES, EPERM, EROFS)) {
                val d = setUp()
                val input = IosFiles.read("$d/in.jpg")
                val old = byteArrayOf(4, 5, 6)
                if (k == 1) IosFiles.store("$d/out.jpg", old)
                val calls = InjectedCalls().apply {
                    failAt = "create"
                    failWith = code
                }
                FaceBlurRuntime.installTargetWriter(IosPathWriter(calls))
                assertFailsWith<FaceMaskException.UnsupportedTarget>("target $k errno $code") {
                    IosFiles.masker().use { it.maskFile("$d/in.jpg", target(d)) }
                }
                assertEquals(1, calls.seen.count { it == "create" }, "no other file was tried")
                assertEquals(if (k == 1) listOf("in.jpg", "out.jpg") else listOf("in.jpg"), names(d), "$k $code")
                assertContentEquals(input, IosFiles.read("$d/in.jpg"), "source untouched")
                if (k == 1) assertContentEquals(old, IosFiles.read("$d/out.jpg"), "target untouched")
            }
        }
        FaceBlurRuntime.installTargetWriter(null)

        // The real calls in a folder without write permission: the same error, and nothing is added anywhere.
        val d = setUp()
        val input = IosFiles.read("$d/in.jpg")
        assertEquals(0, chmod(d, READ_AND_SEARCH.convert()))
        try {
            for (target in listOf(OutputTarget.NewFile("$d/out.jpg"), OutputTarget.OverwriteSource)) {
                val before = IosFiles.snapshot()
                assertFailsWith<FaceMaskException.UnsupportedTarget>(target.toString()) {
                    IosFiles.masker().use { it.maskFile("$d/in.jpg", target) }
                }
                assertEquals(emptyList(), IosFiles.diff(before, IosFiles.snapshot()), "nothing written")
            }
        } finally {
            chmod(d, OWNER_ALL.convert())
        }
        assertEquals(listOf("in.jpg"), names(d))
        assertContentEquals(input, IosFiles.read("$d/in.jpg"), "source untouched")
    }

    @Test
    fun cancellationAfterEncodingWritesNothing() {
        val d = setUp()
        var cancel = false
        val token = object : CancellationToken {
            override val isCancellationRequested: Boolean get() = cancel
        }
        val codec = ObservingCodec()
        FaceBlurRuntime.installImageCodec(codec)
        codec.afterEncode = { cancel = true }
        assertFailsWith<FaceMaskException.Cancelled> {
            IosFiles.masker().use {
                it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg"), cancellation = token)
            }
        }
        assertEquals(1, codec.encodes)
        assertEquals(listOf("in.jpg"), names(d))
    }

    @Test
    fun withoutHardLinksTheTargetIsCreatedExclusivelyAndNeverReplaced() {
        val d = setUp()
        val calls = InjectedCalls().apply {
            failAt = "link"
            failWith = ENOTSUP
        }
        FaceBlurRuntime.installTargetWriter(IosPathWriter(calls))
        IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg")) }
        assertMasked("$d/out.jpg")
        assertEquals(listOf("in.jpg", "out.jpg"), names(d))

        // A file that appears at the name after the check is never replaced (link: EEXIST).
        val late = byteArrayOf(8, 8)
        val racing = InjectedCalls().apply {
            before = { call, _ -> if (call == "link") IosFiles.store("$d/late.jpg", late) }
        }
        FaceBlurRuntime.installTargetWriter(IosPathWriter(racing))
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/late.jpg")) }
        }
        assertContentEquals(late, IosFiles.read("$d/late.jpg"))
        assertEquals(listOf("in.jpg", "late.jpg", "out.jpg"), names(d))
    }

    @Test
    fun aSourceSwappedDuringTheCallIsNotOverwritten() {
        val d = setUp()
        val original = IosFiles.read("$d/in.jpg")
        val other = jpeg(500, 400)
        val calls = InjectedCalls().apply {
            before = { call, _ ->
                if (call == "create") {
                    check(NSFileManager.defaultManager.moveItemAtPath("$d/in.jpg", "$d/moved.jpg", null))
                    IosFiles.store("$d/in.jpg", other)
                }
            }
        }
        FaceBlurRuntime.installTargetWriter(IosPathWriter(calls))
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.OverwriteSource) }
        }
        assertContentEquals(original, IosFiles.read("$d/moved.jpg"), "the moved original is unchanged")
        assertContentEquals(other, IosFiles.read("$d/in.jpg"), "the new file at the path is unchanged")
        assertEquals(listOf("in.jpg", "moved.jpg"), names(d))
    }

    @Test
    fun theSnapshotSeesACopyOfTheInput() {
        val d = setUp()
        val copy = platform.Foundation.NSTemporaryDirectory() + "faceblur-copy-of-input.bin"
        val codec = ObservingCodec()
        codec.beforeOpen = { s ->
            if (s is io.github.shishark.faceblur.filemode.FileSource.Path) IosFiles.store(copy, IosFiles.read(s.path))
        }
        FaceBlurRuntime.installImageCodec(codec)
        val before = IosFiles.snapshot()
        IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/out.jpg")) }
        val diff = IosFiles.diff(before, IosFiles.snapshot())
        IosFiles.removeDir(copy)
        assertTrue(diff.any { it.endsWith("faceblur-copy-of-input.bin") }, "a copying build variant fails the check")
    }

    @Test
    fun temporaryNamesKeepAtMost64BytesOfTheName() {
        assertEquals("a".repeat(64), IosPathWriter.namePart("a".repeat(100)))
        assertEquals("é".repeat(32), IosPathWriter.namePart("é".repeat(40)))
        assertEquals("😀".repeat(16), IosPathWriter.namePart("😀".repeat(20)))
        assertEquals("ab😀", IosPathWriter.namePart("ab😀"))
    }

    private companion object {
        /** `0500`: the owner may list and enter the folder, not add a file. */
        const val READ_AND_SEARCH = 0x140

        /** `0700`: restored so that the folder can be removed. */
        const val OWNER_ALL = 0x1C0
    }
}
