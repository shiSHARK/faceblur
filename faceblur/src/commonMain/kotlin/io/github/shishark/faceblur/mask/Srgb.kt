// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import kotlin.math.pow

/**
 * sRGB ⇄ linear-light lookup tables, created once on first use (before any working area is reserved). Kept apart from
 * the stage files so that the source gate can check those for array creation.
 */
internal object Srgb {
    private const val N = 4096

    /** 8-bit sRGB → linear 0..1. */
    val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private val fromLinearLut = IntArray(N + 1) { i ->
        val l = i.toFloat() / N
        val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Linear 0..1 → 8-bit sRGB. */
    fun encode(linear: Float): Int {
        val i = (linear * N + 0.5f).toInt()
        return fromLinearLut[
            if (i < 0) {
                0
            } else if (i > N) {
                N
            } else {
                i
            },
        ]
    }

    fun encodeRgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)
}
