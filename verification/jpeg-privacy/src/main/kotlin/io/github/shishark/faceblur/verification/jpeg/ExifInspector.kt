// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** Result of inspecting one EXIF APP1 payload. */
internal class ExifResult(val tags: Set<ExifTag>, val orientation: Int?)

/** Raised for structures the inspector cannot follow safely (loops, offsets outside the segment, ...). */
private class ExifMalformed(message: String) : Exception(message)

/**
 * Hardened, bounds-checked reader of the TIFF structure inside an EXIF APP1 payload. It never allocates by a
 * count read from the file, follows every IFD pointer at most once (loop and overlap detection), and accounts
 * for every byte of the structure so that data hidden in unreferenced areas is reported.
 */
internal class ExifInspector(
    private val data: ByteArray,
    private val start: Int,
    private val length: Int,
    private val options: CheckOptions,
    private val report: (FindingCode, Severity, String) -> Unit,
) {
    private val policy = options.policy
    private val allowed = allowedTags(policy)
    private val tags = mutableSetOf<ExifTag>()
    private var orientation: Int? = null
    private var thumbnailOffset: Long? = null
    private var thumbnailLength: Long? = null
    private var littleEndian = true
    private var tiffLength = 0
    private var tiffStart = 0
    private lateinit var covered: BooleanArray
    private val visited = mutableSetOf<Long>()

    fun inspect(): ExifResult {
        try {
            parse()
        } catch (e: ExifMalformed) {
            report(FindingCode.EXIF_UNPARSEABLE, Severity.FAIL, "EXIF structure cannot be followed: ${e.message}")
        }
        return ExifResult(tags, orientation)
    }

    private fun parse() {
        // Payload = "Exif\0\0" + TIFF structure (checked by the caller).
        tiffStart = start + EXIF_PREFIX.size
        tiffLength = length - EXIF_PREFIX.size
        if (tiffLength < 8) throw ExifMalformed("EXIF payload shorter than a TIFF header")
        littleEndian = when {
            data[tiffStart] == 'I'.code.toByte() && data[tiffStart + 1] == 'I'.code.toByte() -> true

            data[tiffStart] == 'M'.code.toByte() && data[tiffStart + 1] == 'M'.code.toByte() -> false

            else -> {
                report(FindingCode.EXIF_BAD_HEADER, Severity.FAIL, "TIFF byte-order mark is neither II nor MM")
                return
            }
        }
        if (u16(2) != 42) {
            report(FindingCode.EXIF_BAD_HEADER, Severity.FAIL, "TIFF magic number is not 42")
            return
        }
        covered = BooleanArray(tiffLength)
        cover(0, 8)
        walkIfd(u32(4), ExifIfd.IFD0)
        if (orientation == null) {
            report(FindingCode.ORIENTATION_MISSING, Severity.FAIL, "EXIF has no Orientation tag (must be written as 1)")
        }
        checkRequiredAndAbsent()
        checkUnreferenced()
    }

    private fun walkIfd(offset: Long, ifd: ExifIfd) {
        if (offset < 8 || offset + 2 > tiffLength) throw ExifMalformed("${ifd.name} offset lies outside the segment")
        if (!visited.add(offset)) throw ExifMalformed("${ifd.name} is reachable twice (loop or overlap)")
        val base = offset.toInt()
        val count = u16(base)
        val end = base.toLong() + 2 + count.toLong() * 12 + 4
        if (end > tiffLength) throw ExifMalformed("${ifd.name} entry count exceeds the segment")
        cover(base, end.toInt())
        val seen = mutableSetOf<Int>()
        val pointers = mutableListOf<Pair<Int, Long>>()
        for (i in 0 until count) {
            val entry = base + 2 + i * 12
            val tag = u16(entry)
            val type = u16(entry + 2)
            val valueCount = u32(entry + 4)
            if (!seen.add(tag)) {
                report(FindingCode.EXIF_DUPLICATE_TAG, Severity.FAIL, "${ExifTag(ifd, tag)} appears more than once")
            }
            val byteCount = TYPE_SIZES[type]?.let { it * valueCount }
            val valueOffset = valueLocation(entry, byteCount, ExifTag(ifd, tag))
            tags.add(ExifTag(ifd, tag))
            if (isPointer(ifd, tag)) {
                if ((type != 4 && type != 13) || valueCount != 1L) {
                    report(
                        FindingCode.EXIF_VALUE_INVALID,
                        Severity.FAIL,
                        "${ExifTag(ifd, tag)} is not a single LONG offset",
                    )
                }
                pointers.add(tag to u32(entry + 8))
                judgePointer(ifd, tag)
            } else {
                judgeTag(ifd, tag, type, valueCount, byteCount, valueOffset)
            }
        }
        val next = u32(base + 2 + count * 12)
        when {
            ifd == ExifIfd.IFD0 && next != 0L -> {
                report(
                    FindingCode.EXIF_THUMBNAIL_IFD1,
                    Severity.FAIL,
                    "IFD0 links to IFD1 (the EXIF thumbnail directory)",
                )
                walkIfd(next, ExifIfd.IFD1)
            }

            ifd != ExifIfd.IFD0 && next != 0L -> throw ExifMalformed("${ifd.name} links to a further IFD")
        }
        if (ifd == ExifIfd.IFD1) coverThumbnail()
        for ((tag, target) in pointers) {
            walkIfd(
                target,
                if (tag ==
                    EXIF_IFD_POINTER
                ) {
                    ExifIfd.EXIF
                } else if (tag == GPS_IFD_POINTER) {
                    ExifIfd.GPS
                } else {
                    ExifIfd.INTEROP
                },
            )
        }
    }

    /** The thumbnail bytes are referenced by IFD1 (JPEGInterchangeFormat and its length); account for them. */
    private fun coverThumbnail() {
        val offset = thumbnailOffset ?: return
        val length = thumbnailLength ?: return
        if (offset < 8 || offset + length > tiffLength) throw ExifMalformed("thumbnail lies outside the segment")
        cover(offset.toInt(), (offset + length).toInt())
    }

    private fun isPointer(ifd: ExifIfd, tag: Int): Boolean =
        (ifd == ExifIfd.IFD0 && (tag == EXIF_IFD_POINTER || tag == GPS_IFD_POINTER)) ||
            (ifd == ExifIfd.EXIF && tag == INTEROP_IFD_POINTER)

    private fun judgePointer(ifd: ExifIfd, tag: Int) {
        when (tag) {
            EXIF_IFD_POINTER -> if (!allowsExifIfd(policy)) {
                report(FindingCode.EXIF_SUBIFD_NOT_ALLOWED, Severity.FAIL, "the policy allows no Exif sub-IFD")
            }

            GPS_IFD_POINTER -> if (!allowsGpsIfd(policy)) {
                report(FindingCode.GPS_NOT_ALLOWED, Severity.FAIL, "the policy allows no GPS IFD")
            }

            else -> report(FindingCode.EXIF_INTEROP_NOT_ALLOWED, Severity.FAIL, "Interoperability IFD is not allowed")
        }
    }

    /** Returns the offset (relative to the TIFF start) where the value bytes live, or -1 if unknown. */
    private fun valueLocation(entry: Int, byteCount: Long?, tag: ExifTag): Int {
        if (byteCount == null) {
            report(FindingCode.EXIF_VALUE_INVALID, Severity.FAIL, "$tag has an unknown value type")
            return -1
        }
        if (byteCount <= 4) {
            // Inline value: the unused bytes of the 4-byte field must be zero.
            val hidden = (byteCount.toInt() until 4).any { data[tiffStart + entry + 8 + it].toInt() != 0 }
            if (hidden) {
                report(
                    FindingCode.EXIF_UNREFERENCED_BYTES,
                    Severity.FAIL,
                    "$tag has non-zero bytes after its inline value",
                )
            }
            return entry + 8
        }
        val offset = u32(entry + 8)
        if (offset < 8 || offset + byteCount > tiffLength) {
            throw ExifMalformed("$tag value lies outside the segment")
        }
        cover(offset.toInt(), (offset + byteCount).toInt())
        return offset.toInt()
    }

    private fun judgeTag(ifd: ExifIfd, tag: Int, type: Int, count: Long, byteCount: Long?, valueOffset: Int) {
        val id = ExifTag(ifd, tag)
        if (ifd == ExifIfd.IFD1) {
            // The whole directory is already reported as the thumbnail; remember where its bytes are.
            if (type == 4 && count == 1L && valueOffset >= 0) {
                if (tag == 0x0201) thumbnailOffset = u32(valueOffset)
                if (tag == 0x0202) thumbnailLength = u32(valueOffset)
            }
            return
        }
        if (id !in allowed) {
            // A pointer-gated IFD that is not allowed at all has been reported once at its pointer.
            val gatedAndRefused =
                (ifd == ExifIfd.EXIF && !allowsExifIfd(policy)) || (ifd == ExifIfd.GPS && !allowsGpsIfd(policy)) ||
                    ifd == ExifIfd.INTEROP
            if (tag == MAKER_NOTE) {
                report(FindingCode.MAKERNOTE_PRESENT, Severity.FAIL, "MakerNote tag present in ${ifd.name}")
            } else if (!gatedAndRefused) {
                report(FindingCode.EXIF_TAG_NOT_ALLOWED, Severity.FAIL, "${tagName(id)} is not allowed by the policy")
            }
            return
        }
        val spec = SPECS[id] ?: return
        if (type !in spec.types || count !in spec.counts) {
            report(
                FindingCode.EXIF_VALUE_INVALID,
                Severity.FAIL,
                "${tagName(id)} has type $type and count $count, outside the allowed shape",
            )
            return
        }
        if (byteCount == null || valueOffset < 0) return
        val problem = spec.validate?.invoke(RawValue(valueOffset, count.toInt()))
        if (problem != null) {
            report(FindingCode.EXIF_VALUE_INVALID, Severity.FAIL, "${tagName(id)}: $problem")
        }
        if (tag == ORIENTATION && ifd == ExifIfd.IFD0) {
            val value = u16(valueOffset)
            if (orientation == null) orientation = value
            if (value != 1) {
                report(FindingCode.ORIENTATION_NOT_1, Severity.FAIL, "Orientation is $value, must be 1")
            }
        }
    }

    private fun checkRequiredAndAbsent() {
        val kp = policy as? MetadataPolicy.KeepAllowlist ?: return
        for (required in kp.requiredTags) {
            if (required !in tags) {
                report(
                    FindingCode.REQUIRED_TAG_MISSING,
                    Severity.FAIL,
                    "${tagName(required)} was in the input and is allowlisted but is missing",
                )
            }
        }
        for (tag in allowed) {
            if (tag.id == ORIENTATION || tag in tags || tag in kp.requiredTags) continue
            report(
                FindingCode.ALLOWLISTED_TAG_ABSENT,
                Severity.INFO,
                "${tagName(tag)} is allowlisted and absent (the input did not carry it)",
            )
        }
    }

    private fun checkUnreferenced() {
        var nonZero = 0
        for (i in 8 until tiffLength) {
            if (!covered[i] && data[tiffStart + i].toInt() != 0) nonZero++
        }
        if (nonZero > 0) {
            report(
                FindingCode.EXIF_UNREFERENCED_BYTES,
                Severity.FAIL,
                "$nonZero non-zero bytes in the EXIF segment are not part of any IFD or value",
            )
        }
    }

    private fun cover(from: Int, to: Int) {
        for (i in from until to) {
            if (covered[i]) throw ExifMalformed("overlapping structures at offset $i")
            covered[i] = true
        }
    }

    private fun u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > tiffLength) throw ExifMalformed("read outside the segment")
        val a = data[tiffStart + offset].toInt() and 0xFF
        val b = data[tiffStart + offset + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    private fun u32(offset: Int): Long {
        val hi = u16(if (littleEndian) offset + 2 else offset).toLong()
        val lo = u16(if (littleEndian) offset else offset + 2).toLong()
        return (hi shl 16) or lo
    }

    /** Access to the bytes of one value for the validators. */
    inner class RawValue(private val offset: Int, val count: Int) {
        fun byte(i: Int): Int = data[tiffStart + offset + i].toInt() and 0xFF

        /** ASCII text without the terminating NUL; null if the field is not NUL-terminated or has an inner NUL. */
        fun text(): String? {
            if (count < 1 || byte(count - 1) != 0) return null
            val sb = StringBuilder()
            for (i in 0 until count - 1) {
                val b = byte(i)
                if (b == 0) return null
                sb.append(b.toChar())
            }
            return sb.toString()
        }

        fun rational(i: Int): Double? {
            val den = u32(offset + i * 8 + 4)
            if (den == 0L) return null
            return u32(offset + i * 8).toDouble() / den.toDouble()
        }
    }

    private class Spec(
        val types: Set<Int>,
        val counts: LongRange,
        val validate: ((ExifInspector.RawValue) -> String?)? = null,
    )

    private companion object {
        val EXIF_PREFIX = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val TYPE_SIZES =
            mapOf(
                1 to 1L, 2 to 1L, 3 to 2L, 4 to 4L, 5 to 8L, 6 to 1L, 7 to 1L, 8 to 2L, 9 to 4L, 10 to 8L,
                11 to 4L, 12 to 8L, 13 to 4L,
            )
        private const val MAX_METRES = 100000.0 // altitude and horizontal error: sane bound, not in the ADR
        private const val BYTE = 1
        private const val ASCII = 2
        private const val SHORT = 3
        private const val RATIONAL = 5

        private val PRINTABLE = 0x20..0x7E

        private fun printableAscii(v: ExifInspector.RawValue): String? {
            val text = v.text() ?: return "not a NUL-terminated ASCII string"
            return if (text.all { it.code in PRINTABLE }) null else "contains characters outside printable ASCII"
        }

        private fun dateTime(v: ExifInspector.RawValue): String? {
            val t = v.text() ?: return "not a NUL-terminated ASCII string"
            val m =
                Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").matchEntire(t)
                    ?: return "not YYYY:MM:DD HH:MM:SS"
            val p = m.groupValues.drop(1).map { it.toInt() }
            return if (validDate(p[0], p[1], p[2]) && p[3] in 0..23 && p[4] in 0..59 && p[5] in 0..60) {
                null
            } else {
                "date or time out of range"
            }
        }

        /** A real calendar date; the ADR names no year bound, so any year 0001..9999 is accepted. */
        private fun validDate(year: Int, month: Int, day: Int): Boolean =
            year in 1..9999 && month in 1..12 && day in 1..java.time.YearMonth.of(year, month).lengthOfMonth()

        private fun offsetTime(v: ExifInspector.RawValue): String? {
            val t = v.text() ?: return "not a NUL-terminated ASCII string"
            val m = Regex("""[+-](\d{2}):(\d{2})""").matchEntire(t) ?: return "not +HH:MM"
            return if (m.groupValues[1].toInt() <= 14 && m.groupValues[2].toInt() <= 59) null else "offset out of range"
        }

        private fun subSec(v: ExifInspector.RawValue): String? {
            val t = v.text() ?: return "not a NUL-terminated ASCII string"
            return if (t.isNotEmpty() && t.length <= 9 && t.all { it in '0'..'9' }) null else "not 1 to 9 digits"
        }

        private fun refOf(allowedRefs: String) = { v: ExifInspector.RawValue ->
            val t = v.text()
            if (t != null && t.length == 1 && t[0] in allowedRefs) null else "reference letter not one of $allowedRefs"
        }

        private fun degrees(limit: Double) = { v: ExifInspector.RawValue ->
            val d = v.rational(0)
            val m = v.rational(1)
            val s = v.rational(2)
            when {
                d == null || m == null || s == null -> "zero denominator"
                d < 0 || m < 0 || s < 0 || m >= 60 || s >= 60 -> "degrees, minutes or seconds out of range"
                d + m / 60 + s / 3600 > limit -> "angle above $limit degrees"
                else -> null
            }
        }

        private fun nonNegativeRational(limit: Double? = null) = { v: ExifInspector.RawValue ->
            val r = v.rational(0)
            when {
                r == null -> "zero denominator"
                r < 0 || (limit != null && r >= limit) -> "out of range"
                else -> null
            }
        }

        private fun gpsTime(v: ExifInspector.RawValue): String? {
            val h = v.rational(0)
            val m = v.rational(1)
            val s = v.rational(2)
            return when {
                h == null || m == null || s == null -> "zero denominator"
                h !in 0.0..23.0 || m !in 0.0..59.0 || s < 0 || s >= 61 -> "time out of range"
                else -> null
            }
        }

        private fun gpsDate(v: ExifInspector.RawValue): String? {
            val t = v.text() ?: return "not a NUL-terminated ASCII string"
            val m = Regex("""(\d{4}):(\d{2}):(\d{2})""").matchEntire(t) ?: return "not YYYY:MM:DD"
            return if (validDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) {
                null
            } else {
                "date out of range"
            }
        }

        val SPECS: Map<ExifTag, Spec> = mapOf(
            ExifTag(ExifIfd.IFD0, ORIENTATION) to Spec(setOf(SHORT), 1L..1L),
            ExifTag(ExifIfd.IFD0, 0x010F) to Spec(setOf(ASCII), 1L..65L, ::printableAscii),
            ExifTag(ExifIfd.IFD0, 0x0110) to Spec(setOf(ASCII), 1L..65L, ::printableAscii),
            ExifTag(ExifIfd.EXIF, 0x9003) to Spec(setOf(ASCII), 20L..20L, ::dateTime),
            ExifTag(ExifIfd.EXIF, 0x9004) to Spec(setOf(ASCII), 20L..20L, ::dateTime),
            ExifTag(ExifIfd.EXIF, 0x9011) to Spec(setOf(ASCII), 7L..7L, ::offsetTime),
            ExifTag(ExifIfd.EXIF, 0x9012) to Spec(setOf(ASCII), 7L..7L, ::offsetTime),
            ExifTag(ExifIfd.EXIF, 0x9291) to Spec(setOf(ASCII), 2L..10L, ::subSec),
            ExifTag(ExifIfd.GPS, 0x00) to Spec(setOf(BYTE), 4L..4L),
            ExifTag(ExifIfd.GPS, 0x01) to Spec(setOf(ASCII), 2L..2L, refOf("NS")),
            ExifTag(ExifIfd.GPS, 0x02) to Spec(setOf(RATIONAL), 3L..3L, degrees(90.0)),
            ExifTag(ExifIfd.GPS, 0x03) to Spec(setOf(ASCII), 2L..2L, refOf("EW")),
            ExifTag(ExifIfd.GPS, 0x04) to Spec(setOf(RATIONAL), 3L..3L, degrees(180.0)),
            ExifTag(ExifIfd.GPS, 0x05) to
                Spec(setOf(BYTE), 1L..1L) { if (it.byte(0) in 0..1) null else "altitude reference not 0 or 1" },
            ExifTag(ExifIfd.GPS, 0x06) to Spec(setOf(RATIONAL), 1L..1L, nonNegativeRational(MAX_METRES)),
            ExifTag(ExifIfd.GPS, 0x07) to Spec(setOf(RATIONAL), 3L..3L, ::gpsTime),
            ExifTag(ExifIfd.GPS, 0x10) to Spec(setOf(ASCII), 2L..2L, refOf("TM")),
            ExifTag(ExifIfd.GPS, 0x11) to Spec(setOf(RATIONAL), 1L..1L, nonNegativeRational(360.0)),
            ExifTag(ExifIfd.GPS, 0x1D) to Spec(setOf(ASCII), 11L..11L, ::gpsDate),
            ExifTag(ExifIfd.GPS, 0x1F) to Spec(setOf(RATIONAL), 1L..1L, nonNegativeRational(MAX_METRES)),
        )

        val NAMES = mapOf(
            ORIENTATION to "Orientation", 0x010F to "Make", 0x0110 to "Model", 0x0131 to "Software",
            0x0132 to "DateTime",
            0x010E to "ImageDescription", 0x013B to "Artist", 0x8298 to "Copyright", 0x0201 to "JPEGInterchangeFormat",
            0x0202 to "JPEGInterchangeFormatLength", 0x9286 to "UserComment", MAKER_NOTE to "MakerNote",
            0x9003 to "DateTimeOriginal", 0x9004 to "DateTimeDigitized", 0x9011 to "OffsetTimeOriginal",
            0x9012 to "OffsetTimeDigitized", 0x9291 to "SubSecTimeOriginal",
        )
    }

    private fun tagName(tag: ExifTag): String {
        val name = if (tag.ifd == ExifIfd.GPS) GPS_NAMES[tag.id] else NAMES[tag.id]
        return if (name == null) tag.toString() else "$tag ($name)"
    }
}

private val GPS_NAMES = mapOf(
    0x00 to "GPSVersionID", 0x01 to "GPSLatitudeRef", 0x02 to "GPSLatitude", 0x03 to "GPSLongitudeRef",
    0x04 to "GPSLongitude",
    0x05 to "GPSAltitudeRef", 0x06 to "GPSAltitude", 0x07 to "GPSTimeStamp", 0x10 to "GPSImgDirectionRef",
    0x11 to "GPSImgDirection", 0x1B to "GPSProcessingMethod", 0x1C to "GPSAreaInformation", 0x1D to "GPSDateStamp",
    0x1F to "GPSHPositioningError",
)
