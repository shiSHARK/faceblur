// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException

/** Input formats of file mode (ADR 0005 decision 7). */
internal enum class ImageFormat {
    JPEG,
    HEIC,
    PNG,
    ;

    companion object {
        /**
         * How many first bytes a codec or the stream form reads for [sniff] (fewer only when the source is shorter):
         * enough for the JPEG and PNG signatures and an ISO-BMFF `ftyp` box with 12 compatible brands.
         */
        const val SNIFF_BYTES: Int = 64

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "mif1")
        private val AV1_BRANDS = setOf("avif", "avis")

        /**
         * The format from the first bytes of the source, never from a file name or MIME type. HEIC needs an ISO-BMFF
         * `ftyp` box naming a HEIF brand and no AVIF brand; whether the primary image is HEVC-coded is left to the
         * platform decoder, which fails with [FaceMaskException.UnsupportedImage] otherwise.
         *
         * @throws FaceMaskException.UnsupportedImage for anything else.
         */
        fun sniff(head: ByteArray): ImageFormat {
            if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                head[2] == 0xFF.toByte()
            ) {
                return JPEG
            }
            if (head.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it] }
            ) {
                return PNG
            }
            if (isHeic(head)) return HEIC
            throw FaceMaskException.UnsupportedImage("only JPEG, HEIC and PNG sources are supported")
        }

        private fun isHeic(b: ByteArray): Boolean {
            if (b.size < 16 || brand(b, 4) != "ftyp") return false
            val boxSize = JpegParser.u32(b, 0)
            if (boxSize < 16 || boxSize % 4 != 0L) return false
            val end = minOf(boxSize, b.size.toLong()).toInt()
            val brands = ArrayList<String>()
            brands += brand(b, 8) // major brand; bytes 12..15 are the minor version
            var p = 16
            while (p + 4 <= end) {
                brands += brand(b, p)
                p += 4
            }
            return brands.any { it in HEIC_BRANDS } && brands.none { it in AV1_BRANDS }
        }

        private fun brand(b: ByteArray, at: Int): String =
            (0 until 4).map { (b[at + it].toInt() and 0xFF).toChar() }.joinToString("")
    }
}
