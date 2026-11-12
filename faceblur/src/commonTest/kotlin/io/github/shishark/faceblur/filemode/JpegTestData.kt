// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

/**
 * Builds JPEG-shaped byte sequences for structure tests (not decodable images; the JVM tests use real encoder
 * output). All metadata payloads carry [CANARY] so that a copy would be visible.
 */
internal object JpegTestData {
    const val CANARY = "CANARY-7f3a91"

    fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len ushr 8).toByte(), len.toByte()) + payload
    }

    val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** "JFIF\0". */
    val JFIF_ID = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0)

    /** "ICC_PROFILE\0". */
    val ICC_ID = "ICC_PROFILE".encodeToByteArray() + 0

    /** The library's fixed APP0, as the output must carry it. */
    fun jfif() = JpegIds.FIXED_JFIF_APP0.copyOf()

    /** A JFIF APP0 as an encoder may write it (version 1.02, 72 dpi): valid JFIF, but not the library's segment. */
    fun encoderJfif() = seg(0xE0, JFIF_ID + byteArrayOf(1, 2, 1, 0, 72, 0, 72, 0, 0))

    /** A JFIF APP0 with a [w] x [h] RGB thumbnail whose first bytes are [CANARY] (as much as fits). */
    fun jfifWithThumbnail(w: Int = 8, h: Int = 8): ByteArray {
        val thumbnail = ByteArray(w * h * 3) { (it * 7).toByte() }
        val canary = CANARY.encodeToByteArray()
        canary.copyInto(thumbnail, 0, 0, minOf(canary.size, thumbnail.size))
        return seg(0xE0, JFIF_ID + byteArrayOf(1, 1, 0, 0, 1, 0, 1, w.toByte(), h.toByte()) + thumbnail)
    }

    fun dqt() = seg(0xDB, ByteArray(65) { if (it == 0) 0 else 1 })

    /** A baseline frame of [width] x [height] pixels, one component. */
    fun sof0(width: Int = 16, height: Int = 16): ByteArray {
        val h = byteArrayOf((height ushr 8).toByte(), height.toByte())
        val w = byteArrayOf((width ushr 8).toByte(), width.toByte())
        return seg(0xC0, byteArrayOf(8) + h + w + byteArrayOf(1, 1, 0x11, 0))
    }

    /** One DC table (class 0, id 0) with a single 1-bit code for symbol 0: 1 + 16 + 1 bytes. */
    fun dht() = seg(0xC4, ByteArray(18) { if (it == 1) 1 else 0 })

    fun dri() = seg(0xDD, byteArrayOf(0, 4))

    /** SOS header plus entropy data with a stuffed byte (FF 00) and a restart marker (FF D0). */
    fun sos() = seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) +
        byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56, 0xFF.toByte(), 0xD0.toByte(), 0x78)

    fun xmp() = seg(0xE1, "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + 0 + CANARY.encodeToByteArray())

    fun com() = seg(0xFE, CANARY.encodeToByteArray())

    fun iptc() = seg(0xED, "Photoshop 3.0".encodeToByteArray() + 0 + CANARY.encodeToByteArray())

    fun mpf() = seg(0xE2, "MPF".encodeToByteArray() + 0 + CANARY.encodeToByteArray())

    /** A JFXX APP0 extension that holds a thumbnail. */
    fun jfxx() = seg(0xE0, "JFXX".encodeToByteArray() + 0 + byteArrayOf(0x10) + CANARY.encodeToByteArray())

    /** A source EXIF with a canary in ImageDescription (copied raw it would leak). */
    fun sourceExif(): ByteArray {
        val desc = CANARY.encodeToByteArray() + 0
        val tiff =
            byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1) +
                byteArrayOf(0x01, 0x0E, 0, 2) + ExifWriter.u32(desc.size) + ExifWriter.u32(26) +
                ExifWriter.u32(0) + desc
        return seg(0xE1, Exif.HEADER + tiff)
    }

    /**
     * A well-formed single-chunk ICC_PROFILE APP2 (size field, colour space, "acsp" signature) of [size] bytes with
     * [CANARY] in its body: what a source or vendor profile could carry.
     */
    fun icc(space: String = "RGB ", size: Int = 400): ByteArray {
        val profile = ByteArray(size) { (it % 251).toByte() }
        ExifWriter.u32(size).copyInto(profile, 0)
        space.encodeToByteArray().copyInto(profile, 16)
        "acsp".encodeToByteArray().copyInto(profile, 36)
        CANARY.encodeToByteArray().copyInto(profile, 200)
        return seg(0xE2, ICC_ID + byteArrayOf(1, 1) + profile)
    }

    /** Coding segments of a minimal baseline JPEG whose frame is [width] x [height]. */
    fun coding(width: Int = 16, height: Int = 16) = dqt() + sof0(width, height) + dht() + dri() + sos()

    /** What an encoder might produce: its own JFIF, then metadata it should not, then the coding segments and EOI. */
    fun encoderOutput(vararg extra: ByteArray): ByteArray =
        extra.fold(SOI + encoderJfif()) { acc, s -> acc + s } + coding() + EOI

    /** What an encoder might produce for an image of [size]: its own JFIF, the coding segments and EOI. */
    fun encoderOutput(size: Size): ByteArray = SOI + encoderJfif() + coding(size.width, size.height) + EOI

    /** [bytes] with the single occurrence of [find] replaced by [replacement] (same length). */
    fun replaceOnce(bytes: ByteArray, find: ByteArray, replacement: ByteArray): ByteArray {
        require(find.size == replacement.size)
        val hits = (0..bytes.size - find.size).filter { i -> find.indices.all { bytes[i + it] == find[it] } }
        require(hits.size == 1) { "expected one occurrence, found ${hits.size}" }
        return bytes.copyOf().also { replacement.copyInto(it, hits[0]) }
    }

    /** Position of the single occurrence of [find] in [bytes]. */
    fun indexOf(bytes: ByteArray, find: ByteArray): Int {
        val hits = (0..bytes.size - find.size).filter { i -> find.indices.all { bytes[i + it] == find[it] } }
        require(hits.size == 1) { "expected one occurrence, found ${hits.size}" }
        return hits[0]
    }

    fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.encodeToByteArray()
        outer@ for (i in 0..haystack.size - n.size) {
            for (k in n.indices) if (haystack[i + k] != n[k]) continue@outer
            return true
        }
        return false
    }
}
