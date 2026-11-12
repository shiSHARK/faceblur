// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detectFile
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.maskFile
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content-URI sources and targets (ADR 0005 decision 4) against a test provider whose behaviour each test sets: the
 * probe before any write, `"wt"` and never `"w"`, the explicit truncation, the full read-back, the empty-target rule of
 * `AndroidOutputTargets.newFile` and the explicit opt-in `overwriteNonAtomic`. Robolectric with native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ContentUriTest {
    /** A provider of files in a test directory; [modes] records every open, the flags change its behaviour. */
    class TestProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode
            val f = File(root, uri.lastPathSegment ?: throw FileNotFoundException())
            if (mode == "r" && refuseRead) throw FileNotFoundException("/secret/provider/path")
            if (mode != "r" && refuseWriteModes.contains(mode)) throw FileNotFoundException("/secret/provider/path")
            if (mode == "r" && staleRead != null) {
                return ParcelFileDescriptor.open(staleRead, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val effective = if (mode == "wt" && ignoreTruncate) "w" else mode
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(effective))
        }

        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

        companion object {
            lateinit var root: File
            val modes = ArrayList<String>()
            var refuseRead = false
            var refuseWriteModes = setOf<String>()
            var ignoreTruncate = false
            var staleRead: File? = null

            fun reset(dir: File) {
                root = dir
                modes.clear()
                refuseRead = false
                refuseWriteModes = emptySet()
                ignoreTruncate = false
                staleRead = null
            }
        }
    }

    private val authority = "io.github.shishark.faceblur.test.provider"
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private lateinit var masker: FaceMasker
    private val bytes = ByteArray(5000) { (it * 13).toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("faceblur-content").toFile()
        TestProvider.reset(dir)
        Robolectric.setupContentProvider(TestProvider::class.java, authority)
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
    }

    @After
    fun tearDown() {
        masker.close()
        dir.deleteRecursively()
    }

    private fun uri(name: String): Uri = Uri.parse("content://$authority/$name")

    private fun file(name: String, content: ByteArray? = ByteArray(0)): File =
        File(dir, name).apply { if (content != null) writeBytes(content) }

    private val resolver get() = context.contentResolver

    private fun assertClean(e: Throwable) {
        assertFalse(e.message.orEmpty().contains("secret") || e.message.orEmpty().contains(authority), e.message)
        assertNull(e.cause)
    }

    @Test
    fun aNewDocumentIsWrittenWithWtTruncatedAndReadBack() {
        val f = file("new.jpg")
        ContentUriWriter.write(resolver, uri("new.jpg"), overwrite = false, bytes)
        assertContentEquals(bytes, f.readBytes())
        assertEquals(listOf("r", "wt", "r"), TestProvider.modes, "probe, write, read-back; never \"w\"")
    }

    @Test
    fun aNonEmptyTargetNeedsTheExplicitOptInAndIsThenReplacedExactly() {
        val f = file("doc.jpg", ByteArray(20_000) { 1 })
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = false, bytes)
        }
        assertTrue(e.message!!.contains("overwriteNonAtomic"))
        assertEquals(listOf("r"), TestProvider.modes, "nothing opened for writing")
        assertEquals(20_000, f.length().toInt())
        ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = true, bytes)
        assertContentEquals(bytes, f.readBytes(), "the longer old content is cut to the output's length")
    }

    @Test
    fun aProviderThatIgnoresTruncationIsCorrectedByTheExplicitTruncate() {
        val f = file("doc.jpg", ByteArray(20_000) { 1 })
        TestProvider.ignoreTruncate = true
        ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = true, bytes)
        assertContentEquals(bytes, f.readBytes(), "no bytes of the original remain after the output")
    }

    @Test
    fun aProviderThatRefusesWtOrReadingIsRefusedWithoutWritingAndWithoutFallingBackToW() {
        val f = file("doc.jpg", ByteArray(0))
        TestProvider.refuseWriteModes = setOf("wt")
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = false, bytes)
        }
        assertClean(e)
        assertFalse("w" in TestProvider.modes, "never \"w\"")
        assertEquals(0L, f.length())
        TestProvider.reset(dir)
        TestProvider.refuseRead = true
        val r = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            ContentUriWriter.check(resolver, uri("doc.jpg"), overwrite = true)
        }
        assertClean(r)
        assertEquals(listOf("r"), TestProvider.modes)
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = true, bytes)
        }
        assertFalse(TestProvider.modes.any { it != "r" }, "an unreadable target is refused before any write mode")
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            ContentUriWriter.check(resolver, uri("missing.jpg"), overwrite = false)
        }
    }

    /**
     * Security L-2: any throwable inside the open write window (an `Error` as well as an exception) truncates the
     * target to 0 bytes and closes it before it passes; nothing of the original or of a partial output stays.
     */
    @Test
    fun anyFailureInsideTheWriteWindowTruncatesTheTargetFirst() {
        val original = ByteArray(20_000) { 7 }
        val planted = OutOfMemoryError("planted")
        val f = file("error.jpg", original)
        val thrown = assertFailsWith<OutOfMemoryError> {
            ContentUriWriter.write(resolver, uri("error.jpg"), overwrite = true, bytes) { out, b ->
                out.write(b, 0, b.size / 2)
                throw planted
            }
        }
        assertTrue(thrown === planted, "the Error passes unchanged")
        assertEquals(0L, f.length(), "the target is cut to 0 bytes")
        assertEquals(listOf("r", "wt"), TestProvider.modes, "no read-back after the failure")
        TestProvider.modes.clear()
        val g = file("exception.jpg", original)
        val e = assertFailsWith<FaceMaskException.IoFailed> {
            ContentUriWriter.write(resolver, uri("exception.jpg"), overwrite = true, bytes) { out, b ->
                out.write(b, 0, 10)
                throw IllegalStateException("/secret/provider/path")
            }
        }
        assertClean(e)
        assertEquals(0L, g.length(), "the target is cut to 0 bytes")
    }

    @Test
    fun aReadBackThatDiffersFailsTheCall() {
        // The provider serves another (stale) file for "r": first empty, so the read-back size differs.
        val target = file("doc.jpg", ByteArray(0))
        TestProvider.staleRead = file("stale.jpg", ByteArray(0))
        val e = assertFailsWith<FaceMaskException.IoFailed> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = false, bytes)
        }
        assertTrue(e.message!!.contains("treat it as unmasked"))
        assertClean(e)
        assertContentEquals(bytes, target.readBytes(), "the write itself happened; the call still fails")
        // Same size, other bytes.
        TestProvider.staleRead = file("stale.jpg", ByteArray(bytes.size) { 9 })
        assertFailsWith<FaceMaskException.IoFailed> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = true, bytes)
        }
        // Longer than the output (bytes after its end).
        TestProvider.staleRead = file("stale.jpg", bytes + byteArrayOf(1))
        assertFailsWith<FaceMaskException.IoFailed> {
            ContentUriWriter.write(resolver, uri("doc.jpg"), overwrite = true, bytes)
        }
    }

    @Test
    fun contentSourcesAreDecodedAndMaskedIntoAContentTarget() {
        // Robolectric's decoder reads no provider descriptor: the content source is decoded from the provider's bytes
        // (read through the same ContentResolver); everything else is the production codec and writer.
        val bytesFromProvider = { r: android.content.ContentResolver, u: Uri ->
            android.graphics.ImageDecoder.createSource(
                java.nio.ByteBuffer.wrap(
                    r.openInputStream(u)!!.use {
                        it.readBytes()
                    },
                ),
            )
        }
        io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installImageCodec(
            AndroidImageCodec.using(HostFiles, bytesFromProvider),
        )
        try {
            contentRoundTrip()
        } finally {
            io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installImageCodec(null)
        }
    }

    private fun contentRoundTrip() {
        val source = TestImages.jpeg(320, 240)
        file("in.jpg", source)
        val out = file("out.jpg")
        val detection = masker.detectFile(context, uri("in.jpg"))
        assertEquals(320 to 240, detection.width to detection.height)
        val result = masker.maskFile(context, uri("in.jpg"), AndroidOutputTargets.newFile(uri("out.jpg")))
        val unmasked = PixelChecks.decode(source)
        TestImages.assertMaskedFile(
            out.readBytes(),
            PixelChecks.reference(unmasked) {
                masker.maskInPlace(it)
            },
            unmasked,
            result.masks,
        )
        // The source itself, replaced through the explicit opt-in.
        masker.maskFile(context, uri("in.jpg"), AndroidOutputTargets.overwriteNonAtomic(uri("in.jpg")))
        assertEquals(320 to 240, TestImages.decodedSize(File(dir, "in.jpg").readBytes()))
        assertFalse(File(dir, "in.jpg").readBytes().contentEquals(source))
    }

    @Test
    fun overwriteSourceOfAContentSourceIsRefusedBeforeItIsOpened() {
        file("in.jpg", TestImages.jpeg(64, 48))
        TestProvider.modes.clear()
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(context, uri("in.jpg"), OutputTarget.OverwriteSource)
        }
        assertTrue(e.message!!.contains("overwriteNonAtomic"))
        assertEquals(emptyList(), TestProvider.modes, "the source is never opened")
        // Other schemes are refused with fixed text.
        val s = assertFailsWith<FaceMaskException.IoFailed> {
            masker.detectFile(context, Uri.parse("https://example.invalid/a.jpg"))
        }
        assertClean(s)
        val t = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            AndroidTargetWriter.DEFAULT.check(
                AndroidOutputTargets.newFile(Uri.parse("https://example.invalid/b")),
                AndroidSources.of(uri("in.jpg")),
                AndroidSources.call(resolver),
            )
        }
        assertClean(t)
        // A foreign platform value and a content target without a platform context are refused by the writer too.
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            AndroidTargetWriter.DEFAULT.check(
                AndroidOutputTargets.newFile(uri("out.jpg")),
                AndroidSources.of(uri("in.jpg")),
                CallContext(null),
            )
        }
    }

    @Test
    fun fileUrisFollowThePathRules() {
        // The production writer on android.system.Os does nothing on the host; the path rules on the host file
        // system are installed instead.
        io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installImageCodec(
            AndroidImageCodec.using(HostFiles),
        )
        io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installTargetWriter(
            AndroidTargetWriter.using(HostFiles),
        )
        try {
            val input = file("in.jpg", TestImages.jpeg(64, 48))
            val existing = file("existing.jpg", "OLD".encodeToByteArray())
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                masker.maskFile(context, Uri.fromFile(input), AndroidOutputTargets.newFile(Uri.fromFile(existing)))
            }
            assertContentEquals("OLD".encodeToByteArray(), existing.readBytes())
            masker.maskFile(
                context,
                Uri.fromFile(input),
                AndroidOutputTargets.overwriteNonAtomic(Uri.fromFile(existing)),
            )
            assertEquals(64 to 48, TestImages.decodedSize(existing.readBytes()))
            masker.maskFile(context, Uri.fromFile(input), OutputTarget.OverwriteSource)
            assertEquals(64 to 48, TestImages.decodedSize(input.readBytes()))
        } finally {
            io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installImageCodec(null)
            io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime.installTargetWriter(null)
        }
    }

    @Test
    fun targetsCompareByUriAndOptInAndShowNoUri() {
        val a = AndroidOutputTargets.newFile(uri("x.jpg"))
        assertEquals(a, AndroidOutputTargets.newFile(uri("x.jpg")))
        assertEquals(a.hashCode(), AndroidOutputTargets.newFile(uri("x.jpg")).hashCode())
        assertNotEquals(a, AndroidOutputTargets.overwriteNonAtomic(uri("x.jpg")))
        assertNotEquals(a, AndroidOutputTargets.newFile(uri("y.jpg")))
        assertEquals("PlatformTarget(newFile)", a.toString())
        assertEquals(
            "PlatformTarget(overwriteNonAtomic)",
            AndroidOutputTargets.overwriteNonAtomic(uri("x.jpg")).toString(),
        )
        assertEquals("FileSource.Platform(content)", AndroidSources.of(uri("x.jpg")).toString())
        assertEquals("ContentSource", ContentSource(uri("x.jpg")).toString())
        assertEquals("CallContext(android)", AndroidSources.call(resolver).toString())
        val path = AndroidSources.of(Uri.fromFile(File("/data/x.jpg")))
        assertTrue(path is FileSource.Path && path.path == "/data/x.jpg")
        assertFailsWith<FaceMaskException.IoFailed> { AndroidSources.of(Uri.parse("file://")) }
        assertFailsWith<FaceMaskException.UnsupportedTarget> { AndroidSources.resolver(CallContext(null)) }
        val writer = AndroidTargetWriter.DEFAULT
        assertTrue(writer.accepts(AndroidTargetValue(uri("x"), false)))
        assertFalse(
            writer.accepts(object : io.github.shishark.faceblur.PlatformTargetValue {
                override val kind = "foreign"
            }),
        )
        assertEquals(File("rel.jpg").absolutePath, writer.absolutePath("rel.jpg"))
    }
}
