// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.filemode.JpegOutput
import io.github.shishark.faceblur.filemode.JpegParser
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.maskJpeg
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android stream form of file mode (`maskJpeg`, ST-003, ST-012) on the common orchestration, with the platform's
 * own codecs and the reference model fixture on the
 * device, on pictures made from the registered synthetic face: decode at the planned size with the EXIF orientation baked in, mask, optional scale-down, gain map
 * dropped, rebuilt and validated JPEG, written once; every failure writes nothing. The pixel checks compare the output
 * inside every mask with a reference masked in the test by the same masker and with the unmasked source
 * ([PixelChecks]).
 */
@RunWith(AndroidJUnit4::class)
class AndroidFileModeTest {
    private companion object {
        /** Largest mean channel difference to the reference inside a mask (the output's JPEG of a smooth fill). */
        const val TOLERANCE = 8.0

        /** Smallest mean channel difference a mask must make against the unmasked face (4 × [TOLERANCE]). */
        const val SEPARATION = 32.0

        /** Largest mean channel difference to the source away from the masks (JPEG generations of the photo). */
        const val OUTSIDE_TOLERANCE = 8.0
    }

    private lateinit var masker: FaceMasker

    @Before
    fun createMasker() {
        DeviceFixtures.logHeader()
        // No install call: the Android bindings are the platform defaults.
        val model =
            DetectorModel.load(
                ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
                DeviceFixtures.yunetProfile(),
            )
        masker = FaceMasker(model, FaceMaskerConfig())
    }

    /** The registered face, cropped to 640 × 480 and scaled to [width] × [height]. */
    private fun face(width: Int, height: Int): Bitmap {
        val full = BitmapFactory.decodeFile(DeviceFixtures.file(DeviceFixtures.IMAGE_FILE).path)
        val crop = Bitmap.createBitmap(full, 0, 0, 640, 480)
        return if (width == 640 && height == 480) crop else Bitmap.createScaledBitmap(crop, width, height, true)
    }

    @After
    fun closeMasker() = masker.close()

    /** Counts write calls; never closed by the code under test. */
    private class CountingOutput : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writes = 0

        override fun write(b: Int) {
            writes++
            bytes.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writes++
            bytes.write(b, off, len)
        }
    }

    private fun picture(width: Int, height: Int): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height step 8) {
            for (x in 0 until width step 8) {
                val c = Color.rgb((x * 255) / width, (y * 255) / height, 128)
                for (dy in 0 until minOf(8, height - y)) {
                    for (dx in 0 until minOf(8, width - x)) {
                        b.setPixel(
                            x + dx,
                            y + dy,
                            c,
                        )
                    }
                }
            }
        }
        return b
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(face(width, height).compress(Bitmap.CompressFormat.JPEG, 92, out))
        return out.toByteArray()
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(face(width, height).compress(Bitmap.CompressFormat.PNG, 100, out))
        return out.toByteArray()
    }

    /** [jpeg] with an EXIF APP1 (big-endian TIFF, one IFD0 entry: Orientation = [orientation]) right after SOI. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // "MM", 42, IFD0 at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun run(source: ByteArray, options: FileOptions = FileOptions()) = CountingOutput().let { out ->
        out to masker.maskJpeg(ByteArrayInputStream(source), out, options)
    }

    private val cancelled = object : CancellationToken {
        override val isCancellationRequested: Boolean = true
    }

    /**
     * Inside every mask of [masks] (working-image pixels; the output is [scale] × the working size) the output
     * equals [reference] within the JPEG tolerance and differs from [unmasked] by clearly more; away from the masks
     * the output equals [unmasked] (so the pixels are turned and scaled as planned). The measured differences are
     * logged (synthetic image).
     */
    private fun assertMasked(
        output: ByteArray,
        reference: Bitmap,
        unmasked: Bitmap,
        masks: List<Mask>,
        scale: Double = 1.0,
    ) {
        assertTrue(masks.isNotEmpty(), "the registered face is masked")
        val decoded = PixelChecks.decode(output)
        assertEquals(reference.width to reference.height, decoded.width to decoded.height)
        for (c in PixelChecks.compare(decoded, reference, unmasked, masks, scale)) {
            Log.i(DeviceFixtures.TAG, "pixel check: $c")
            assertTrue(c.referenceToUnmasked >= SEPARATION, "control: the mask changes the source ($c)")
            assertTrue(c.toReference <= TOLERANCE, "the output holds the masked pixels ($c)")
            assertTrue(c.toUnmasked >= SEPARATION, "the output differs from the unmasked source ($c)")
        }
        val far = PixelChecks.freeCorner(decoded.width, decoded.height, masks, scale)
        assertNotNull(far, "a corner away from the masks")
        val outside = PixelChecks.meanDiff(decoded, unmasked, far)
        Log.i(DeviceFixtures.TAG, "pixel check: free corner output-unmasked %.1f".format(outside))
        assertTrue(outside <= OUTSIDE_TOLERANCE, "away from the masks the output is the source (%.1f)".format(outside))
    }

    /** [unmasked] masked in the test by the same masker. */
    private fun reference(unmasked: Bitmap): Bitmap = PixelChecks.reference(unmasked) { masker.maskInPlace(it) }

    private fun decodedSize(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun aJpegComesOutMaskedRebuiltAndValidatedInOneWrite() {
        val source = jpeg(640, 480)
        val (out, result) = run(source)
        val bytes = out.bytes.toByteArray()
        assertEquals(1, out.writes, "the finished file is written once")
        assertEquals(bytes.size.toLong(), result.outputBytes)
        assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll), "PRIV-007 marker walk")
        assertEquals(640 to 480, decodedSize(bytes))
        assertEquals(640, result.width)
        assertEquals(480, result.height)
        assertTrue(result.faces.isNotEmpty(), "the registered face is found")
        assertTrue(result.faces.all { it.score > 0f })
        assertTrue(result.masks.isNotEmpty())
        val t = result.timings
        assertTrue(t.decodeMs!! >= 0.0 && t.encodeMs!! >= 0.0 && t.totalMs >= t.encodeMs!!)
        val unmasked = PixelChecks.decode(source)
        assertMasked(bytes, reference(unmasked), unmasked, result.masks)
    }

    @Test
    fun theExifOrientationIsBakedIntoThePixelsAndWrittenAsOne() {
        val plain = jpeg(640, 480)
        val (out, result) = run(withOrientation(plain, 6))
        val bytes = out.bytes.toByteArray()
        assertEquals(480, result.width, "orientation 6 turns the picture")
        assertEquals(640, result.height)
        assertEquals(480 to 640, decodedSize(bytes))
        assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll))
        // The reference is turned in the test (90 degrees clockwise), not by the decoder under test.
        val unmasked = PixelChecks.rotate90(PixelChecks.decode(plain))
        assertMasked(bytes, reference(unmasked), unmasked, result.masks)
    }

    @Test
    fun aSmallerOutputIsScaledAfterMaskingInWorkingCoordinates() {
        val source = jpeg(640, 480)
        val (out, result) = run(source, FileOptions(maxOutputLongSide = 320))
        assertEquals(320 to 240, decodedSize(out.bytes.toByteArray()))
        assertEquals(640, result.width, "masks are in working-image pixels")
        assertEquals(320, result.outputWidth)
        val working = PixelChecks.decode(source)
        val reference = Bitmap.createScaledBitmap(reference(working), 320, 240, true)
        val unmasked = Bitmap.createScaledBitmap(working, 320, 240, true)
        assertMasked(out.bytes.toByteArray(), reference, unmasked, result.masks, scale = 0.5)
    }

    /**
     * A 16-bit PNG decodes to `RGBA_F16` in extended sRGB even with the sRGB target (control below), so the file mode
     * redraws it into 8-bit sRGB before masking: the masked redraw is what reaches the output.
     */
    @Test
    fun a16BitSourceIsRedrawnInto8BitSrgbAndTheRedrawIsMasked() {
        val unmasked = face(640, 480)
        val source = PixelChecks.png16(unmasked)
        val control =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
                d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            }
        assertEquals(Bitmap.Config.RGBA_F16, control.config, "control: the source needs the redraw")
        control.recycle()
        val (out, result) = run(source)
        val bytes = out.bytes.toByteArray()
        assertEquals(1, out.writes)
        assertEquals(640 to 480, decodedSize(bytes))
        assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll))
        assertMasked(bytes, reference(unmasked), unmasked, result.masks)
    }

    @Test
    fun aPngSourceIsAccepted() {
        val (out, result) = run(png(320, 200))
        assertEquals(320 to 200, decodedSize(out.bytes.toByteArray()))
        assertEquals(emptyList(), OutputValidator.findings(out.bytes.toByteArray(), MetadataPolicy.StripAll))
        assertEquals(320, result.outputWidth)
    }

    @Test
    fun anythingElseIsRefusedAndNothingIsWritten() {
        val out = CountingOutput()
        assertFailsWith<FaceMaskException.UnsupportedImage> {
            masker.maskJpeg(ByteArrayInputStream("GIF89a not supported".encodeToByteArray()), out)
        }
        val truncated = jpeg(320, 240).let { it.copyOfRange(0, it.size / 3) }
        assertFailsWith<FaceMaskException.UnsupportedImage> {
            masker.maskJpeg(ByteArrayInputStream(truncated), out)
        }
        assertFailsWith<FaceMaskException.UnsupportedImage> {
            masker.maskJpeg(ByteArrayInputStream(ByteArray(0)), out)
        }
        assertEquals(0, out.writes)
    }

    @Test
    fun aHeaderAboveTheDecodedPixelLimitIsRefusedBeforeDecoding() {
        // A real JPEG whose frame header claims 5,000 × 4,000 px: the plan refuses it from the header alone.
        val bytes = jpeg(64, 48)
        val sof = (2 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
        bytes[sof + 5] = (4000 ushr 8).toByte()
        bytes[sof + 6] = 4000.toByte()
        bytes[sof + 7] = (5000 ushr 8).toByte()
        bytes[sof + 8] = 5000.toByte()
        val out = CountingOutput()
        val options = FileOptions(maxLongSide = 0, maxDecodedPixels = 13_000_000)
        assertFailsWith<FaceMaskException.ImageTooLarge> {
            masker.maskJpeg(ByteArrayInputStream(bytes), out, options)
        }
        assertEquals(0, out.writes)
    }

    @Test
    fun onlyStripAllIsAcceptedForNow() {
        val out = CountingOutput()
        val e =
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                masker.maskJpeg(
                    ByteArrayInputStream(jpeg(64, 48)),
                    out,
                    FileOptions(metadata = MetadataPolicy.KeepAllowlist(gps = true)),
                )
            }
        assertTrue(e.message!!.contains("metadata"))
        assertEquals(0, out.writes)
    }

    @Test
    fun aCancelledCallWritesNothing() {
        val out = CountingOutput()
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.maskJpeg(ByteArrayInputStream(jpeg(64, 48)), out, FileOptions(), cancelled)
        }
        assertEquals(0, out.writes)
    }

    @Test
    fun streamFailuresAreIoFailedWithoutTheirText() {
        val failingInput =
            object : InputStream() {
                override fun read(): Int = throw IOException("/secret/path")
            }
        val e1 =
            assertFailsWith<FaceMaskException.IoFailed> {
                masker.maskJpeg(failingInput, CountingOutput())
            }
        assertFalse(e1.message!!.contains("secret"))
        val failingOutput =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("/secret/path")

                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("/secret/path")
            }
        val e2 =
            assertFailsWith<FaceMaskException.IoFailed> {
                masker.maskJpeg(ByteArrayInputStream(jpeg(64, 48)), failingOutput)
            }
        assertFalse(e2.message!!.contains("secret"))
    }

    /**
     * The gain-map rule at the encoder (Android 14+, API 34): `setGainmap(null)` runs before `compress`, and the
     * output has no MPF segment; then file mode end to end on a source with a gain map and an MPF secondary image:
     * the written file has no MPF segment and no secondary image. Gain maps do not exist below API 34, so this runs on an Android 14+ device or
     * emulator only (the Android 37 emulator run); on older devices JUnit reports it as not run.
     */
    @Test
    fun theGainMapIsDroppedBeforeEncoding() {
        assumeTrue("gain maps exist from API 34", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        fun appIds(jpeg: ByteArray) = JpegParser.parse(jpeg).segments.filter { it.marker in 0xE0..0xEF }.map { s ->
            jpeg.copyOfRange(s.start + 4, minOf(s.end, s.start + 8)).decodeToString()
        }
        // Control: the platform encoder writes an attached gain map as an MPF secondary image.
        val control = picture(64, 48).also { it.gainmap = Gainmap(Bitmap.createBitmap(16, 12, Bitmap.Config.ALPHA_8)) }
        val raw = ByteArrayOutputStream().also { control.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        assertTrue(appIds(raw).any { it.startsWith("MPF") }, "control: MPF written for a gain map: ${appIds(raw)}")
        val bitmap = picture(64, 48)
        bitmap.gainmap = Gainmap(Bitmap.createBitmap(16, 12, Bitmap.Config.ALPHA_8))
        assertTrue(bitmap.hasGainmap(), "control: the picture carries a gain map")
        val encoded = Bitmaps.encodeJpeg(bitmap, 90)
        assertFalse(bitmap.hasGainmap(), "setGainmap(null) ran before compress")
        val ids = appIds(encoded)
        assertFalse(ids.any { it.startsWith("MPF") }, "no MPF segment: $ids")
        // The rebuilt file keeps none of the encoder's APPn segments (the encoder's ICC profile included).
        val finished = JpegOutput.finish(encoded, null, MetadataPolicy.StripAll, Size(64, 48))
        assertEquals(emptyList(), OutputValidator.findings(finished.bytes, MetadataPolicy.StripAll))

        // File mode end to end on a synthetic source that carries a gain map as an MPF secondary image. The
        // source is the control as well: the same picture compressed WITHOUT setGainmap(null). A source without the
        // gain map, the MPF segment or the secondary image fails the test (the rule would not be exercised).
        fun secondImage(jpeg: ByteArray): Boolean = (2 until jpeg.size - 2).any {
            jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xD8.toByte() && jpeg[it + 2] == 0xFF.toByte()
        }
        val withMap = picture(256, 192).also {
            it.gainmap = Gainmap(Bitmap.createBitmap(64, 48, Bitmap.Config.ALPHA_8))
        }
        val source = ByteArrayOutputStream().also {
            assertTrue(withMap.compress(Bitmap.CompressFormat.JPEG, 90, it))
        }.toByteArray()
        assertTrue(appIds(source).any { it.startsWith("MPF") }, "fixture: an MPF segment: ${appIds(source)}")
        assertTrue(secondImage(source), "fixture: a secondary image after the primary one")
        val decodedSource = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source)))
        assertTrue(decodedSource.hasGainmap(), "fixture: the source decodes with a gain map")
        val dir = DeviceFixtures.scratch("gain-map")
        val input = File(dir, "gain-map-source.jpg").apply { writeBytes(source) }
        val target = File(dir, "gain-map-masked.jpg")
        val result = masker.maskFile(input.path, OutputTarget.NewFile(target.path))
        assertTrue(result.gainMapRemoved, "the result reports the dropped gain map")
        val written = target.readBytes()
        assertFalse(appIds(written).any { it.startsWith("MPF") }, "no MPF segment in the output: ${appIds(written)}")
        assertFalse(secondImage(written), "no secondary image in the output")
        assertEquals(emptyList(), OutputValidator.findings(written, MetadataPolicy.StripAll))
        // Kept in the app's private storage for the host checker (pulled by the emulator run; uninstall removes it).
        File(dir, "gain-map-control.jpg").writeBytes(source)
        Log.i(DeviceFixtures.TAG, "gain map: source ${source.size} B, output ${written.size} B in ${dir.name}")
    }

    @Test
    fun bitmapPixelsExchangeNonPremultipliedArgb() {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val image = BitmapPixels(bitmap)
        assertEquals(4, image.width)
        assertEquals(3, image.height)
        val block = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        image.writePixels(1, 1, 2, 2, block)
        val back = IntArray(4)
        image.readPixels(1, 1, 2, 2, back)
        assertContentEquals(block, back)
        assertFailsWith<IllegalArgumentException> { BitmapPixels(Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)) }
        assertFailsWith<IllegalArgumentException> {
            BitmapPixels(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false))
        }
    }
}
