// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

/**
 * A small, independent walk over the segments of a JPEG file: what a saved export may carry and what not. It shares no
 * code with the library. It lists problems; an empty list means the file has one image and no metadata beyond what the
 * library's output shape allows (a JFIF header, one EXIF segment that holds only Orientation = 1, an ICC colour
 * profile, and the coding segments).
 */
internal object JpegAudit {
    class Result(val problems: List<String>, val segments: List<String>)

    fun audit(data: ByteArray): Result {
        val problems = mutableListOf<String>()
        val segments = mutableListOf<String>()
        if (data.size < 4 || u8(data, 0) != 0xFF || u8(data, 1) != SOI) {
            return Result(listOf("not a JPEG: no start-of-image marker"), segments)
        }
        var pos = 2
        var frames = 0
        var ended = false
        while (pos < data.size && !ended) {
            if (u8(data, pos) != 0xFF) {
                problems += "no marker at offset $pos"
                break
            }
            while (pos + 1 < data.size && u8(data, pos + 1) == 0xFF) pos++ // fill bytes
            if (pos + 1 >= data.size) break
            val marker = u8(data, pos + 1)
            when {
                marker == EOI -> {
                    ended = true
                    pos += 2
                }

                marker in RST_FIRST..RST_LAST || marker == TEM -> pos += 2

                marker == SOI -> {
                    problems += "second start-of-image at offset $pos (a second image)"
                    pos += 2
                }

                else -> {
                    if (pos + 4 > data.size) {
                        problems += "truncated segment at offset $pos"
                        break
                    }
                    val length = (u8(data, pos + 2) shl 8) or u8(data, pos + 3)
                    if (length < 2 || pos + 2 + length > data.size) {
                        problems += "segment length out of range at offset $pos"
                        break
                    }
                    val payload = data.copyOfRange(pos + 4, pos + 2 + length)
                    if (marker in SOF_FIRST..SOF_LAST && marker != DHT && marker != JPG && marker != DAC) frames++
                    segments += name(marker, payload)
                    problems += segmentProblems(marker, payload, pos)
                    pos += 2 + length
                    if (marker == SOS) pos = skipScan(data, pos)
                }
            }
        }
        if (!ended) problems += "no end-of-image marker"
        if (ended && pos != data.size) problems += "${data.size - pos} bytes after the end-of-image marker"
        if (frames != 1) problems += "$frames frames (one expected)"
        for (word in FORBIDDEN_TEXT) {
            if (indexOf(data, word.toByteArray(Charsets.ISO_8859_1)) >= 0) problems += "the file holds \"$word\""
        }
        return Result(problems, segments)
    }

    private fun segmentProblems(marker: Int, payload: ByteArray, at: Int): List<String> = when (marker) {
        APP0 -> if (startsWith(payload, "JFIF\u0000")) emptyList() else listOf("APP0 that is not JFIF at $at")

        APP1 -> when {
            startsWith(payload, "Exif\u0000\u0000") -> exifProblems(payload.copyOfRange(6, payload.size))
            startsWith(payload, "http://ns.adobe.com/xap/1.0/") -> listOf("XMP packet at $at")
            else -> listOf("unknown APP1 segment at $at")
        }

        APP2 -> when {
            startsWith(payload, "ICC_PROFILE\u0000") -> emptyList()
            startsWith(payload, "MPF\u0000") -> listOf("multi-picture (MPF) segment at $at: a second image")
            else -> listOf("unknown APP2 segment at $at")
        }

        in APP3..APP15 -> listOf("application segment APP${marker - APP0} at $at")

        COM -> listOf("comment segment at $at")

        else -> emptyList()
    }

    /** The EXIF block may hold exactly one tag, Orientation = 1, and no thumbnail or second directory. */
    private fun exifProblems(tiff: ByteArray): List<String> {
        if (tiff.size < MIN_TIFF) return listOf("EXIF block too short")
        val little = tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte()
        val big = tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte()
        if (!little && !big) return listOf("EXIF block with no byte-order mark")

        fun u16(at: Int) = if (little) {
            u8(tiff, at) or (u8(tiff, at + 1) shl 8)
        } else {
            (u8(tiff, at) shl 8) or
                u8(tiff, at + 1)
        }

        fun u32(at: Int) = if (little) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        val ifd = u32(4).toInt()
        if (ifd < 0 || ifd + 2 > tiff.size) return listOf("EXIF directory out of range")
        val count = u16(ifd)
        val next = ifd + 2 + count * ENTRY + 4
        if (count != 1 || next > tiff.size) return listOf("EXIF directory with $count tags (only Orientation expected)")
        val tag = u16(ifd + 2)
        val value = u16(ifd + 2 + 8)
        val problems = mutableListOf<String>()
        if (tag != ORIENTATION || value != 1) problems += "EXIF tag $tag = $value (only Orientation = 1 expected)"
        if (u32(next - 4) != 0L) problems += "EXIF block with a second directory (thumbnail)"
        return problems
    }

    /** Past the entropy-coded data of one scan: to the next marker that is not a stuffed byte or a restart marker. */
    private fun skipScan(data: ByteArray, from: Int): Int {
        var pos = from
        while (pos + 1 < data.size) {
            if (u8(data, pos) == 0xFF) {
                val next = u8(data, pos + 1)
                if (next != 0x00 && next !in RST_FIRST..RST_LAST && next != 0xFF) return pos
            }
            pos++
        }
        return data.size
    }

    private fun name(marker: Int, payload: ByteArray): String = when (marker) {
        in APP0..APP15 ->
            "APP${marker - APP0}" +
                (if (marker == APP1 && startsWith(payload, "Exif\u0000\u0000")) "(Exif)" else "") +
                (if (marker == APP2 && startsWith(payload, "ICC_PROFILE\u0000")) "(ICC)" else "")

        COM -> "COM"

        SOS -> "SOS"

        else -> "0x" + marker.toString(HEX).uppercase()
    }

    private fun startsWith(data: ByteArray, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return data.size >= bytes.size && bytes.indices.all { data[it] == bytes[it] }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        var i = 0
        while (i + needle.size <= data.size) {
            if (needle.indices.all { data[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }

    private fun u8(data: ByteArray, at: Int) = data[at].toInt() and 0xFF

    /** What no export may mention: XMP and gain-map (Ultra HDR) containers, in any segment or after the image. */
    private val FORBIDDEN_TEXT = listOf("hdrgm", "GContainer", "xmpmeta", "ns.adobe.com", "MPF\u0000")

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val TEM = 0x01
    private const val RST_FIRST = 0xD0
    private const val RST_LAST = 0xD7
    private const val SOF_FIRST = 0xC0
    private const val SOF_LAST = 0xCF
    private const val DHT = 0xC4
    private const val JPG = 0xC8
    private const val DAC = 0xCC
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP3 = 0xE3
    private const val APP15 = 0xEF
    private const val COM = 0xFE
    private const val ORIENTATION = 0x0112
    private const val MIN_TIFF = 8
    private const val ENTRY = 12
    private const val HEX = 16
}
