// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.PlanView
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer

/**
 * The 8-bit, four-channel memory layouts of a caller-owned iOS pixel buffer (ADR 0004 decision 2), named by the byte
 * order in memory.
 *
 * The alpha byte must mean what the layout declares (ADR 0011 decision 4a); otherwise
 * `FaceBlurIos.wrapRgba` refuses the buffer with `FaceMaskException.UnsupportedImage` and nothing is written:
 * - premultiplied layouts: every pixel is opaque (alpha 255). An image with any transparent or translucent pixel is
 *   refused, never repaired: flatten it onto an opaque background first;
 * - skip layouts: every fourth byte is 0 or 255 (the colour is then used as stored, whatever that byte is).
 * A caller whose alpha has no meaning declares a skip layout with those bytes at 0 or 255, or flattens the image.
 */
public enum class IosPixelLayout {
    /** Bytes R, G, B, A; colour premultiplied by alpha (`CGImageAlphaInfo.premultipliedLast`, big-endian or default). */
    RGBA_PREMULTIPLIED,

    /** Bytes R, G, B, X; the fourth byte is ignored and the image is opaque (`noneSkipLast`). */
    RGBA_NONE_SKIP,

    /** Bytes B, G, R, A; colour premultiplied by alpha (`premultipliedFirst` with 32-bit little-endian order). */
    BGRA_PREMULTIPLIED,

    /** Bytes B, G, R, X; the fourth byte is ignored and the image is opaque (`noneSkipFirst`, 32-bit little-endian). */
    BGRA_NONE_SKIP,
}

/**
 * A caller-owned buffer seen as opaque ARGB (the common pixel contract, ADR 0002), in its upright view. No pixel is
 * copied and no Objective-C object is created: reads and writes go straight to the buffer, one block at a time.
 * Every pixel of a premultiplied layout must be opaque (checked once at [wrap] and again on every read); `NONE_SKIP`
 * layouts read as opaque. Every write stores alpha (or the ignored byte) 0xFF. Only the blocks the library asks for
 * are touched; every block is bounds-checked, so a request outside the image fails with [FaceMaskException.Internal]
 * before any memory access.
 *
 * [orientation] (1 to 8, the EXIF and `CGImagePropertyOrientation` numbering, as in `UprightBox.fromRaw`) says how the
 * stored pixels ([rawWidth] × [rawHeight]) are shown: the library sees and writes the upright view, whose size is
 * [width] × [height] (the raw size, swapped for 5 to 8). Upright pixel (u, v) is stored at raw pixel (x, y) with
 * 1 (u, v); 2 (W-1-u, v); 3 (W-1-u, H-1-v); 4 (u, H-1-v); 5 (v, u); 6 (v, H-1-u); 7 (W-1-v, H-1-u); 8 (W-1-v, u)
 * (W, H = raw size). The mapping is a permutation of whole pixels, so a mask written in the upright view covers
 * exactly the pixels a viewer shows under it. The orientation is the image's [PlanView]: a plan from `detect` on
 * one orientation is refused through another (`MaskPlanMismatch`).
 */
internal class IosBufferPixels(
    private val base: CPointer<ByteVar>,
    rawWidth: Int,
    rawHeight: Int,
    private val bytesPerRow: Long,
    layout: IosPixelLayout,
    orientation: Int = 1,
) : MutablePixelImage,
    PlanView {
    /** The orientation: plans from `detect` on this view are applied only through the same view. */
    override val planView: Int = orientation

    override val width: Int = if (orientation >= 5) rawHeight else rawWidth
    override val height: Int = if (orientation >= 5) rawWidth else rawHeight

    private val premultiplied =
        layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.BGRA_PREMULTIPLIED
    private val red = if (layout == IosPixelLayout.RGBA_PREMULTIPLIED ||
        layout == IosPixelLayout.RGBA_NONE_SKIP
    ) {
        0
    } else {
        2
    }
    private val blue = 2 - red

    /** Byte offset of upright pixel (0, 0), and the byte steps for one upright column ([du]) and one row ([dv]). */
    private val origin: Long
    private val du: Long
    private val dv: Long

    init {
        val lastX = (rawWidth - 1) * PX
        val lastY = (rawHeight - 1) * bytesPerRow
        val steps = when (orientation) {
            1 -> longArrayOf(0L, PX, bytesPerRow)
            2 -> longArrayOf(lastX, -PX, bytesPerRow)
            3 -> longArrayOf(lastY + lastX, -PX, -bytesPerRow)
            4 -> longArrayOf(lastY, PX, -bytesPerRow)
            5 -> longArrayOf(0L, bytesPerRow, PX)
            6 -> longArrayOf(lastY, -bytesPerRow, PX)
            7 -> longArrayOf(lastY + lastX, -bytesPerRow, -PX)
            8 -> longArrayOf(lastX, bytesPerRow, -PX)
            else -> throw FaceMaskException.InvalidConfiguration("orientation", "1 to 8")
        }
        origin = steps[0]
        du = steps[1]
        dv = steps[2]
    }

    private fun check(x: Int, y: Int, w: Int, h: Int, size: Int) {
        val inside = x >= 0 && y >= 0 && w >= 0 && h >= 0 && x.toLong() + w <= width && y.toLong() + h <= height &&
            w.toLong() * h <= size
        if (!inside) throw FaceMaskException.Internal("a pixel block outside the image was requested")
    }

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        check(x, y, w, h, dst.size)
        var i = 0
        for (row in y until y + h) {
            var o = origin + row * dv + x * du
            for (col in 0 until w) {
                // ADR 0011 4a again on every read: the buffer may have changed since wrap (a reused wrapper);
                // detection reads every pixel before any mask is written, so nothing is written.
                if (premultiplied && base[o + 3].toInt() and 0xFF != 0xFF) throw notOpaque()
                val r = base[o + red].toInt() and 0xFF
                val g = base[o + 1].toInt() and 0xFF
                val b = base[o + blue].toInt() and 0xFF
                dst[i++] = OPAQUE or (r shl 16) or (g shl 8) or b
                o += du
            }
        }
    }

    override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        check(x, y, w, h, src.size)
        var i = 0
        for (row in y until y + h) {
            var o = origin + row * dv + x * du
            for (col in 0 until w) {
                // Every pixel the library reads is opaque, and masks never lower alpha (ADR 0002 decision 10), so the
                // stored form is the colour with alpha 255 in every layout.
                val p = src[i++]
                base[o + red] = (p shr 16).toByte()
                base[o + 1] = (p shr 8).toByte()
                base[o + blue] = p.toByte()
                base[o + 3] = 0xFF.toByte()
                o += du
            }
        }
    }

    internal companion object {
        private const val OPAQUE = 0xFF shl 24
        private const val PX = 4L

        private fun notOpaque() = FaceMaskException.UnsupportedImage(
            "a pixel of a premultiplied layout is not opaque; flatten the image onto an opaque background first",
        )

        /** Validates the buffer description; every failure is a [FaceMaskException.InvalidConfiguration]. */
        fun wrap(
            address: Long,
            width: Int,
            height: Int,
            bytesPerRow: Int,
            layout: IosPixelLayout,
            orientation: Int = 1,
        ): IosBufferPixels {
            if (width <= 0) throw FaceMaskException.InvalidConfiguration("width", "1 or more")
            if (height <= 0) throw FaceMaskException.InvalidConfiguration("height", "1 or more")
            if (bytesPerRow.toLong() < width * 4L) {
                throw FaceMaskException.InvalidConfiguration("bytesPerRow", "at least width × 4")
            }
            if (orientation !in 1..8) throw FaceMaskException.InvalidConfiguration("orientation", "1 to 8")
            // The last byte of the buffer must be addressable without overflow.
            val span = bytesPerRow.toLong() * (height - 1) + width * 4L
            if (address == 0L || address < 0L || address > Long.MAX_VALUE - span) {
                throw FaceMaskException.InvalidConfiguration("address", "a valid, non-null buffer address")
            }
            val base = address.toCPointer<ByteVar>()
                ?: throw FaceMaskException.InvalidConfiguration("address", "a valid, non-null buffer address")
            checkAlpha(base, width, height, bytesPerRow.toLong(), layout)
            return IosBufferPixels(base, width, height, bytesPerRow.toLong(), layout, orientation)
        }

        /**
         * ADR 0011 decision 4a, before detection and before any write: a premultiplied layout with any pixel that is
         * not opaque (alpha below 255), or a skip layout with a fourth byte other than 0 or 255, refuses the whole
         * buffer with [FaceMaskException.UnsupportedImage]. One read pass over the pixel rows; row padding is never
         * read. Refused, never repaired.
         */
        private fun checkAlpha(
            base: CPointer<ByteVar>,
            width: Int,
            height: Int,
            bytesPerRow: Long,
            layout: IosPixelLayout,
        ) {
            val premultiplied =
                layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.BGRA_PREMULTIPLIED
            for (row in 0 until height) {
                var o = row * bytesPerRow + 3
                for (col in 0 until width) {
                    val a = base[o].toInt() and 0xFF
                    if (premultiplied) {
                        if (a != 0xFF) throw notOpaque()
                    } else if (a != 0 && a != 0xFF) {
                        throw FaceMaskException.UnsupportedImage(
                            "a skip layout needs every fourth byte to be 0 or 255; flatten a translucent image first",
                        )
                    }
                    o += 4
                }
            }
        }
    }
}
