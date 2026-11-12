// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** What the masking step reports: the number of faces found and of masks drawn. */
class MaskCounts(val faces: Int, val masks: Int)

/**
 * The in-memory pipeline of an app that does its own steps around FaceBlur: decode, mask in place, scale down, draw a
 * watermark, encode once. The order is the one of the integration guide. [mask] is the one call into the library
 * (`FaceMasker.maskInPlace(bitmap)`); [sourceOf] opens the chosen photo for the decoder; everything stays in memory: no file, no cache, no log, and the unmasked pixels
 * exist only in the working bitmap, which is never shown and is recycled in every case.
 */
class BitmapPipeline(
    private val watermark: String,
    private val sourceOf: (Uri) -> ImageDecoder.Source,
    private val mask: (Bitmap, CancellationToken) -> MaskCounts,
) : PipelineEngine<Uri> {
    /** Number of encodes since creation; a test asserts that one run encodes exactly once. */
    internal var encodes = 0
        private set

    override fun run(source: Uri, output: OutputOption, cancelled: () -> Boolean): PipelineOutcome {
        var working: Bitmap? = null
        try {
            // 1. Decode with the platform decoder: it applies the EXIF orientation, so every later step sees an
            //    upright image. Long side capped at the detection size so that the bitmap stays small.
            val decoded = decode(source)
            working = decoded
            stopIfCancelled(cancelled)

            // 2. FaceBlur in place, BEFORE any scale below the detection size, so that small faces are still found.
            val counts = mask(decoded, token(cancelled))

            // 3. Scale down only now, after the faces are covered.
            val scaled = scaleDown(decoded, output.maxOutputLongSide)
            if (scaled !== decoded) decoded.recycle()
            working = scaled
            stopIfCancelled(cancelled)

            // 4. The app's own drawing, after masking: it can no longer change what the detector sees.
            drawWatermark(scaled)

            // 5. Encode once, from the masked pixels, in memory. The encoder adds no metadata.
            val bytes = encode(scaled)
            working = null
            scaled.recycle()
            return PipelineOutcome(counts.faces, counts.masks, picture(bytes))
        } catch (e: DemoFailure) {
            throw e
        } catch (e: FaceMaskException) {
            throw failureOf(e)
        } catch (e: IOException) {
            throw DemoFailure(if (e is ImageDecoder.DecodeException) FailureKind.UNSUPPORTED_IMAGE else FailureKind.IO)
        } catch (e: SecurityException) {
            throw DemoFailure(FailureKind.IO)
        } catch (e: OutOfMemoryError) {
            throw DemoFailure(FailureKind.NOT_ENOUGH_MEMORY)
        } finally {
            working?.recycle()
        }
    }

    private fun decode(source: Uri): Bitmap {
        val bitmap = ImageDecoder.decodeBitmap(sourceOf(source)) { decoder, info, _ ->
            // A software, mutable, sRGB bitmap: the form FaceBlur accepts for in-place masking.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            fitTo(decoder, info.size.width, info.size.height, DETECTION_LONG_SIDE)
        }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        // A wide-gamut or high-bit-depth source: convert once to the form the library accepts.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()
        return copy ?: throw DemoFailure(FailureKind.NOT_ENOUGH_MEMORY)
    }

    private fun fitTo(decoder: ImageDecoder, width: Int, height: Int, maxLongSide: Int) {
        val longSide = max(width, height)
        if (longSide <= maxLongSide) return
        val s = maxLongSide.toFloat() / longSide
        decoder.setTargetSize(max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()))
    }

    private fun scaleDown(bitmap: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (maxLongSide <= 0 || longSide <= maxLongSide) return bitmap
        val s = maxLongSide.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * s).roundToInt()),
            max(1, (bitmap.height * s).roundToInt()),
            true,
        )
    }

    private fun drawWatermark(bitmap: Bitmap) {
        val size = max(MIN_TEXT_PX, bitmap.width.toFloat() / TEXT_DIVISOR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            setShadowLayer(size / SHADOW_DIVISOR, 0f, 0f, Color.BLACK)
        }
        Canvas(bitmap).drawText(watermark, size / 2f, bitmap.height - size / 2f, paint)
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        // Android 14+: no gain map goes into the output (the library has already removed it; this keeps it so).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) bitmap.gainmap = null
        val out = ByteArrayOutputStream()
        encodes++
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw DemoFailure(FailureKind.IO)
        return out.toByteArray()
    }

    /** The picture for the screen, decoded from the encoded bytes: what a saved file would show. */
    private fun picture(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            fitTo(decoder, info.size.width, info.size.height, PREVIEW_LONG_SIDE)
        }

    private fun stopIfCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw DemoFailure(FailureKind.CANCELLED)
    }

    private fun token(cancelled: () -> Boolean) = object : CancellationToken {
        override val isCancellationRequested: Boolean get() = cancelled()
    }

    private companion object {
        const val DETECTION_LONG_SIDE = 3_500
        const val PREVIEW_LONG_SIDE = 1_600
        const val JPEG_QUALITY = 90
        const val MIN_TEXT_PX = 16f
        const val TEXT_DIVISOR = 25
        const val SHADOW_DIVISOR = 6f
    }
}
