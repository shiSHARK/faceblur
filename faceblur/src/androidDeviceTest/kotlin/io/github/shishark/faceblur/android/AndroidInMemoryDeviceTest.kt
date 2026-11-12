// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.applyMasks
import io.github.shishark.faceblur.detect
import io.github.shishark.faceblur.maskInPlace
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.CRC32
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Android in-memory mode on the phone (ST-015, FR-IM-001, FR-IM-002, FR-IM-008, FR-IM-009) with the reference
 * model fixture and the registered synthetic face: the same bitmap object holds the masked pixels, opaque inside every
 * mask, exactly the original beyond the soft edges, and equal to the common route on a plain pixel array; HARDWARE and
 * the other refused bitmaps are refused with their reason and stay unchanged; the worst case (a whole 25-megapixel
 * bitmap masked) completes or fails with the bitmap untouched. The gain-map rule needs API 34 and is covered by the
 * host test and the Android 37 emulator run.
 */
@RunWith(AndroidJUnit4::class)
class AndroidInMemoryDeviceTest {
    private lateinit var masker: FaceMasker

    @Before
    fun createMasker() {
        DeviceFixtures.logHeader()
        val model =
            DetectorModel.load(
                ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
                DeviceFixtures.yunetProfile(),
            )
        masker = FaceMasker(model, FaceMaskerConfig())
    }

    @After
    fun closeMasker() = masker.close()

    /** The registered face decoded as the integration guide says: software, mutable, sRGB. */
    private fun face(): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(DeviceFixtures.file(DeviceFixtures.IMAGE_FILE))) {
                d,
                _,
                _,
            ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
    }

    /** A plain pixel array as a [MutablePixelImage] (the oracle's common route). */
    private class ArrayImage(override val width: Int, override val height: Int, val px: IntArray) : MutablePixelImage {
        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            for (r in 0 until h) px.copyInto(dst, r * w, (y + r) * width + x, (y + r) * width + x + w)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            for (r in 0 until h) src.copyInto(px, (y + r) * width + x, r * w, r * w + w)
        }
    }

    @Test
    fun maskInPlaceMasksTheRegisteredFaceInTheSameBitmapOnThePhone() {
        val bitmap = face()
        val before = pixels(bitmap)
        val found = masker.detect(bitmap)
        assertTrue(found.faces.isNotEmpty(), "the reference model finds the registered face")
        assertContentEquals(before, pixels(bitmap), "detect never writes")
        val result = masker.maskInPlace(bitmap)
        assertEquals(found.faces, result.faces, "maskInPlace detects what detect found")
        val after = pixels(bitmap)
        val feather = FaceMaskerConfig().masking.feather
        val masks = result.masks.map { it to it.toGeometry() }
        var inside = 0
        var changed = 0
        for (i in before.indices) {
            val x = i % bitmap.width
            val y = i / bitmap.width
            if (masks.any { (_, g) -> g.containsPixel(x, y) }) {
                inside++
                if (after[i] != before[i]) changed++
                assertEquals(0xFF, after[i] ushr 24, "alpha 255 inside a mask at ($x, $y)")
            } else if (masks.all { (m, g) ->
                    g.normalizedRadius(x + 0.5f, y + 0.5f) > 1f + feather + 2f / minOf(m.radiusX, m.radiusY)
                }
            ) {
                assertEquals(before[i], after[i], "outside every mask the pixel is unchanged at ($x, $y)")
            }
        }
        assertTrue(inside > 1000, "the check covered the inside of the masks ($inside px)")
        // A fill value can equal a source pixel by chance, so "changed" is counted, not required per pixel; the exact
        // values are checked against the oracle below.
        assertTrue(changed >= inside * 0.95, "the masks changed the inside ($changed of $inside px)")
        val oracle = ArrayImage(bitmap.width, bitmap.height, before)
        val expected = masker.maskInPlace(oracle)
        assertEquals(expected.masks, result.masks)
        assertContentEquals(oracle.px, after, "the bitmap holds exactly the common route's pixels")
        Log.i(
            DeviceFixtures.TAG,
            "in-memory: ${result.faces.size} faces, ${result.masks.size} masks, $inside px inside",
        )
    }

    @Test
    fun hardwareImmutableAndOtherBitmapsAreRefusedUnchangedOnThePhone() {
        val source = face()
        val hardware = checkNotNull(source.copy(Bitmap.Config.HARDWARE, false)) { "a HARDWARE copy" }
        assertEquals(Bitmap.Config.HARDWARE, hardware.config)
        for (call in listOf<() -> Unit>(
            { masker.detect(hardware) },
            { masker.maskInPlace(hardware) },
            { masker.applyMasks(hardware, MaskPlan(hardware.width, hardware.height, emptyList())) },
        )) {
            val e = assertFailsWith<FaceMaskException.UnsupportedImage> { call() }
            assertTrue(e.message.orEmpty().startsWith("Unsupported image: HARDWARE: "), e.message)
        }
        val immutable = source.copy(Bitmap.Config.ARGB_8888, false)
        val immutableBefore = pixels(immutable)
        val e = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.maskInPlace(immutable) }
        assertTrue(e.message.orEmpty().startsWith("Unsupported image: IMMUTABLE: "), e.message)
        assertContentEquals(immutableBefore, pixels(immutable))
        val rgb565 = source.copy(Bitmap.Config.RGB_565, true)
        val c = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.maskInPlace(rgb565) }
        assertTrue(c.message.orEmpty().startsWith("Unsupported image: CONFIG: "), c.message)
        val p3 = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
        )
        p3.setPixels(pixels(source), 0, source.width, 0, 0, source.width, source.height)
        val p3Before = pixels(p3)
        val s = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.maskInPlace(p3) }
        assertTrue(s.message.orEmpty().startsWith("Unsupported image: CONFIG: the bitmap's colour space"), s.message)
        assertContentEquals(p3Before, pixels(p3))
        val recycled = source.copy(Bitmap.Config.ARGB_8888, true).apply { recycle() }
        val r = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.detect(recycled) }
        assertTrue(r.message.orEmpty().startsWith("Unsupported image: RECYCLED: "), r.message)
        // The documented copy is accepted.
        val copy = hardware.copy(Bitmap.Config.ARGB_8888, true)
        assertTrue(masker.maskInPlace(copy).faces.isNotEmpty())
    }

    /** CRC-32 of the bitmap's pixels, read row by row (no full-size array). */
    private fun checksum(b: Bitmap): Long {
        val row = IntArray(b.width)
        val bytes = ByteArray(b.width * 4)
        val crc = CRC32()
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in row.indices) {
                val p = row[x]
                bytes[4 * x] = (p ushr 24).toByte()
                bytes[4 * x + 1] = (p ushr 16).toByte()
                bytes[4 * x + 2] = (p ushr 8).toByte()
                bytes[4 * x + 3] = p.toByte()
            }
            crc.update(bytes)
        }
        return crc.value
    }

    @Test
    fun theWorstCaseOfAWhole25MegapixelBitmapCompletesOrLeavesItUntouched() {
        val side = 5_000
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val tile = face()
        val canvas = android.graphics.Canvas(bitmap)
        for (y in 0 until side step tile.height) {
            for (x in 0 until side step tile.width) canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
        }
        val before = checksum(bitmap)
        val plan = MaskPlan(side, side, listOf(Mask.exact(MaskShape.RECTANGLE, 2_500f, 2_500f, 2_500f, 2_500f)))
        val outcome =
            try {
                masker.applyMasks(bitmap, plan)
                "completed"
            } catch (e: FaceMaskException.NotEnoughMemory) {
                assertEquals(before, checksum(bitmap), "NotEnoughMemory leaves the bitmap untouched")
                "NotEnoughMemory, untouched"
            }
        if (outcome == "completed") {
            assertNotEquals(before, checksum(bitmap), "the bitmap was masked")
            val row = IntArray(side)
            for (y in 0 until side step 97) {
                bitmap.getPixels(row, 0, side, 0, y, side, 1)
                assertTrue(row.all { it ushr 24 == 0xFF }, "row $y is opaque")
            }
        }
        Log.i(DeviceFixtures.TAG, "in-memory worst case ${side}x$side: $outcome")
    }
}
