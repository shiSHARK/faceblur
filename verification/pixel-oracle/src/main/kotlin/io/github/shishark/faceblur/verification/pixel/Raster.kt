// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/** A decoded or generated image as non-premultiplied ARGB, row by row. */
public class Raster(public val width: Int, public val height: Int, public val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty raster" }
        require(argb.size.toLong() == width.toLong() * height) { "pixel array does not match the size" }
    }

    public operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    public fun copy(): Raster = Raster(width, height, argb.copyOf())

    public companion object {
        public fun of(width: Int, height: Int, pixel: (Int, Int) -> Int): Raster {
            val data = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) data[y * width + x] = pixel(x, y)
            return Raster(width, height, data)
        }
    }
}

/** The image could not be decoded safely (corrupt, unsupported, or larger than [PixelDecoder.MAX_PIXELS]). */
public class UndecodableImageException(message: String) : Exception(message)

/**
 * Decodes PNG and JPEG with the JDK (`javax.imageio`), not with any library code. Embedded colour profiles
 * and grey-scale images are converted to sRGB by the JDK; use RGB fixtures for exact comparisons.
 */
public object PixelDecoder {
    /** Most pixels decoded; a header announcing more is refused before any pixel buffer exists. */
    public const val MAX_PIXELS: Long = 100_000_000L

    public fun decode(bytes: ByteArray): Raster {
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) throw UndecodableImageException("no decoder accepts the data")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) {
                        throw UndecodableImageException("image of $w x $h is empty or above $MAX_PIXELS pixels")
                    }
                    val image = reader.read(0)
                    val data = IntArray(w * h)
                    image.getRGB(0, 0, w, h, data, 0, w)
                    return Raster(w, h, data)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: IOException) {
            throw UndecodableImageException("decoder failed: ${e::class.simpleName}")
        } catch (e: RuntimeException) {
            throw UndecodableImageException("decoder failed: ${e::class.simpleName}")
        }
    }
}
