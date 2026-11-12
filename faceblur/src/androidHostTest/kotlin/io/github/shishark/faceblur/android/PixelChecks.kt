// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import io.github.shishark.faceblur.Mask
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pixel-level checks of the file-mode output: the masked pixels must be the ones that reach the encoded file. The
 * output is compared, inside every mask, with a reference masked in the test (the mask colours depend on the
 * surroundings, so a fixed colour would not do) and with the unmasked source. The same file is in the host and the
 * device tests.
 */
internal object PixelChecks {
    /** A pixel rectangle, right and bottom exclusive. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Int get() = (right - left) * (bottom - top)
    }

    /** Mean absolute differences in one mask's core (largest of R, G and B). */
    class MaskCheck(val core: Box, val toReference: Double, val toUnmasked: Double, val referenceToUnmasked: Double) {
        override fun toString(): String =
            "core %dx%d: output-reference %.1f, output-unmasked %.1f, reference-unmasked %.1f"
                .format(core.right - core.left, core.bottom - core.top, toReference, toUnmasked, referenceToUnmasked)
    }

    /** [bytes] decoded as a mutable `ARGB_8888` bitmap; `BitmapFactory` ignores the EXIF orientation. */
    fun decode(bytes: ByteArray): Bitmap {
        val options =
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "not decodable" }
    }

    /** [bitmap] turned 90° clockwise (EXIF orientation 6), as a new mutable `ARGB_8888` bitmap. */
    fun rotate90(bitmap: Bitmap): Bitmap {
        val turned = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply {
                postRotate(90f)
            },
            false,
        )
        return turned.copy(Bitmap.Config.ARGB_8888, true)
    }

    /** [unmasked] copied and masked in place by [mask] (the test's own reference); returns the copy. */
    fun reference(unmasked: Bitmap, mask: (BitmapPixels) -> Unit): Bitmap =
        unmasked.copy(Bitmap.Config.ARGB_8888, true).also { mask(BitmapPixels(it)) }

    /**
     * The core of [mask] in an image [scale] × the working size: the central half of the mask's box in each
     * direction, inside every mask shape and away from the soft edge, clipped to [width] × [height].
     */
    fun core(mask: Mask, scale: Double, width: Int, height: Int): Box {
        val cx = mask.centerX * scale
        val cy = mask.centerY * scale
        val hx = mask.radiusX * scale / 2
        val hy = mask.radiusY * scale / 2
        return Box(
            ceil(cx - hx).toInt().coerceIn(0, width),
            ceil(cy - hy).toInt().coerceIn(0, height),
            floor(cx + hx).toInt().coerceIn(0, width),
            floor(cy + hy).toInt().coerceIn(0, height),
        )
    }

    /** Mean absolute difference of [a] and [b] over [box], the largest of the three colour channels. */
    fun meanDiff(a: Bitmap, b: Bitmap, box: Box): Double {
        require(a.width == b.width && a.height == b.height) { "sizes differ" }
        val w = box.right - box.left
        val h = box.bottom - box.top
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, box.left, box.top, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, box.left, box.top, w, h) }
        val sum = LongArray(3)
        for (i in pa.indices) {
            sum[0] += abs(Color.red(pa[i]) - Color.red(pb[i]))
            sum[1] += abs(Color.green(pa[i]) - Color.green(pb[i]))
            sum[2] += abs(Color.blue(pa[i]) - Color.blue(pb[i]))
        }
        return sum.max().toDouble() / pa.size
    }

    /** One [MaskCheck] per mask of [masks] (working-image pixels), in an output [scale] × the working size. */
    fun compare(
        output: Bitmap,
        reference: Bitmap,
        unmasked: Bitmap,
        masks: List<Mask>,
        scale: Double,
    ): List<MaskCheck> = masks.map { m ->
        val core = core(m, scale, output.width, output.height)
        check(core.area >= 16) { "mask core too small for a pixel check: $core" }
        MaskCheck(
            core,
            meanDiff(output, reference, core),
            meanDiff(output, unmasked, core),
            meanDiff(reference, unmasked, core),
        )
    }

    /** True when [box] and the outer box of any mask in [masks] (in an image [scale] × the working size) overlap. */
    fun touchesAMask(box: Box, masks: List<Mask>, scale: Double): Boolean = masks.any { m ->
        val l = (m.centerX - m.radiusX) * scale
        val r = (m.centerX + m.radiusX) * scale
        val t = (m.centerY - m.radiusY) * scale
        val b = (m.centerY + m.radiusY) * scale
        l < box.right && r > box.left && t < box.bottom && b > box.top
    }

    /** A 40 × 40 px box 8 px in from a corner of a [width] × [height] image that no mask touches, or null. */
    fun freeCorner(width: Int, height: Int, masks: List<Mask>, scale: Double): Box? = listOf(
        Box(width - 48, height - 48, width - 8, height - 8),
        Box(8, height - 48, 48, height - 8),
        Box(width - 48, 8, width - 8, 48),
        Box(8, 8, 48, 48),
    ).firstOrNull { !touchesAMask(it, masks, scale) }

    /** [bitmap] as a 16-bit-per-channel RGB PNG (every 8-bit value v written as v × 257), no other chunk. */
    fun png16(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val raw = ByteArrayOutputStream(h * (1 + w * 6))
        for (y in 0 until h) {
            raw.write(0) // filter: none
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                for (v in intArrayOf(Color.red(p), Color.green(p), Color.blue(p))) {
                    raw.write(v)
                    raw.write(v)
                }
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val zipped = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (!deflater.finished()) zipped.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", int32(w) + int32(h) + byteArrayOf(16, 2, 0, 0, 0))
        writeChunk(out, "IDAT", zipped.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.encodeToByteArray()
        val crc = CRC32().apply {
            update(t)
            update(data)
        }
        out.write(int32(data.size))
        out.write(t)
        out.write(data)
        out.write(int32(crc.value.toInt()))
    }
}
