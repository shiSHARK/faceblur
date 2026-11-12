// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.PixelImage
import io.github.shishark.faceblur.internal.model.Sha256

/**
 * The binding of a file-mode plan to the decoded working image it was made for (ADR 0015 decision 6): SHA-256 over a
 * fixed version tag, the width, the height and every pixel in the platform adapter's channel order (each
 * `0xAARRGGBB` int as four bytes, most significant first, rows top to bottom). `maskFile` computes it again on its own
 * decoded image and refuses a plan whose fingerprint differs (`FaceMaskException.SourceChanged`).
 *
 * A regular class, not a value class, so that no JVM signature erases it to a `ByteArray`. Nothing outside the
 * library can make, read or compare one: the constructor is private, the factory and [matches] are synthetic on the
 * JVM and Android ([HiddenFromJava]), and the digest is a private field without an accessor. It is never written to a
 * file, logged, part of an error message or of `toString()` (which names the type only), and it takes no part in the
 * equality of `Detection` or `MaskPlan`. The JVM boundary test checks all of this.
 *
 * The common SHA-256 is used, read incrementally ([Sha256.Hasher]); the platform digest seam of the model check takes
 * one whole array and is not used here, so the pixels are never copied into one buffer.
 */
internal class ImageFingerprint private constructor(private val digest: ByteArray) {
    /** True when [other] is a fingerprint of the same pixels; false for `null` (a plan without a binding). */
    @HiddenFromJava
    fun matches(other: ImageFingerprint?): Boolean = other != null && digest.contentEquals(other.digest)

    /** Names the type only: the digest never appears in text. */
    override fun toString(): String = "ImageFingerprint"

    companion object {
        /** The version tag; a new fingerprint rule gets a new tag. */
        private val TAG: ByteArray = "FaceBlur image fingerprint v1".encodeToByteArray() + byteArrayOf(0)

        /** At most this many pixels are read per band (256 KiB of ints plus 1 MiB of bytes). */
        private const val BAND_PIXELS = 65_536

        /**
         * The fingerprint of [image], read in bands of whole rows of at most [BAND_PIXELS] pixels (one row when a row
         * is longer), never as a second full-size copy.
         */
        @HiddenFromJava
        fun of(image: PixelImage): ImageFingerprint {
            val width = image.width
            val height = image.height
            val hasher = Sha256.Hasher()
            hasher.update(TAG)
            hasher.update(intBytes(width))
            hasher.update(intBytes(height))
            if (width > 0 && height > 0) {
                val rowsPerBand = maxOf(1, BAND_PIXELS / width)
                val ints = IntArray(rowsPerBand * width)
                val bytes = ByteArray(ints.size * 4)
                var y = 0
                while (y < height) {
                    val rows = minOf(rowsPerBand, height - y)
                    val n = rows * width
                    image.readPixels(0, y, width, rows, ints)
                    for (i in 0 until n) {
                        val v = ints[i]
                        val o = i * 4
                        bytes[o] = (v ushr 24).toByte()
                        bytes[o + 1] = (v ushr 16).toByte()
                        bytes[o + 2] = (v ushr 8).toByte()
                        bytes[o + 3] = v.toByte()
                    }
                    hasher.update(bytes, 0, n * 4)
                    y += rows
                }
            }
            return ImageFingerprint(hasher.finish())
        }

        private fun intBytes(v: Int): ByteArray =
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}
