// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.image

import io.github.shishark.faceblur.PixelImage
import io.github.shishark.faceblur.detect.DetectionImage

/**
 * [DetectionImage] over any [PixelImage] (the generic path of in-memory mode). Crops are read through
 * [PixelImage.readPixels] and downscaled with [AreaResampler]; the caller's image is never written. Platform adapters
 * may provide faster implementations of [DetectionImage] with the same contract.
 */
internal class PixelDetectionImage(private val source: PixelImage) : DetectionImage {
    override val width: Int get() = source.width
    override val height: Int get() = source.height

    override fun cropScaled(x: Int, y: Int, w: Int, h: Int, targetMaxSide: Int): DetectionImage {
        val cx = x.coerceIn(0, width)
        val cy = y.coerceIn(0, height)
        val cw = w.coerceAtMost(width - cx).coerceAtLeast(1)
        val ch = h.coerceAtMost(height - cy).coerceAtLeast(1)
        val (ow, oh) = AreaResampler.fitSize(cw, ch, targetMaxSide)
        return PixelDetectionImage(ArgbPixels(ow, oh, AreaResampler.resample(source, cx, cy, cw, ch, ow, oh)))
    }

    override fun readArgb(): IntArray = IntArray(width * height).also { source.readPixels(0, 0, width, height, it) }

    /** Nothing to free: crops are plain arrays, and the caller owns the source image. */
    override fun release() = Unit
}

/** A read-only ARGB array image (crops made by the library). */
internal class ArgbPixels(override val width: Int, override val height: Int, private val data: IntArray) : PixelImage {
    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        for (row in 0 until h) data.copyInto(dst, row * w, (y + row) * width + x, (y + row) * width + x + w)
    }
}
