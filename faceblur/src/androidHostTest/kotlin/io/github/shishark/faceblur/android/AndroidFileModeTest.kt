// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.JpegOutput
import io.github.shishark.faceblur.filemode.JpegParser
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskJpeg
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android stream form of file mode (`maskJpeg`, ST-003, ST-012) on the common orchestration, on the host with
 * Robolectric native graphics (real Skia codecs, as in the
 * benchmark app), with a fake model session that reports one face: decode at the planned size with the EXIF
 * orientation baked in, mask, optional scale-down, gain map dropped, rebuilt and validated JPEG, written once; every
 * failure writes nothing. The pixel checks compare the output inside every mask with a reference masked in the test
 * and with the unmasked source ([PixelChecks]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidFileModeTest {
    private companion object {
        /** Largest mean channel difference to the reference inside a mask (two JPEG generations of a smooth fill). */
        const val TOLERANCE = 8.0

        /** Smallest mean channel difference a mask must make against the unmasked source (4 × [TOLERANCE]). */
        const val SEPARATION = 32.0

        /** Largest mean channel difference to the source away from the masks (two JPEG generations of the pattern). */
        const val OUTSIDE_TOLERANCE = 8.0

        /** The first bytes of a JPEG (SOI and the start of a marker). */
        val START = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }

    private lateinit var masker: FaceMasker

    @Before
    fun createMasker() {
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
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

    /**
     * A synthetic picture of 8 px blocks: a colour gradient (red across, green down) with a brightness checker on top
     * (±64 on every channel, so JPEG keeps it), so every mask clearly changes it and a wrongly turned picture shows in
     * its colours.
     */
    private fun picture(width: Int, height: Int): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height step 8) {
            for (x in 0 until width step 8) {
                val d = if ((x / 8 + y / 8) % 2 == 0) 64 else -64
                val c = Color.rgb(64 + (x * 127) / width + d, 64 + (y * 127) / height + d, 128 + d)
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
        assertTrue(picture(width, height).compress(Bitmap.CompressFormat.JPEG, 92, out))
        return out.toByteArray()
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(picture(width, height).compress(Bitmap.CompressFormat.PNG, 100, out))
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
     * the output equals [unmasked] (so the pixels are turned and scaled as planned).
     */
    private fun assertMasked(
        output: ByteArray,
        reference: Bitmap,
        unmasked: Bitmap,
        masks: List<Mask>,
        scale: Double = 1.0,
    ) {
        assertTrue(masks.isNotEmpty(), "the fake model reports a face")
        val decoded = PixelChecks.decode(output)
        assertEquals(reference.width to reference.height, decoded.width to decoded.height)
        for (c in PixelChecks.compare(decoded, reference, unmasked, masks, scale)) {
            println("pixel check: $c")
            assertTrue(c.referenceToUnmasked >= SEPARATION, "control: the mask changes the source ($c)")
            assertTrue(c.toReference <= TOLERANCE, "the output holds the masked pixels ($c)")
            assertTrue(c.toUnmasked >= SEPARATION, "the output differs from the unmasked source ($c)")
        }
        val far = PixelChecks.freeCorner(decoded.width, decoded.height, masks, scale)
        assertNotNull(far, "a corner away from the masks")
        val outside = PixelChecks.meanDiff(decoded, unmasked, far)
        println("pixel check: free corner output-unmasked %.1f".format(outside))
        assertTrue(outside <= OUTSIDE_TOLERANCE, "away from the masks the output is the source (%.1f)".format(outside))
    }

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
        assertEquals(640 to 480, result.outputWidth to result.outputHeight)
        assertTrue(result.faces.isNotEmpty(), "the fake model reports a face")
        assertTrue(result.faces.all { it.score > 0f })
        assertTrue(result.masks.isNotEmpty())
        val t = result.timings
        assertTrue(t.decodeMs!! >= 0.0 && t.encodeMs!! >= 0.0 && t.totalMs >= t.encodeMs!!)
        val unmasked = PixelChecks.decode(source)
        val reference = PixelChecks.reference(unmasked) {
            assertEquals(result.masks, masker.maskInPlace(it).masks)
        }
        assertMasked(bytes, reference, unmasked, result.masks)
    }

    @Test
    fun theExifOrientationIsBakedIntoThePixelsAndWrittenAsOne() {
        val plain = jpeg(640, 480)
        val (out, result) = run(withOrientation(plain, 6))
        val bytes = out.bytes.toByteArray()
        assertEquals(480, result.width, "orientation 6 turns the picture")
        assertEquals(640, result.height)
        assertEquals(480 to 640, result.sourceWidth to result.sourceHeight)
        assertEquals(480 to 640, decodedSize(bytes))
        assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll))
        // The reference is turned in the test (90 degrees clockwise), not by the decoder under test.
        val unmasked = PixelChecks.rotate90(PixelChecks.decode(plain))
        val reference = PixelChecks.reference(unmasked) {
            assertEquals(result.masks, masker.maskInPlace(it).masks)
        }
        assertMasked(bytes, reference, unmasked, result.masks)
    }

    @Test
    fun aSmallerOutputIsScaledAfterMaskingInWorkingCoordinates() {
        val source = jpeg(640, 480)
        val (out, result) = run(source, FileOptions(maxOutputLongSide = 320))
        assertEquals(320 to 240, decodedSize(out.bytes.toByteArray()))
        assertEquals(640, result.width, "masks are in working-image pixels")
        assertEquals(320, result.outputWidth)
        val working = PixelChecks.decode(source)
        val masked = PixelChecks.reference(working) { assertEquals(result.masks, masker.maskInPlace(it).masks) }
        val reference = Bitmap.createScaledBitmap(masked, 320, 240, true)
        val unmasked = Bitmap.createScaledBitmap(working, 320, 240, true)
        assertMasked(out.bytes.toByteArray(), reference, unmasked, result.masks, scale = 0.5)
    }

    /**
     * A 16-bit PNG decodes to `RGBA_F16` in extended sRGB even with the sRGB target (control below), so the file mode
     * redraws it into 8-bit sRGB before masking: the masked redraw is what reaches the output.
     */
    @Test
    fun a16BitSourceIsRedrawnInto8BitSrgbAndTheRedrawIsMasked() {
        val unmasked = picture(320, 240)
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
        assertEquals(320 to 240, decodedSize(bytes))
        assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll))
        val reference = PixelChecks.reference(unmasked) {
            assertEquals(result.masks, masker.maskInPlace(it).masks)
        }
        assertMasked(bytes, reference, unmasked, result.masks)
    }

    /**
     * The planned output size reaches the frame check (review follow-up): an encoder that returns a JPEG of another
     * size than planned (here one pixel wider, as if it had encoded another bitmap) fails closed and nothing is
     * written to the stream.
     */
    @Test
    fun anEncoderOutputOfAnotherSizeThanPlannedIsNeverWritten() {
        fun wrong(w: WorkingImage): WorkingImage = object : WorkingImage by w {
            override fun scaledTo(output: Size): WorkingImage = wrong(w.scaledTo(output))

            override fun encodeJpeg(quality: Int): ByteArray =
                Bitmaps.encodeJpeg(Bitmaps.blank(w.pixels.width + 1, w.pixels.height), quality)
        }
        val real = AndroidImageCodec.DEFAULT
        val wrongSize = ImageCodec { source, call ->
            val opened = real.open(source, call)
            object : OpenedImage by opened {
                override fun decode(working: Size): WorkingImage = wrong(opened.decode(working))
            }
        }
        FaceBlurRuntime.installImageCodec(wrongSize)
        try {
            for (options in listOf(FileOptions(), FileOptions(maxOutputLongSide = 320))) {
                val out = CountingOutput()
                val e = assertFailsWith<FaceMaskException.Internal> {
                    masker.maskJpeg(ByteArrayInputStream(jpeg(640, 480)), out, options)
                }
                assertTrue(e.message!!.contains("size"), e.message)
                assertEquals(0, out.writes, "nothing is written")
            }
        } finally {
            FaceBlurRuntime.installImageCodec(null)
        }
    }

    /** The platform JPEG encoder refuses an `ALPHA_8` bitmap (`compress` returns false): an Internal error. */
    @Test
    fun anEncoderThatFailsIsInternal() {
        val e =
            assertFailsWith<FaceMaskException.Internal> {
                Bitmaps.encodeJpeg(Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8), 90)
            }
        assertTrue(e.message!!.contains("encoder"))
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

    /** Review 044 L-5: a `RuntimeException` from either stream is `IoFailed` with fixed text and nothing attached. */
    @Test
    fun runtimeExceptionsOfTheStreamsAreIoFailedWithoutTheirText() {
        val throwingInput =
            object : InputStream() {
                override fun read(): Int = throw IllegalStateException("/secret/path")

                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IllegalStateException("/secret/path")
            }
        val e1 = assertFailsWith<FaceMaskException.IoFailed> { masker.maskJpeg(throwingInput, CountingOutput()) }
        assertFalse(e1.message!!.contains("secret"))
        assertEquals(null, e1.cause)
        val throwingOutput =
            object : OutputStream() {
                override fun write(b: Int) = throw UnsupportedOperationException("/secret/path")

                override fun write(b: ByteArray, off: Int, len: Int) =
                    throw UnsupportedOperationException("/secret/path")
            }
        val e2 = assertFailsWith<FaceMaskException.IoFailed> {
            masker.maskJpeg(ByteArrayInputStream(jpeg(64, 48)), throwingOutput)
        }
        assertFalse(e2.message!!.contains("secret"))
        assertEquals(null, e2.cause)
    }

    /**
     * Review 044 L-4: through the Android adapter, an endless stream that starts like a JPEG is refused with
     * `SourceTooLarge` after at most 64 MiB + 1 bytes are read; nothing is decoded or written.
     */
    @Test
    fun anEndlessStreamIsSourceTooLargeAfterAtMostTheLimitPlusOneByte() {
        val limit = 64L * 1024 * 1024
        var read = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    read++
                    return 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    for (i in off until off + len) b[i] = if (read + i - off < 3) START[(read + i - off).toInt()] else 0
                    read += len
                    return len
                }
            }
        val out = CountingOutput()
        val e = assertFailsWith<FaceMaskException.SourceTooLarge> { masker.maskJpeg(endless, out) }
        assertEquals(limit, e.limitBytes)
        assertTrue(read in (limit + 1)..(limit + 1 + 64 * 1024), "read $read bytes")
        assertEquals(0, out.writes)
    }

    /**
     * The gain-map rule at the encoder: `setGainmap(null)` runs before `compress`. The host encoder (Skia on the JVM)
     * never writes an MPF gain-map image even when a gain map is attached, so the absence of the MPF image in a real
     * Android 14+ encoder's output is checked on the Android 37 emulator, not here.
     */
    @Test
    fun theGainMapIsDroppedBeforeEncoding() {
        val bitmap = picture(64, 48)
        bitmap.gainmap = Gainmap(Bitmap.createBitmap(16, 12, Bitmap.Config.ALPHA_8))
        assertTrue(bitmap.hasGainmap(), "control: the picture carries a gain map")
        val encoded = Bitmaps.encodeJpeg(bitmap, 90)
        assertFalse(bitmap.hasGainmap(), "setGainmap(null) ran before compress")
        val ids = JpegParser.parse(encoded).segments.filter { it.marker in 0xE0..0xEF }.map { s ->
            encoded.copyOfRange(s.start + 4, minOf(s.end, s.start + 8)).decodeToString()
        }
        assertFalse(ids.any { it.startsWith("MPF") }, "no MPF segment: $ids")
        // The rebuilt file keeps none of the encoder's APPn segments (the encoder's ICC profile included).
        val finished = JpegOutput.finish(encoded, null, MetadataPolicy.StripAll, Size(64, 48))
        assertEquals(emptyList(), OutputValidator.findings(finished.bytes, MetadataPolicy.StripAll))
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
