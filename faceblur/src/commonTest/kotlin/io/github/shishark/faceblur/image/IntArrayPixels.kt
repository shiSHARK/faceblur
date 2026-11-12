// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.image

import io.github.shishark.faceblur.MutablePixelImage

/** Simple in-memory [MutablePixelImage] backed by one `IntArray` (tests only). */
internal class IntArrayPixels(
    override val width: Int,
    override val height: Int,
    val data: IntArray = IntArray(width * height),
) : MutablePixelImage {
    init {
        require(data.size >= width * height) { "data too small" }
    }

    override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        for (row in 0 until h) data.copyInto(dst, row * w, (y + row) * width + x, (y + row) * width + x + w)
    }

    override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        for (row in 0 until h) src.copyInto(data, (y + row) * width + x, row * w, row * w + w)
    }

    fun copy(): IntArrayPixels = IntArrayPixels(width, height, data.copyOf())
}
