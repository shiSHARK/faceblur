// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.detectFile
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ST-012 / ST-013 on the phone: the path and `Uri` forms with the production codec and writer on `android.system.Os`
 * (link, rename, lstat, non-blocking open) and a private provider of the test app's own files ([DeviceTestFilesProvider];
 * the phone's shared storage is never touched), with NO install call (the platform defaults). Pictures are made from
 * the registered synthetic face. Every written file is decoded again and checked inside its masks.
 */
@RunWith(AndroidJUnit4::class)
class FileTargetsDeviceTest {
    private companion object {
        const val TOLERANCE = 8.0
        const val SEPARATION = 32.0
    }

    private lateinit var masker: FaceMasker
    private lateinit var dirs: List<File>

    @Before
    fun setUp() {
        DeviceFixtures.logHeader()
        val model = DetectorModel.load(
            ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
            DeviceFixtures.yunetProfile(),
        )
        masker = FaceMasker(model)
        val external = DeviceFixtures.context.getExternalFilesDir(null)
        dirs = listOfNotNull(
            DeviceFixtures.scratch("targets"),
            external?.let {
                File(it, "faceblur-scratch/targets").apply {
                    deleteRecursively()
                    mkdirs()
                }
            },
        )
    }

    @After
    fun tearDown() {
        masker.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun faceJpeg(dir: File, name: String): File {
        val full = BitmapFactory.decodeFile(DeviceFixtures.file(DeviceFixtures.IMAGE_FILE).path)
        val crop = Bitmap.createBitmap(full, 0, 0, 640, 480)
        val f = File(dir, name)
        f.outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        return f
    }

    /** The written file is valid and holds, inside every mask, the source masked with the same plan. */
    private fun assertMaskedFile(output: ByteArray, source: ByteArray, result: FileResult) {
        assertEquals(emptyList(), OutputValidator.findings(output, MetadataPolicy.StripAll), "PRIV-007 marker walk")
        assertTrue(result.masks.isNotEmpty(), "the registered face is masked")
        val unmasked = PixelChecks.decode(source)
        val reference = PixelChecks.reference(unmasked) {
            masker.applyMasks(it, MaskPlan(it.width, it.height, result.masks))
        }
        val decoded = PixelChecks.decode(output)
        for (c in PixelChecks.compare(decoded, reference, unmasked, result.masks, 1.0)) {
            Log.i(DeviceFixtures.TAG, "file targets pixel check: $c")
            assertTrue(c.referenceToUnmasked >= SEPARATION, "control: the mask changes the source ($c)")
            assertTrue(c.toReference <= TOLERANCE, "the output holds the masked pixels ($c)")
            assertTrue(c.toUnmasked >= SEPARATION, "the output differs from the unmasked source ($c)")
        }
    }

    private fun noTemporaryFiles(dir: File) =
        assertEquals(emptyList(), dir.list()!!.filter { it.contains(".faceblur-") }, "no temporary file is left")

    /** Whether `link(2)` works in [dir] (logged; the writer falls back to an exclusive create where it does not). */
    private fun linkSupport(dir: File): String {
        val a = File(dir, "probe-a").apply { writeText("x") }
        val b = File(dir, "probe-b")
        return try {
            Os.link(a.path, b.path)
            "supported"
        } catch (e: ErrnoException) {
            "errno ${e.errno}"
        } finally {
            a.delete()
            b.delete()
        }
    }

    @Test
    fun theBindingsAreThePlatformDefaultsWithoutAnInstallCall() {
        assertSame(AndroidModelRuntime, FaceBlurRuntime.runtime())
        assertSame(AndroidModelFileReader, FaceBlurRuntime.modelFiles())
        assertSame(AndroidHeapProbe, FaceBlurRuntime.memoryProbe())
        assertSame(AndroidImageCodec.DEFAULT, FaceBlurRuntime.imageCodec())
        assertSame(AndroidTargetWriter.DEFAULT, FaceBlurRuntime.targetWriter())
    }

    @Test
    fun pathTargetsAreWrittenNewReplacedAndTheSourceOverwrittenOnThePhone() {
        for (dir in dirs) {
            Log.i(
                DeviceFixtures.TAG,
                "file targets: ${if (dir.path.contains("/files/")) "app files" else "dir"}; link ${linkSupport(dir)}",
            )
            val input = faceJpeg(dir, "in.jpg")
            val source = input.readBytes()
            val out = File(dir, "out.jpg")
            val r1 = masker.maskFile(input.path, OutputTarget.NewFile(out.path))
            assertMaskedFile(out.readBytes(), source, r1)
            val mode = Os.stat(out.path).st_mode and 0x1FF
            Log.i(DeviceFixtures.TAG, "file targets: output mode %o".format(mode))
            val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
                masker.maskFile(input.path, OutputTarget.NewFile(out.path))
            }
            assertFalse(e.message!!.contains(dir.path))
            val r2 = masker.maskFile(input.path, OutputTarget.NewFile(out.path, replaceExisting = true))
            assertMaskedFile(out.readBytes(), source, r2)
            val r3 = masker.maskFile(input.path, OutputTarget.OverwriteSource)
            assertMaskedFile(input.readBytes(), source, r3)
            noTemporaryFiles(dir)
        }
    }

    @Test
    fun linkedSourcesAndTargetsAreNeverReplaced() {
        val dir = dirs.first()
        val original = faceJpeg(dir, "original.jpg")
        val source = original.readBytes()
        val link = File(dir, "link.jpg")
        Os.symlink(original.path, link.path)
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(link.path, OutputTarget.OverwriteSource)
        }
        assertContentEquals(source, original.readBytes(), "the original behind the link is untouched")
        // A second hard link: Android refuses link(2) for apps (EACCES), so such a file cannot be made here; the
        // refusal of multiply-linked files is host-tested on a real file system (HostFilesTest).
        val second = File(dir, "second-name.jpg")
        val linked = try {
            Os.link(original.path, second.path)
            true
        } catch (e: ErrnoException) {
            Log.i(DeviceFixtures.TAG, "file targets: hard links refused by the platform (errno ${e.errno})")
            false
        }
        if (linked) {
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                masker.maskFile(original.path, OutputTarget.OverwriteSource)
            }
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                masker.maskFile(link.path, OutputTarget.NewFile(second.path, replaceExisting = true))
            }
            assertContentEquals(source, second.readBytes())
        }
        // Reading through a link is allowed (the result goes to a new file).
        val out = File(dir, "via-link.jpg")
        val r = masker.maskFile(link.path, OutputTarget.NewFile(out.path))
        assertMaskedFile(out.readBytes(), source, r)
        noTemporaryFiles(dir)
    }

    @Test(timeout = 30_000)
    fun fifoSourcesAreRefusedWithoutBlocking() {
        val dir = dirs.first()
        val fifo = File(dir, "pipe.jpg")
        Os.mkfifo(fifo.path, 0x180)
        val e = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.detectFile(fifo.path) }
        assertFalse(e.message!!.contains(dir.path))
        assertFailsWith<FaceMaskException.UnsupportedImage> {
            masker.maskFile(fifo.path, OutputTarget.NewFile(File(dir, "out.jpg").path))
        }
        assertFailsWith<FaceMaskException.UnsupportedImage> { masker.detectFile(dir.path) }
        assertFalse(File(dir, "out.jpg").exists())
    }

    @Test
    fun theSameFileDecodesToTheSamePixelsAlsoInTwoParallelDecodes() {
        val dir = dirs.first()
        val jpeg = faceJpeg(dir, "a.jpg")
        val png = File(dir, "b.png").apply {
            outputStream().use { BitmapFactory.decodeFile(jpeg.path).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val codec = AndroidImageCodec.DEFAULT
        for (f in listOf(jpeg, png)) {
            for (half in listOf(false, true)) {
                fun decodeOnce(): IntArray = codec.open(FileSource.Path(f.path), CallContext(null)).use { opened ->
                    val size = if (half) {
                        io.github.shishark.faceblur.filemode.Size(opened.size.width / 2, opened.size.height / 2)
                    } else {
                        opened.size
                    }
                    opened.decode(size).use { w ->
                        IntArray(w.pixels.width * w.pixels.height).also {
                            w.pixels.readPixels(0, 0, w.pixels.width, w.pixels.height, it)
                        }
                    }
                }
                val first = decodeOnce()
                assertContentEquals(first, decodeOnce(), "${f.name} half=$half: one after the other")
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val both = (1..2).map { pool.submit(Callable { decodeOnce() }) }.map { it.get() }
                    for (p in both) assertContentEquals(first, p, "${f.name} half=$half: two parallel decodes")
                } finally {
                    pool.shutdown()
                }
                Log.i(DeviceFixtures.TAG, "decoder determinism: ${f.extension} half=$half identical (${first.size} px)")
            }
        }
    }

    @Test
    fun contentTargetsAreWrittenTruncatedVerifiedAndOnlyReplacedWithTheOptIn() {
        val context = DeviceFixtures.context
        val resolver = context.contentResolver
        val providerDir = DeviceFixtures.scratch("provider")
        val target = File(providerDir, "target.jpg").apply { writeBytes(ByteArray(0)) }
        val uri = DeviceTestFilesProvider.uri("target.jpg")
        val input = faceJpeg(dirs.first(), "in.jpg")
        val source = input.readBytes()
        synchronized(DeviceTestFilesProvider.modes) { DeviceTestFilesProvider.modes.clear() }
        val r1 = masker.maskFile(context, Uri.fromFile(input), AndroidOutputTargets.newFile(uri))
        assertEquals(listOf("r", "r", "wt", "r"), DeviceTestFilesProvider.modes, "probes, write, read-back; never w")
        assertEquals(r1.outputBytes, target.length())
        assertMaskedFile(target.readBytes(), source, r1)
        // Not empty any more: newFile refuses it, untouched.
        val before = target.readBytes()
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(context, Uri.fromFile(input), AndroidOutputTargets.newFile(uri))
        }
        assertContentEquals(before, target.readBytes())
        // A longer old content is cut to the output's length by the opt-in replacement.
        target.writeBytes(ByteArray(3_000_000) { 7 })
        val r2 = masker.maskFile(context, Uri.fromFile(input), AndroidOutputTargets.overwriteNonAtomic(uri))
        assertEquals(r2.outputBytes, target.length(), "no byte of the old content after the output")
        assertMaskedFile(target.readBytes(), source, r2)
        // The content URI as a source: decoded through the provider; OverwriteSource is refused before it is opened.
        val d = masker.detectFile(context, uri)
        assertEquals(640 to 480, d.width to d.height)
        synchronized(DeviceTestFilesProvider.modes) { DeviceTestFilesProvider.modes.clear() }
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile(context, uri, OutputTarget.OverwriteSource)
        }
        assertEquals(emptyList(), DeviceTestFilesProvider.modes, "the source is never opened")
        val r3 = masker.maskFile(context, uri, AndroidOutputTargets.overwriteNonAtomic(uri))
        assertEquals(r3.outputBytes, target.length())
        assertTrue(resolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(target.readBytes()))
        providerDir.deleteRecursively()
    }
}
