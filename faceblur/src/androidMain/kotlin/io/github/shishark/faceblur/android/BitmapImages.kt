// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.os.Build
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.PixelImage

/**
 * The bitmap rules of in-memory mode (ADR 0003 decisions 1 and 2), checked before any work: a bitmap that is
 * recycled, `HARDWARE`, not `ARGB_8888` or, for the masking calls, not in the sRGB colour space or immutable is
 * refused with [FaceMaskException.UnsupportedImage]. The message starts with the reason (`RECYCLED`, `HARDWARE`,
 * `CONFIG`, `IMMUTABLE`) and names the fix; the library never copies a bitmap.
 *
 * Why sRGB for the masking calls: `getPixels`/`setPixels` convert between the bitmap's colour space and sRGB, and the
 * mask stage reads every written run back and requires each opaque pixel to be unchanged (a lost write is never
 * accepted). In another colour space that round trip is not exact, so the copy would stop half way with
 * `MaskWriteFailed`; refusing the bitmap before any work keeps it untouched instead.
 */
internal object BitmapRules {
    /** The documented copy an app can make itself (one full-size allocation). */
    private const val COPY =
        "or copy it once with bitmap.copy(Bitmap.Config.ARGB_8888, true) and continue with the copy"

    /** The documented copy into sRGB (Android 13+ `Bitmap.setColorSpace` is not a conversion). */
    private const val COPY_SRGB =
        "or draw it once into Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, " +
            "ColorSpace.get(ColorSpace.Named.SRGB)) and continue with that bitmap"

    /** The decode settings that give a bitmap the masking calls accept. */
    private const val DECODE = "decode with ImageDecoder.ALLOCATOR_SOFTWARE and isMutableRequired = true"

    /** Refuses [bitmap] for `detect`-style reads (it may be immutable) or, with [write], for the masking calls. */
    fun check(bitmap: Bitmap, write: Boolean) {
        if (bitmap.isRecycled) throw FaceMaskException.UnsupportedImage("RECYCLED: the bitmap was recycled")
        val config: Bitmap.Config? = bitmap.config
        if (config == Bitmap.Config.HARDWARE) {
            throw FaceMaskException.UnsupportedImage(
                "HARDWARE: the pixels of a HARDWARE bitmap cannot be read or written; $DECODE, $COPY",
            )
        }
        if (config != Bitmap.Config.ARGB_8888) {
            throw FaceMaskException.UnsupportedImage(
                "CONFIG: the bitmap's config is ${config?.name ?: "unknown"}, only ARGB_8888 is supported; $COPY",
            )
        }
        if (write && bitmap.colorSpace?.isSrgb != true) {
            throw FaceMaskException.UnsupportedImage(
                "CONFIG: the bitmap's colour space is ${bitmap.colorSpace?.name ?: "unknown"}, only sRGB is " +
                    "supported for masking; decode with ImageDecoder setTargetColorSpace(ColorSpace.get(" +
                    "ColorSpace.Named.SRGB)), $COPY_SRGB",
            )
        }
        if (write && !bitmap.isMutable) {
            throw FaceMaskException.UnsupportedImage(
                "IMMUTABLE: masks are written in place, so the bitmap must be mutable; $DECODE, $COPY",
            )
        }
    }
}

/**
 * Android 14+ (API 34): the HDR gain map of a bitmap, a second picture of the unmasked scene that a later encode would
 * write (ADR 0003 decision 3). Below API 34 the platform has no gain maps.
 */
internal object GainMaps {
    fun present(bitmap: Bitmap): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()

    /** Removes the gain map of [bitmap]; true when there was one. */
    fun remove(bitmap: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !bitmap.hasGainmap()) return false
        bitmap.gainmap = null
        return true
    }
}

/**
 * A bitmap of `detect` as a [PixelImage] (it may be immutable). `Bitmap.getPixels` returns non-premultiplied sRGB
 * ARGB ints by definition, whatever the bitmap's colour space.
 */
internal class BitmapReadPixels(private val bitmap: Bitmap) : PixelImage {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        bitmap.getPixels(dst, 0, w, x, y, w, h)
    }
}

/**
 * The caller's bitmap of `applyMasks` and `maskInPlace` as a [MutablePixelImage], with the gain-map rule of ADR 0003
 * decision 3: the gain map is removed immediately before the first pixel is written (the mask stage writes only in its
 * final copy, after its last cancellation check), or by [finish] after a call that completed without writing (no
 * face, an empty plan). A call that throws before the copy leaves the bitmap untouched, gain map included.
 */
internal class InPlaceBitmap(private val bitmap: Bitmap) : MutablePixelImage {
    private val pixels = BitmapPixels(bitmap)
    private var started = false

    /** True when this call removed a gain map. */
    var gainMapRemoved: Boolean = false
        private set

    override val width: Int get() = pixels.width
    override val height: Int get() = pixels.height

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = pixels.readPixels(x, y, w, h, dst)

    override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        if (!started) {
            started = true
            gainMapRemoved = GainMaps.remove(bitmap)
        }
        pixels.writePixels(x, y, w, h, src)
    }

    /** After a masking call returned: removes the gain map if no pixel was written (nothing else is left to do). */
    fun finish() {
        if (!started) {
            started = true
            gainMapRemoved = GainMaps.remove(bitmap)
        }
    }
}
