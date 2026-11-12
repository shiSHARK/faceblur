// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.filemode.JpegParser
import io.github.shishark.faceblur.filemode.OutputValidator
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic pictures and the pixel-level output check of the file-mode host tests (no photo, no face image). */
internal object TestImages {
    /** Largest mean channel difference to the reference inside a mask (two JPEG generations of a smooth fill). */
    const val TOLERANCE = 8.0

    /** Smallest mean channel difference a mask must make against the unmasked source (4 × [TOLERANCE]). */
    const val SEPARATION = 32.0

    /** Largest mean channel difference to the source away from the masks. */
    const val OUTSIDE_TOLERANCE = 8.0

    /** 8 px blocks: a colour gradient with a ±64 brightness checker, so every mask clearly changes it. */
    fun picture(width: Int, height: Int, alpha: (Int, Int) -> Int = { _, _ -> 255 }): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = if ((x / 8 + y / 8) % 2 == 0) 64 else -64
                val c = Color.argb(alpha(x, y), 64 + (x * 127) / width + d, 64 + (y * 127) / height + d, 128 + d)
                b.setPixel(x, y, c)
            }
        }
        return b
    }

    fun jpeg(width: Int, height: Int): ByteArray = encode(picture(width, height), Bitmap.CompressFormat.JPEG, 92)

    fun png(bitmap: Bitmap): ByteArray = encode(bitmap, Bitmap.CompressFormat.PNG, 100)

    private fun encode(b: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(b.compress(format, quality, out))
        return out.toByteArray()
    }

    /** [jpeg] with an EXIF APP1 (big-endian TIFF, one IFD0 entry: Orientation = [orientation]) right after SOI. */
    fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
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

    fun decodedSize(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    /**
     * The written file passes the library's validator, carries only the fixed APP0 and APP1 (no MPF, no ICC, nothing
     * else) and, inside every mask of [masks], holds the masked [reference] pixels and clearly not the [unmasked] ones;
     * away from the masks it is the source.
     */
    fun assertMaskedFile(output: ByteArray, reference: Bitmap, unmasked: Bitmap, masks: List<Mask>) {
        assertEquals(emptyList(), OutputValidator.findings(output, MetadataPolicy.StripAll), "PRIV-007 marker walk")
        val appn = JpegParser.parse(output).segments.map { it.marker }.filter { it in 0xE0..0xEF }
        assertEquals(listOf(0xE0, 0xE1), appn, "only the fixed JFIF APP0 and the EXIF APP1")
        assertTrue(masks.isNotEmpty(), "the fake model reports a face")
        val decoded = PixelChecks.decode(output)
        assertEquals(reference.width to reference.height, decoded.width to decoded.height)
        for (c in PixelChecks.compare(decoded, reference, unmasked, masks, 1.0)) {
            assertTrue(c.referenceToUnmasked >= SEPARATION, "control: the mask changes the source ($c)")
            assertTrue(c.toReference <= TOLERANCE, "the output holds the masked pixels ($c)")
            assertTrue(c.toUnmasked >= SEPARATION, "the output differs from the unmasked source ($c)")
        }
        val far = assertNotNull(PixelChecks.freeCorner(decoded.width, decoded.height, masks, 1.0))
        val outside = PixelChecks.meanDiff(decoded, unmasked, far)
        assertTrue(outside <= OUTSIDE_TOLERANCE, "away from the masks the output is the source (%.1f)".format(outside))
    }
}
