// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.net.Uri
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android path forms end to end on the host: the Android codec ([AndroidImageCodec], Robolectric native graphics)
 * and the Android writer ([AndroidTargetWriter], [PathWriteRules]) on the host's real file system ([HostFiles]),
 * through the public `detectFile` / `maskFile`, with a fake model that reports one face. Every written file is decoded
 * again and checked pixel by pixel inside the masks ([TestImages.assertMaskedFile]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HostFilesTest {
    private lateinit var masker: FaceMasker
    private lateinit var dir: File

    @Before
    fun setUp() {
        FaceBlurRuntime.installImageCodec(AndroidImageCodec.using(HostFiles))
        FaceBlurRuntime.installTargetWriter(AndroidTargetWriter.using(HostFiles))
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        dir = Files.createTempDirectory("faceblur-host").toFile()
    }

    @After
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        dir.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun reference(unmasked: Bitmap): Bitmap = PixelChecks.reference(unmasked) { masker.maskInPlace(it) }

    private fun noTemporaryFiles() =
        assertEquals(emptyList(), dir.list()!!.filter { it.contains(".faceblur-") }, "no temporary file is left")

    private fun assertNoPath(e: Throwable) {
        assertFalse(e.message.orEmpty().contains(dir.name), "no path in: ${e.message}")
        assertNull(e.cause)
        assertEquals(0, e.suppressed.size)
    }

    @Test
    fun aPathSourceIsMaskedAndTheWrittenFileHoldsTheMaskedPixels() {
        val source = TestImages.jpeg(640, 480)
        val input = write("in.jpg", source)
        val out = File(dir, "out.jpg")
        val result = masker.maskFile(input.path, OutputTarget.NewFile(out.path))
        val bytes = out.readBytes()
        assertEquals(bytes.size.toLong(), result.outputBytes)
        assertEquals(640 to 480, result.outputWidth to result.outputHeight)
        assertFalse(result.gainMapRemoved)
        val unmasked = PixelChecks.decode(source)
        TestImages.assertMaskedFile(bytes, reference(unmasked), unmasked, result.masks)
        assertContentEquals(source, input.readBytes(), "the source is untouched")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(out.toPath()),
            "the output keeps the temporary file's owner-only mode",
        )
        noTemporaryFiles()
        assertEquals(0, HostFiles.openCount, "every descriptor is closed")
    }

    @Test
    fun aPlanFromDetectFileFitsMaskFileAndTheOrientationIsBakedIn() {
        val plain = TestImages.jpeg(640, 480)
        val input = write("turned.jpg", TestImages.withOrientation(plain, 6))
        val detection = masker.detectFile(input.path)
        assertEquals(480 to 640, detection.width to detection.height, "orientation 6 turns the picture")
        assertEquals(480 to 640, detection.outputWidth to detection.outputHeight)
        val out = File(dir, "out.jpg")
        val result = masker.maskFile(input.path, OutputTarget.NewFile(out.path), plan = detection.toMaskPlan())
        assertEquals(detection.toMaskPlan().masks, result.masks)
        assertEquals(emptyList(), result.faces, "a given plan is applied instead of detection")
        val unmasked = PixelChecks.rotate90(PixelChecks.decode(plain))
        TestImages.assertMaskedFile(out.readBytes(), reference(unmasked), unmasked, result.masks)
        // A smaller output is scaled after masking; coordinates stay in the working image.
        val small = File(dir, "small.jpg")
        val r2 = masker.maskFile(input.path, OutputTarget.NewFile(small.path), FileOptions(maxOutputLongSide = 320))
        assertEquals(240 to 320, TestImages.decodedSize(small.readBytes()))
        assertEquals(480 to 640, r2.width to r2.height)
    }

    @Test
    fun existingFilesLinksAndFilesWithOtherNamesAreNeverReplacedWrongly() {
        val source = TestImages.jpeg(320, 240)
        val input = write("in.jpg", source)
        val existing = write("existing.jpg", "OLD".encodeToByteArray())
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(input.path, OutputTarget.NewFile(existing.path))
        }
        assertNoPath(e)
        assertContentEquals("OLD".encodeToByteArray(), existing.readBytes())
        masker.maskFile(input.path, OutputTarget.NewFile(existing.path, replaceExisting = true))
        assertEquals(320 to 240, TestImages.decodedSize(existing.readBytes()))

        // A symbolic link as target is refused; the file it points to keeps its content.
        val pointed = write("pointed.jpg", "KEEP".encodeToByteArray())
        val link = File(dir, "link.jpg")
        Files.createSymbolicLink(link.toPath(), pointed.toPath())
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(input.path, OutputTarget.NewFile(link.path, replaceExisting = true))
        }
        assertContentEquals("KEEP".encodeToByteArray(), pointed.readBytes())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        // A file with a second hard link is refused for replacement.
        val twice = write("twice.jpg", "TWICE".encodeToByteArray())
        Files.createLink(File(dir, "twice-other.jpg").toPath(), twice.toPath())
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(input.path, OutputTarget.NewFile(twice.path, replaceExisting = true))
        }
        assertContentEquals("TWICE".encodeToByteArray(), File(dir, "twice-other.jpg").readBytes())
        // NewFile at the source path without replaceExisting is refused before anything is opened.
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(input.path, OutputTarget.NewFile(input.path))
        }
        assertContentEquals(source, input.readBytes())
        noTemporaryFiles()
    }

    @Test
    fun overwriteSourceReplacesTheDecodedFileButNeverALinkedOrMultiplyLinkedSource() {
        val source = TestImages.jpeg(320, 240)
        val input = write("in.jpg", source)
        val result = masker.maskFile(input.path, OutputTarget.OverwriteSource)
        val unmasked = PixelChecks.decode(source)
        TestImages.assertMaskedFile(input.readBytes(), reference(unmasked), unmasked, result.masks)

        // A symbolic link to the original: replacing the link would leave the unmasked original in place.
        val original = write("original.jpg", source)
        val link = File(dir, "link.jpg")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(link.path, OutputTarget.OverwriteSource)
        }
        assertNoPath(e)
        assertContentEquals(source, original.readBytes(), "the original is untouched")
        // A source with another hard link.
        val linked = write("linked.jpg", source)
        Files.createLink(File(dir, "linked-other.jpg").toPath(), linked.toPath())
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(linked.path, OutputTarget.OverwriteSource)
        }
        assertContentEquals(source, linked.readBytes())
        // The source replaced by another file during the call (after it was opened): refused, nothing written.
        val swapped = write("swapped.jpg", source)
        val writer = AndroidTargetWriter.using(HostFiles)
        val fileSource = FileSource.Path(swapped.path)
        AndroidImageCodec.using(HostFiles).open(fileSource, CallContext(null)).use { opened ->
            writer.check(OutputTarget.OverwriteSource, fileSource, CallContext(null))
            val replacement = write("replacement.jpg", "OTHER".encodeToByteArray())
            Files.move(replacement.toPath(), swapped.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(Size(320, 240), opened.size)
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                writer.write(OutputTarget.OverwriteSource, fileSource, byteArrayOf(1), CallContext(null))
            }
        }
        assertContentEquals("OTHER".encodeToByteArray(), swapped.readBytes())
        noTemporaryFiles()
    }

    @org.junit.Test(timeout = 20_000)
    fun fifosAndDirectoriesAreRefusedWithoutBlocking() {
        val fifo = File(dir, "pipe.jpg")
        val made = ProcessBuilder("mkfifo", fifo.path).start()
        assertTrue(made.waitFor(10, TimeUnit.SECONDS) && made.exitValue() == 0, "mkfifo")
        val out = File(dir, "out.jpg")
        for (p in listOf(fifo.path, dir.path)) {
            val e =
                assertFailsWith<FaceMaskException.UnsupportedImage>(p) {
                    masker.maskFile(p, OutputTarget.NewFile(out.path))
                }
            assertNoPath(e)
            assertFailsWith<FaceMaskException.UnsupportedImage> { masker.detectFile(p) }
        }
        assertFalse(out.exists())
        val missing = assertFailsWith<FaceMaskException.IoFailed> { masker.detectFile(File(dir, "none.jpg").path) }
        assertNoPath(missing)
    }

    @Test
    fun theSameFileDecodesToTheSamePixelsAlsoInParallel() {
        val codec = AndroidImageCodec.using(HostFiles)
        val files = listOf(
            write("a.jpg", TestImages.jpeg(800, 600)),
            write("b.png", TestImages.png(TestImages.picture(400, 300))),
        )
        for (f in files) {
            fun decodeOnce(working: Size?): IntArray {
                val source = FileSource.Path(f.path)
                return codec.open(source, CallContext(null)).use { opened ->
                    opened.decode(working ?: opened.size).use { w ->
                        IntArray(w.pixels.width * w.pixels.height).also {
                            w.pixels.readPixels(0, 0, w.pixels.width, w.pixels.height, it)
                        }
                    }
                }
            }
            for (working in listOf(null, Size(200, 150))) {
                val first = decodeOnce(working)
                assertContentEquals(
                    first,
                    decodeOnce(working),
                    "${f.name} at $working: same pixels one after the other",
                )
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val both = (1..2).map { pool.submit(Callable { decodeOnce(working) }) }.map { it.get() }
                    for (p in both) assertContentEquals(first, p, "${f.name} at $working: same pixels in parallel")
                } finally {
                    pool.shutdown()
                }
            }
        }
        assertEquals(0, HostFiles.openCount)
    }

    @Test
    fun aSourceThatChangesBetweenItsHeaderAndTheDecodeFailsClosed() {
        // The in-memory file system's decoder source reads the file's current bytes: the frame header is changed in
        // place after the codec read it (as another app rewriting the file would).
        val fs = FakeFiles()
        val jpeg = TestImages.jpeg(320, 240)
        fs.file("/data/in.jpg", jpeg)
        AndroidImageCodec.using(fs).open(FileSource.Path("/data/in.jpg"), CallContext(null)).use { opened ->
            assertEquals(Size(320, 240), opened.size)
            val sof = (2 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xC0.toByte() }
            jpeg[sof + 6] = 200.toByte() // height 240 -> 200
            val e = assertFailsWith<FaceMaskException.IoFailed> { opened.decode(opened.size) }
            assertTrue(e.message!!.contains("changed"))
        }
        assertEquals(0, fs.openDescriptors)
    }

    @Test
    fun transparencyIsDrawnOntoOpaqueWhiteBeforeDetection() {
        // Fully transparent in the bottom right quarter.
        val picture = TestImages.picture(320, 240) { x, y -> if (x >= 160 && y >= 120) 0 else 255 }
        val input = write("alpha.png", TestImages.png(picture))
        val codec = AndroidImageCodec.using(HostFiles)
        codec.open(FileSource.Path(input.path), CallContext(null)).use { opened ->
            opened.decode(opened.size).use { w ->
                val px = IntArray(1)
                w.pixels.readPixels(300, 230, 1, 1, px)
                assertEquals(-0x1, px[0], "transparent pixels become opaque white")
                w.pixels.readPixels(10, 10, 1, 1, px)
                assertEquals(0xFF, px[0] ushr 24, "opaque")
            }
        }
        val out = File(dir, "out.jpg")
        masker.maskFile(input.path, OutputTarget.NewFile(out.path))
        assertEquals(320 to 240, TestImages.decodedSize(out.readBytes()))
    }

    @Test
    fun closingTheOpenedImageDropsTheSourceAndItsBuffer() {
        val bytes = TestImages.jpeg(64, 48)
        val opened = AndroidImageCodec.using(HostFiles).open(FileSource.Bytes(bytes), CallContext(null))
        assertEquals(Size(64, 48), opened.size)
        opened.close()
        val held = generateSequence<Class<*>>(opened.javaClass) {
            it.superclass
        }.flatMap { it.declaredFields.asSequence() }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { f ->
                f.isAccessible = true
                f.get(opened)?.let { f.name to it }
            }
            .filter { (_, v) ->
                v is ByteArray || v is java.nio.ByteBuffer || v is android.graphics.ImageDecoder.Source
            }
            .toList()
        assertEquals(emptyList(), held.map { it.first }, "no reference to the source after close()")
        assertFailsWith<FaceMaskException.Internal> { opened.decode(Size(64, 48)) }
        // A path source closes its descriptor.
        val f = write("in.jpg", bytes)
        AndroidImageCodec.using(HostFiles).open(FileSource.Path(f.path), CallContext(null)).close()
        assertEquals(0, HostFiles.openCount)
    }

    @Test
    fun metadataPoliciesOtherThanStripAllAndPlatformTargetsOfThePathFormWriteNothing() {
        val input = write("in.jpg", TestImages.jpeg(64, 48))
        val out = File(dir, "out.jpg")
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> {
            masker.maskFile(
                input.path,
                OutputTarget.NewFile(out.path),
                FileOptions(metadata = MetadataPolicy.KeepAllowlist(gps = true)),
            )
        }
        assertEquals("metadata", e.parameter)
        val t = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(input.path, AndroidOutputTargets.newFile(Uri.fromFile(out)))
        }
        assertTrue(t.message!!.contains("Context"))
        assertFalse(out.exists())
        noTemporaryFiles()
    }
}
