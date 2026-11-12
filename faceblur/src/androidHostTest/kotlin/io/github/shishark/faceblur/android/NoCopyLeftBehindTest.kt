// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.Context
import android.system.OsConstants
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskJpeg
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * No copy of a photo is left behind (PRIV-005): file mode writes nothing to disk except the masked output, and a
 * temporary file of that output next to the target that is gone when the call returns. Runs the public path forms and
 * the stream form end to end on the host's real file system ([HostFiles], Robolectric native graphics), with failures
 * injected at every file call of the writer and a cancellation requested at every cancellation check of the call:
 * - success: the target directory holds exactly the files it held before plus the target;
 * - failure or cancellation: the target directory is unchanged, byte for byte (an existing target and an overwritten
 *   source included), and no temporary file is left;
 * - always: nothing new or changed under the app's cache, files, no-backup, code-cache and data directories or the
 *   JVM's temporary directory (outside this test's own working directory).
 * The two controls plant the leaks these checks look for (a stray file in the cache directory, a temporary file left
 * next to the target) and require that the same checks report them.
 * Content-URI targets are not covered here: `overwriteNonAtomic` replaces the content in place by design (opt-in) and
 * truncates it to zero on failure, see ContentUriTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NoCopyLeftBehindTest {
    /** [FileCalls] on [HostFiles] that records the calls and fails the named one ([failAt]) with EIO. */
    private class Recording(private val inner: FileCalls<HostFiles.Fd> = HostFiles) : FileCalls<HostFiles.Fd> by inner {
        val openFlags = ArrayList<Int>()
        val touched = ArrayList<String>()
        var failAt: String? = null
        var leakTo: File? = null

        private fun maybeFail(call: String) {
            if (failAt == call) throw DescriptorFailed(OsConstants.EIO)
        }

        override fun open(path: String, flags: Int): HostFiles.Fd {
            openFlags += flags
            maybeFail("open")
            return inner.open(path, flags)
        }

        override fun create(path: String, flags: Int, mode: Int): HostFiles.Fd {
            touched += path
            maybeFail("create")
            return inner.create(path, flags, mode)
        }

        override fun write(fd: HostFiles.Fd, from: ByteArray, offset: Int, count: Int): Int {
            maybeFail("write")
            // Control only: a writer that leaks a copy of what it writes.
            leakTo?.writeBytes(from.copyOfRange(offset, offset + count))
            return inner.write(fd, from, offset, count)
        }

        override fun fsync(fd: HostFiles.Fd) {
            maybeFail("fsync")
            inner.fsync(fd)
        }

        override fun link(from: String, to: String) {
            touched += to
            maybeFail("link")
            inner.link(from, to)
        }

        override fun rename(from: String, to: String) {
            touched += to
            maybeFail("rename")
            inner.rename(from, to)
        }

        override fun unlink(path: String) {
            touched += path
            maybeFail("unlink")
            inner.unlink(path)
        }
    }

    /** Cancels at its [at]-th check (1-based); counts the checks. */
    private class CancelAt(private val at: Int) : CancellationToken {
        var checks = 0

        override val isCancellationRequested: Boolean get() = ++checks >= at
    }

    private lateinit var calls: Recording
    private lateinit var masker: FaceMasker
    private lateinit var work: File
    private lateinit var out: File
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        calls = Recording()
        FaceBlurRuntime.installImageCodec(AndroidImageCodec.using(calls))
        FaceBlurRuntime.installTargetWriter(AndroidTargetWriter.using(calls))
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        work = Files.createTempDirectory("faceblur-no-copy").toFile()
        out = File(work, "out").apply { mkdirs() }
        // The build gives this JVM a temporary directory of its own; the shared system one cannot be watched.
        assertTrue(System.getProperty("java.io.tmpdir")!!.contains("host-test-jvm-tmp"), "own temporary directory")
        // Warm-up outside the watched window: the first native graphics use may unpack libraries.
        TestImages.jpeg(16, 16)
    }

    @After
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        work.deleteRecursively()
    }

    // --- the checks ---------------------------------------------------------------------------------------------

    /** The directories a stray copy could land in (the app's own storage and the JVM's temporary directory). */
    private fun watched(): List<File> = listOfNotNull(
        context.cacheDir,
        context.filesDir,
        context.noBackupFilesDir,
        context.codeCacheDir,
        context.dataDir,
        context.externalCacheDir,
        context.getExternalFilesDir(null),
        File(System.getProperty("java.io.tmpdir")!!),
    ).distinct()

    /**
     * Path -> size, modification time and SHA-256 of every file under [roots] (recursively), except under this test's
     * own [work] directory.
     */
    private fun snapshot(roots: List<File>): Map<String, String> {
        val result = HashMap<String, String>()
        val skip = work.canonicalPath
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().onEnter { it.canonicalPath != skip }.filter { it.isFile }.forEach { f ->
                result[f.canonicalPath] = "${f.length()}:${f.lastModified()}:${sha256(f.readBytes())}"
            }
        }
        return result
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Entries that are new or changed in [after] compared with [before]. */
    private fun differences(before: Map<String, String>, after: Map<String, String>): List<String> =
        after.filter { (path, hash) -> before[path] != hash }.keys.sorted() +
            before.keys.filter { it !in after }.sorted().map { "removed $it" }

    /** Name -> SHA-256 of every entry of the target directory. */
    private fun targetDir(): Map<String, String> =
        out.listFiles()!!.associate { it.name to (if (it.isFile) sha256(it.readBytes()) else "dir") }

    /**
     * Runs [call] and returns what it changed: in the target directory (by name) and in the watched directories.
     * The watched snapshot is taken around the call only.
     */
    private fun changes(call: () -> Unit): Pair<List<String>, List<String>> {
        val dirBefore = targetDir()
        val watchedBefore = snapshot(watched())
        call()
        val dirChanges = differences(dirBefore, targetDir())
        return dirChanges to differences(watchedBefore, snapshot(watched()))
    }

    private fun source(name: String = "in.jpg"): File = File(out, name).apply { writeBytes(TestImages.jpeg(64, 48)) }

    private fun noTemporaryFile() =
        assertEquals(emptyList(), out.list()!!.filter { it.contains(".faceblur-") }, "no temporary file is left")

    // --- success --------------------------------------------------------------------------------------------------

    @Test
    fun aSuccessfulCallAddsExactlyTheTargetAndWritesNothingElseAnywhere() {
        val input = source()
        val target = File(out, "masked.jpg")
        val (dir, elsewhere) = changes { masker.maskFile(input.path, OutputTarget.NewFile(target.path)) }
        assertEquals(listOf("masked.jpg"), dir, "the target directory gains exactly the target")
        assertEquals(emptyList(), elsewhere, "nothing written to the app's storage or the temporary directory")
        noTemporaryFile()
        // Replacing an existing target and overwriting the source change exactly that one entry.
        val (dir2, elsewhere2) = changes { masker.maskFile(input.path, OutputTarget.NewFile(target.path, true)) }
        assertTrue(dir2 == listOf("masked.jpg") || dir2.isEmpty(), "only the target may change: $dir2")
        assertEquals(emptyList(), elsewhere2)
        val (dir3, elsewhere3) = changes { masker.maskFile(input.path, OutputTarget.OverwriteSource) }
        assertEquals(listOf("in.jpg"), dir3, "the source is replaced by its masked version")
        assertEquals(emptyList(), elsewhere3)
        noTemporaryFile()
        // The stream form writes nothing to disk at all.
        val sink = ByteArrayOutputStream()
        val (dir4, elsewhere4) = changes {
            masker.maskJpeg(ByteArrayInputStream(TestImages.jpeg(64, 48)), sink)
        }
        assertEquals(emptyList(), dir4)
        assertEquals(emptyList(), elsewhere4)
        assertTrue(sink.size() > 0)
    }

    // --- injected failures ------------------------------------------------------------------------------------------

    @Test
    fun aFailureAtAnyFileCallOfTheWriterLeavesTheDirectoryByteIdenticalAndNoTemporaryFile() {
        val input = source()
        val existing = File(out, "existing.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val sourceBytes = input.readBytes()
        val targets = listOf(
            "new file" to { OutputTarget.NewFile(File(out, "fresh.jpg").path) },
            "replace" to { OutputTarget.NewFile(existing.path, true) },
            "overwrite source" to { OutputTarget.OverwriteSource },
        )
        var failures = 0
        for ((label, target) in targets) {
            for (call in listOf("create", "write", "fsync", "link", "rename")) {
                calls.failAt = call
                calls.touched.clear()
                var thrown: Throwable? = null
                val (dir, elsewhere) = changes {
                    thrown = runCatching { masker.maskFile(input.path, target()) }.exceptionOrNull()
                }
                calls.failAt = null
                if (thrown == null) {
                    // This route does not make that call (e.g. no link when replacing): the call must have succeeded.
                    assertTrue(calls.touched.isNotEmpty(), "$label/$call: a successful call wrote its target")
                    out.listFiles()!!.filter { it.name == "fresh.jpg" }.forEach { it.delete() }
                    existing.writeBytes(byteArrayOf(1, 2, 3, 4))
                    input.writeBytes(sourceBytes)
                    continue
                }
                failures++
                assertTrue(thrown is FaceMaskException, "$label/$call: a FaceMaskException, not $thrown")
                assertEquals(emptyList(), dir, "$label/$call: the target directory is unchanged")
                assertEquals(emptyList(), elsewhere, "$label/$call: nothing written elsewhere")
                assertContentEquals(byteArrayOf(1, 2, 3, 4), existing.readBytes(), "$label/$call: existing target")
                assertContentEquals(sourceBytes, input.readBytes(), "$label/$call: the source is unchanged")
                noTemporaryFile()
            }
        }
        assertTrue(failures >= 12, "the injected failures were reached ($failures)")
    }

    @Test
    fun aCancellationAtAnyCheckLeavesTheDirectoryByteIdenticalAndNoTemporaryFile() {
        val input = source()
        val existing = File(out, "existing.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        // Calibration: how many checks a whole call makes.
        val count = CancelAt(Int.MAX_VALUE)
        masker.maskFile(input.path, OutputTarget.NewFile(File(out, "calibration.jpg").path), cancellation = count)
        File(out, "calibration.jpg").delete()
        assertTrue(count.checks > 2, "the call checks cancellation (${count.checks})")
        val sourceBytes = input.readBytes()
        for (at in 1..count.checks) {
            for (target in listOf(
                OutputTarget.NewFile(File(out, "fresh.jpg").path),
                OutputTarget.NewFile(existing.path, true),
            )) {
                var thrown: Throwable? = null
                val (dir, elsewhere) = changes {
                    thrown = runCatching { masker.maskFile(input.path, target, cancellation = CancelAt(at)) }
                        .exceptionOrNull()
                }
                assertTrue(
                    thrown is FaceMaskException.Cancelled,
                    "check $at of ${count.checks}: cancelled, not $thrown",
                )
                assertEquals(emptyList(), dir, "check $at: the target directory is unchanged")
                assertEquals(emptyList(), elsewhere, "check $at: nothing written elsewhere")
                assertContentEquals(byteArrayOf(9, 8, 7), existing.readBytes())
                assertContentEquals(sourceBytes, input.readBytes())
                noTemporaryFile()
            }
        }
        // The stream form: a cancelled call hands nothing to the stream and writes nothing to disk.
        val sink = ByteArrayOutputStream()
        val (dir, elsewhere) = changes {
            assertFailsWith<FaceMaskException.Cancelled> {
                masker.maskJpeg(ByteArrayInputStream(TestImages.jpeg(64, 48)), sink, cancellation = CancelAt(1))
            }
        }
        assertEquals(0, sink.size())
        assertEquals(emptyList(), dir)
        assertEquals(emptyList(), elsewhere)
    }

    // --- the read-only opens and where the writer may write (scripts/sast/write-sites.txt, read-open row) ----------

    @Test
    fun everyPlainOpenIsReadOnlyAndEveryMutationStaysNextToTheTarget() {
        val writeFlags =
            OsConstants.O_WRONLY or OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_TRUNC or
                OsConstants.O_APPEND
        listOf(OsConstants.O_WRONLY, OsConstants.O_RDWR, OsConstants.O_CREAT, OsConstants.O_TRUNC, OsConstants.O_APPEND)
            .forEach { assertNotEquals(0, it, "the host has real open flags") }
        val input = source()
        val target = File(out, "masked.jpg")
        masker.detectFile(input.path)
        masker.maskFile(input.path, OutputTarget.NewFile(target.path))
        masker.maskFile(input.path, OutputTarget.NewFile(target.path, true))
        masker.maskFile(input.path, OutputTarget.OverwriteSource)
        val model = File(work, "model.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        ModelFileRules.read(model.path, 1024, calls)
        assertTrue(calls.openFlags.size >= 5, "opens were recorded (${calls.openFlags.size})")
        calls.openFlags.forEach { flags -> assertEquals(0, flags and writeFlags, "open flags $flags are read-only") }
        val names = setOf("masked.jpg", "in.jpg")
        calls.touched.forEach { path ->
            val f = File(path)
            assertEquals(out.canonicalPath, f.parentFile!!.canonicalPath, "every mutation is next to the target: $path")
            assertTrue(
                f.name in names || Regex("""^\.(masked|in)\.jpg\.faceblur-[^/]+\.tmp$""").matches(f.name),
                "only the target or its temporary sibling: ${f.name}",
            )
        }
        noTemporaryFile()
    }

    // --- controls: the checks above find a planted leak ------------------------------------------------------------

    @Test
    fun controlAStrayCopyInTheCacheDirectoryIsDetected() {
        val input = source()
        calls.leakTo = File(context.cacheDir, "stray-copy.bin")
        val (dir, elsewhere) = changes { masker.maskFile(input.path, OutputTarget.NewFile(File(out, "m.jpg").path)) }
        calls.leakTo = null
        assertEquals(listOf("m.jpg"), dir)
        assertEquals(
            listOf(File(context.cacheDir, "stray-copy.bin").canonicalPath),
            elsewhere,
            "the stray copy is found",
        )
        File(context.cacheDir, "stray-copy.bin").delete()
        // The same for the JVM's temporary directory.
        val tmp = File(System.getProperty("java.io.tmpdir")!!, "faceblur-stray-${System.nanoTime()}.bin")
        try {
            calls.leakTo = tmp
            val (_, elsewhere2) = changes { masker.maskFile(input.path, OutputTarget.NewFile(File(out, "n.jpg").path)) }
            assertTrue(
                tmp.canonicalPath in elsewhere2,
                "the stray copy in the temporary directory is found: $elsewhere2",
            )
        } finally {
            calls.leakTo = null
            tmp.delete()
        }
    }

    @Test
    fun controlATemporaryFileLeftNextToTheTargetIsDetected() {
        val input = source()
        val leaky = object : FileCalls<HostFiles.Fd> by calls {
            override fun unlink(path: String) = Unit // never removes the temporary file
        }
        FaceBlurRuntime.installTargetWriter(AndroidTargetWriter.using(leaky))
        val (dir, _) = changes { masker.maskFile(input.path, OutputTarget.NewFile(File(out, "m.jpg").path)) }
        assertEquals(2, dir.size, "the target and the leftover temporary file: $dir")
        assertTrue(dir.any { it.contains(".faceblur-") })
        assertFailsWith<AssertionError> { noTemporaryFile() }
    }
}
