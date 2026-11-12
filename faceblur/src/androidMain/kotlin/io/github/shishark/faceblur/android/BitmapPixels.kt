// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import io.github.shishark.faceblur.MutablePixelImage

/**
 * A mutable `ARGB_8888` sRGB [Bitmap] as a [MutablePixelImage]. `Bitmap.getPixels`/`setPixels` exchange
 * non-premultiplied sRGB ARGB ints by definition (the bitmap itself stays premultiplied; the platform converts per
 * call), which is exactly the [MutablePixelImage] contract.
 */
internal class BitmapPixels(private val bitmap: Bitmap) : MutablePixelImage {
    init {
        require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) { "a mutable ARGB_8888 bitmap" }
    }

    override val width: Int = bitmap.width
    override val height: Int = bitmap.height

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        bitmap.getPixels(dst, 0, w, x, y, w, h)
    }

    override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        bitmap.setPixels(src, 0, w, x, y, w, h)
    }
}
