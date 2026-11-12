// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.security.MessageDigest

/** What the checker found out about one ICC profile (SHA-256 and description are for tests, never for logs). */
internal class IccResult(val sha256: String, val description: String?)

internal fun sha256Hex(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes, from, to - from)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Reader for the ICC profile header and the `desc` tag. All reads are bounds-checked. */
internal object IccInspector {
    private const val HEADER_SIZE = 128
    private const val RESERVED_FROM = 100

    /**
     * [from] .. [to] is the profile (the APP2 payload after `ICC_PROFILE\0`, sequence and count bytes).
     * Findings are reported through [report]; the result is null if the profile is not even a profile.
     */
    fun inspect(data: ByteArray, from: Int, to: Int, report: (FindingCode, String) -> Unit): IccResult? {
        val length = to - from
        if (length < HEADER_SIZE + 4) {
            report(FindingCode.ICC_MALFORMED, "ICC profile shorter than its header")
            return null
        }
        if (u32(data, from).toInt() != length) {
            report(FindingCode.ICC_MALFORMED, "ICC profile size field does not match the segment")
        }
        if (String(data, from + 36, 4, Charsets.ISO_8859_1) != "acsp") {
            report(FindingCode.ICC_MALFORMED, "ICC signature 'acsp' missing")
        }
        if (String(data, from + 16, 4, Charsets.ISO_8859_1) != "RGB ") {
            report(FindingCode.ICC_NOT_RGB, "ICC colour space is not RGB")
        }
        structure(data, from, length, report)
        return IccResult(sha256Hex(data, from, to), description(data, from, length))
    }

    /** Header reserved bytes zero, tag table consistent, and no non-zero byte outside header, table and tags. */
    private fun structure(data: ByteArray, from: Int, length: Int, report: (FindingCode, String) -> Unit) {
        val tagCount = u32(data, from + HEADER_SIZE)
        if (tagCount > (length - HEADER_SIZE - 4) / 12) {
            report(FindingCode.ICC_MALFORMED, "ICC tag count does not fit the profile")
            return
        }
        val tableEnd = HEADER_SIZE + 4 + 12 * tagCount.toInt()
        val covered = BooleanArray(length)
        for (i in 0 until tableEnd) covered[i] = true
        for (i in RESERVED_FROM until HEADER_SIZE) {
            if (data[from + i].toInt() != 0) {
                report(FindingCode.ICC_MALFORMED, "ICC header reserved bytes are not zero")
                break
            }
        }
        for (i in 0 until tagCount.toInt()) {
            val entry = from + HEADER_SIZE + 4 + i * 12
            val offset = u32(data, entry + 4)
            val size = u32(data, entry + 8)
            if (offset < tableEnd || offset + size > length) {
                report(FindingCode.ICC_MALFORMED, "ICC tag data lies outside the profile")
                return
            }
            // Tags may legally share data (identical curves), so ranges are marked, not checked for overlap.
            for (b in offset.toInt() until (offset + size).toInt()) covered[b] = true
        }
        var hidden = 0
        for (i in 0 until length) if (!covered[i] && data[from + i].toInt() != 0) hidden++
        if (hidden > 0) {
            report(FindingCode.ICC_MALFORMED, "$hidden non-zero bytes in the ICC profile belong to no tag")
        }
    }

    private fun description(data: ByteArray, from: Int, length: Int): String? {
        val tagCount = u32(data, from + HEADER_SIZE)
        if (tagCount < 0 || tagCount > (length - HEADER_SIZE - 4) / 12) return null
        for (i in 0 until tagCount.toInt()) {
            val entry = from + HEADER_SIZE + 4 + i * 12
            if (String(data, entry, 4, Charsets.ISO_8859_1) != "desc") continue
            val offset = u32(data, entry + 4)
            val size = u32(data, entry + 8)
            if (offset < 0 || size < 12 || offset + size > length) return null
            return decode(data, from + offset.toInt(), size.toInt())
        }
        return null
    }

    private fun decode(data: ByteArray, at: Int, size: Int): String? {
        val type = String(data, at, 4, Charsets.ISO_8859_1)
        return when (type) {
            "desc" -> {
                val n = u32(data, at + 8)
                if (n < 1 || n > size - 12) null else String(data, at + 12, (n - 1).toInt(), Charsets.ISO_8859_1)
            }

            "mluc" -> {
                if (size < 28) return null
                val len = u32(data, at + 20)
                val off = u32(data, at + 24)
                if (len < 0 || off < 0 ||
                    off + len > size
                ) {
                    null
                } else {
                    String(data, at + off.toInt(), len.toInt(), Charsets.UTF_16BE)
                }
            }

            else -> null
        }
    }

    private fun u32(d: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
        return v
    }
}
