// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.image

import io.github.shishark.faceblur.PixelImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Area-averaging downscaler for generic [PixelImage]s (equivalent to OpenCV INTER_AREA when shrinking); never
 * upscales. Reads the source in strips of rows through [PixelImage.readPixels], so memory stays proportional to the
 * output plus one strip, not to the source image.
 */
internal object AreaResampler {
    private const val STRIP_ROWS = 16

    /** Output size: longer side ≤ [targetMaxSide], aspect kept, rounded, ≥ 1 px. */
    fun fitSize(w: Int, h: Int, targetMaxSide: Int): Pair<Int, Int> {
        val longSide = max(w, h)
        if (longSide <= targetMaxSide || targetMaxSide <= 0) return w to h
        val s = targetMaxSide.toDouble() / longSide
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /**
     * Resamples the crop ([x], [y], [w], [h]) of [src] to [ow] × [oh] (non-premultiplied ARGB). An unscaled crop is
     * copied as is; a scaled result has alpha 0xFF (detection uses colour only).
     */
    fun resample(src: PixelImage, x: Int, y: Int, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        if (ow == w && oh == h) return IntArray(w * h).also { src.readPixels(x, y, w, h, it) }
        // Per source column: first output column and its weight (a source pixel overlaps ≤ 2 output pixels when
        // shrinking).
        val colA = IntArray(w)
        val colWa = FloatArray(w)
        weights(w, ow, colA, colWa)
        val rowA = IntArray(h)
        val rowWa = FloatArray(h)
        weights(h, oh, rowA, rowWa)
        val acc = FloatArray(ow * oh * 3)
        val line = FloatArray(ow * 3)
        val strip = IntArray(w * min(STRIP_ROWS, h))
        var stripStart = 0
        while (stripStart < h) {
            val rows = min(STRIP_ROWS, h - stripStart)
            src.readPixels(x, y + stripStart, w, rows, strip)
            for (row in 0 until rows) {
                val sy = stripStart + row
                line.fill(0f)
                val base = row * w
                for (sx in 0 until w) {
                    val p = strip[base + sx]
                    val r = ((p shr 16) and 0xFF).toFloat()
                    val g = ((p shr 8) and 0xFF).toFloat()
                    val b = (p and 0xFF).toFloat()
                    val a = colA[sx]
                    val wa = colWa[sx]
                    var o = a * 3
                    line[o] += r * wa
                    line[o + 1] += g * wa
                    line[o + 2] += b * wa
                    val wb = 1f - wa
                    if (wb > 0f && a + 1 < ow) {
                        o += 3
                        line[o] += r * wb
                        line[o + 1] += g * wb
                        line[o + 2] += b * wb
                    }
                }
                val oy = rowA[sy]
                val wa = rowWa[sy]
                var o = oy * ow * 3
                for (i in 0 until ow * 3) acc[o + i] += line[i] * wa
                val wb = 1f - wa
                if (wb > 0f && oy + 1 < oh) {
                    o += ow * 3
                    for (i in 0 until ow * 3) acc[o + i] += line[i] * wb
                }
            }
            stripStart += rows
        }
        val norm = (ow.toFloat() / w) * (oh.toFloat() / h)
        val out = IntArray(ow * oh)
        for (i in 0 until ow * oh) {
            val r = (acc[i * 3] * norm + 0.5f).toInt().coerceIn(0, 255)
            val g = (acc[i * 3 + 1] * norm + 0.5f).toInt().coerceIn(0, 255)
            val b = (acc[i * 3 + 2] * norm + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * For each of [n] source pixels: the first output pixel it overlaps ([first]) and the fraction of the source
     * pixel inside it ([wFirst]); the rest (1 - wFirst) belongs to first + 1.
     */
    private fun weights(n: Int, on: Int, first: IntArray, wFirst: FloatArray) {
        val scale = on.toDouble() / n
        for (s in 0 until n) {
            val a = s * scale
            val b = (s + 1) * scale
            val oa = min(on - 1, a.toInt())
            first[s] = oa
            val edge = (oa + 1).toDouble()
            wFirst[s] = if (b <= edge + 1e-9 || oa == on - 1) 1f else ((edge - a) / (b - a)).toFloat()
        }
    }
}
