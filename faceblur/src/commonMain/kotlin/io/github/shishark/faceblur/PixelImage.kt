// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * Read access to an image's pixels as **non-premultiplied ARGB_8888** ints (`0xAARRGGBB`), row-major, top-left
 * origin.
 *
 * The library reads only the blocks it needs, so a large photo never has to be copied into one `IntArray`.
 * Implementations must return the same values for the same block until the image is written.
 */
public interface PixelImage {
    /** Width in pixels. */
    public val width: Int

    /** Height in pixels. */
    public val height: Int

    /**
     * Copies the [w] × [h] block whose top-left pixel is ([x], [y]) into [dst], row-major with a row stride of [w].
     * The library only asks for blocks that lie inside the image.
     */
    public fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray)
}

/**
 * Read and write access to an image's pixels as **non-premultiplied ARGB_8888** ints (`0xAARRGGBB`), row-major,
 * top-left origin. Masks are written through [writePixels], block by block, only where masks are placed.
 */
public interface MutablePixelImage : PixelImage {
    /**
     * Writes the [w] × [h] block whose top-left pixel is ([x], [y]) from [src], row-major with a row stride of [w].
     * The library only writes blocks that lie inside the image.
     */
    public fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray)
}
