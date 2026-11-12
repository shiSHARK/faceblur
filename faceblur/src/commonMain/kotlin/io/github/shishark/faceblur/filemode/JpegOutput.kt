// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MetadataPolicy

/** One JPEG marker segment: bytes [start, end) of the file. For SOS the range includes the entropy-coded data. */
internal class Segment(val marker: Int, val start: Int, val end: Int)

/** Result of [JpegParser.parse]: the segments between SOI and EOI and the structural findings. */
internal class ParsedJpeg(val segments: List<Segment>, val findings: List<String>)

/**
 * A strict, bounded JPEG marker walker for the library's own encoder output. Every step advances the read position,
 * no value read from the file decides an allocation, and malformed input ends the walk with a finding instead of an
 * exception, so hostile or truncated data cannot crash, hang or exhaust memory.
 */
internal object JpegParser {
    const val SOI = 0xD8
    const val EOI = 0xD9
    const val SOS = 0xDA
    const val COM = 0xFE

    fun parse(b: ByteArray): ParsedJpeg {
        val segments = ArrayList<Segment>()
        val findings = ArrayList<String>()
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != SOI) return ParsedJpeg(segments, listOf(Finding.NOT_JPEG))
        var pos = 2
        while (true) {
            if (pos >= b.size - 1) {
                findings += Finding.MISSING_EOI
                break
            }
            if (u8(b, pos) != 0xFF) {
                findings += Finding.STRUCTURE
                break
            }
            val m = u8(b, pos + 1)
            if (m == 0xFF) {
                findings += Finding.STRUCTURE // fill bytes before a marker: never produced by the encoders
                break
            }
            if (m == EOI) {
                if (pos + 2 != b.size) findings += Finding.TRAILING_BYTES
                break
            }
            if (m == SOI || m == 0x01 || m in 0xD0..0xD7) {
                findings += Finding.UNEXPECTED_MARKER
                break
            }
            if (pos + 4 > b.size) {
                findings += Finding.TRUNCATED
                break
            }
            val length = u16(b, pos + 2)
            val headerEnd = pos + 2 + length
            if (length < 2 || headerEnd > b.size) {
                findings += Finding.TRUNCATED
                break
            }
            if (m != SOS) {
                segments += Segment(m, pos, headerEnd)
                pos = headerEnd
                continue
            }
            // Entropy-coded data: runs to the next marker other than a stuffed byte (FF 00) or a restart (FF D0..D7).
            var i = headerEnd
            while (i < b.size - 1) {
                if (u8(b, i) == 0xFF) {
                    val n = u8(b, i + 1)
                    if (n == 0x00 || n in 0xD0..0xD7) {
                        i += 2
                        continue
                    }
                    break
                }
                i++
            }
            if (i >= b.size - 1) {
                findings += Finding.MISSING_EOI
                break
            }
            segments += Segment(m, pos, i)
            pos = i
        }
        return ParsedJpeg(segments, findings)
    }

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

    /** True when segment [s] of [b] is an APPn whose payload starts with [id]. */
    fun hasId(b: ByteArray, s: Segment, id: ByteArray): Boolean {
        val p = s.start + 4
        if (p + id.size > s.end) return false
        for (k in id.indices) if (b[p + k] != id[k]) return false
        return true
    }
}

/**
 * Finding codes of the output check (ADR 0002 decision 9). They are public through
 * [FaceMaskException.OutputValidationFailed.findings]: never rename or remove one, or give it another meaning,
 * without a CHANGELOG "Breaking" entry; the test of the public set fails until the ADR is updated.
 */
internal object Finding {
    const val NOT_JPEG = "NOT_JPEG"
    const val STRUCTURE = "STRUCTURE"
    const val TRUNCATED = "TRUNCATED"
    const val MISSING_EOI = "MISSING_EOI"
    const val TRAILING_BYTES = "TRAILING_BYTES"
    const val UNEXPECTED_MARKER = "UNEXPECTED_MARKER"
    const val FORBIDDEN_SEGMENT = "FORBIDDEN_SEGMENT"
    const val SEGMENT_ORDER = "SEGMENT_ORDER"
    const val MISSING_EXIF = "MISSING_EXIF"
    const val DUPLICATE_EXIF = "DUPLICATE_EXIF"
    const val EXIF_TOO_LARGE = "EXIF_TOO_LARGE"
    const val EXIF_INVALID = "EXIF_INVALID"
    const val EXIF_TAG_NOT_ALLOWED = "EXIF_TAG_NOT_ALLOWED"
    const val ORIENTATION_NOT_1 = "ORIENTATION_NOT_1"
    const val FRAME = "FRAME"
    const val NO_SCAN = "NO_SCAN"

    /** Every code the check can report. */
    val ALL: Set<String> =
        setOf(
            NOT_JPEG, STRUCTURE, TRUNCATED, MISSING_EOI, TRAILING_BYTES, UNEXPECTED_MARKER, FORBIDDEN_SEGMENT,
            SEGMENT_ORDER, MISSING_EXIF, DUPLICATE_EXIF, EXIF_TOO_LARGE, EXIF_INVALID, EXIF_TAG_NOT_ALLOWED,
            ORIENTATION_NOT_1, FRAME, NO_SCAN,
        )
}

internal object JpegIds {
    /**
     * The library's fixed JFIF APP0 segment (ADR 0005 decision 1 step 7): marker FF E0, length 16, "JFIF\0",
     * version 1.01, density units 0 (aspect ratio only), density 1 x 1, thumbnail 0 x 0. Written from these
     * constants, never copied from the encoder, so no JFIF thumbnail can reach the output.
     */
    val FIXED_JFIF_APP0: ByteArray =
        byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, // marker, length 16
            0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
            0x01, 0x01, // version 1.01
            0x00, // density units: none (aspect ratio only)
            0x00, 0x01, 0x00, 0x01, // density 1 x 1
            0x00, 0x00, // thumbnail 0 x 0
        )

    /** Markers of the coding segments the encoders produce and the output may carry. */
    val CODING = setOf(0xC0, 0xC1, 0xC2, 0xC4, 0xDB, 0xDD, JpegParser.SOS)
}

/**
 * The last step of file mode before writing (ADR 0005 decision 1 step 7 and decision 6), as one call so that a
 * platform adapter cannot skip the check or check against another policy: keeps only the groups of [metadata] that
 * [policy] allows, writes the EXIF APP1, rebuilds [encoded] and validates the result.
 */
internal object JpegOutput {
    /**
     * Returns the bytes to write and the frame size they carry, which is [planned] (the output size of the call's
     * size plan): the encoder is a platform part, so the size of the image it encoded is checked like every other
     * size a platform part returns.
     *
     * @throws FaceMaskException.OutputValidationFailed when [encoded] cannot be parsed (parser codes such as
     *   `TRUNCATED` or `TRAILING_BYTES`) or when the rebuilt output fails [OutputValidator]; nothing may be written then.
     * @throws FaceMaskException.Internal when the frame of the valid output has another size than [planned]; nothing
     *   may be written then.
     */
    fun finish(encoded: ByteArray, metadata: PhotoMetadata?, policy: MetadataPolicy, planned: Size): FinishedJpeg {
        val output = JpegRebuilder.rebuild(encoded, ExifWriter.app1(metadata?.restrictTo(policy)))
        val frame = OutputValidator.requireValid(output, policy)
        if (frame != planned) throw FaceMaskException.Internal("the encoder returned another size than planned")
        return FinishedJpeg(output, frame)
    }
}

/** The checked output of [JpegOutput.finish]: the [bytes] to write and the [size] of their frame (SOF). */
internal class FinishedJpeg(val bytes: ByteArray, val size: Size)

/**
 * ADR 0005 decision 1, step 7: rebuilds the encoder's JPEG as SOI, the library's fixed JFIF APP0
 * ([JpegIds.FIXED_JFIF_APP0]), the library's EXIF APP1, then the encoder's non-APPn segments in their order, then
 * EOI. Every APPn the encoder wrote (its own APP0, any APP2 ICC profile, XMP, MPF and the rest) and every COM
 * segment is dropped. Each kept range is copied once into the result.
 */
internal object JpegRebuilder {
    /**
     * @throws FaceMaskException.OutputValidationFailed with the parser's codes (for example `TRUNCATED`,
     *   `MISSING_EOI`, `TRAILING_BYTES`) when [encoded] cannot be parsed; nothing is written then.
     */
    fun rebuild(encoded: ByteArray, exifApp1: ByteArray): ByteArray {
        val parsed = JpegParser.parse(encoded)
        if (parsed.findings.isNotEmpty()) throw FaceMaskException.OutputValidationFailed(parsed.findings)
        val kept = parsed.segments.filter { it.marker !in 0xE0..0xEF && it.marker != JpegParser.COM }
        val app0 = JpegIds.FIXED_JFIF_APP0
        val out = ByteArray(2 + app0.size + exifApp1.size + kept.sumOf { it.end - it.start } + 2)
        out[0] = 0xFF.toByte()
        out[1] = JpegParser.SOI.toByte()
        var p = 2
        app0.copyInto(out, p)
        p += app0.size
        exifApp1.copyInto(out, p)
        p += exifApp1.size
        for (s in kept) {
            encoded.copyInto(out, p, s.start, s.end)
            p += s.end - s.start
        }
        out[p] = 0xFF.toByte()
        out[p + 1] = JpegParser.EOI.toByte()
        return out
    }
}

/**
 * ADR 0005 decision 6: the runtime check of every output before it is written. Fails closed: any finding throws
 * [FaceMaskException.OutputValidationFailed] with the finding codes only.
 */
internal object OutputValidator {
    /**
     * Throws unless [bytes] passes [findings] for [policy]; returns the width and height of the frame (the one SOF0-2
     * segment that a valid output has).
     */
    fun requireValid(bytes: ByteArray, policy: MetadataPolicy): Size {
        val checked = inspect(bytes, policy) { ExifWriter.app1(it) }
        if (checked.findings.isNotEmpty()) throw FaceMaskException.OutputValidationFailed(checked.findings)
        return checked.frame ?: throw FaceMaskException.OutputValidationFailed(listOf(Finding.FRAME))
    }

    /**
     * Findings for [bytes] (empty = valid), each code once in the order found. The accepted form: SOI at 0; the
     * fixed JFIF APP0, byte for byte; the EXIF APP1 directly after it (at most 1,024 bytes, in the exact form
     * [ExifValidator] accepts for [policy]); then only DQT, DHT, SOF0-2 (exactly one, before the first scan), DRI and
     * SOS with their data, each coding segment exactly as long as its tables; one EOI as the last two bytes. Any
     * other APPn (also a second or different APP0 and any APP2, ICC included) or COM is `FORBIDDEN_SEGMENT`; a
     * missing or misplaced APP0 or APP1 is `SEGMENT_ORDER`.
     *
     * [exifWriter] is the reference for the EXIF byte comparison; production always uses [ExifWriter]. Tests pass a
     * faulty writer to show that the writer-independent checks still refuse its output.
     */
    fun findings(
        bytes: ByteArray,
        policy: MetadataPolicy,
        exifWriter: (PhotoMetadata) -> ByteArray = { ExifWriter.app1(it) },
    ): List<String> = inspect(bytes, policy, exifWriter).findings

    /** The findings of [findings] and the size of the last frame (SOF0-2) seen; one frame when nothing was found. */
    private class Inspection(val findings: List<String>, val frame: Size?)

    private fun inspect(
        bytes: ByteArray,
        policy: MetadataPolicy,
        exifWriter: (PhotoMetadata) -> ByteArray,
    ): Inspection {
        val parsed = JpegParser.parse(bytes)
        val f = LinkedHashSet(parsed.findings)
        if (Finding.NOT_JPEG in f) return Inspection(f.toList(), null)
        val segs = parsed.segments
        var i = 0
        if (segs.isNotEmpty() && segs[0].marker == 0xE0) {
            if (!isFixedJfif(bytes, segs[0])) f += Finding.FORBIDDEN_SEGMENT
            i = 1
        } else {
            f += Finding.SEGMENT_ORDER // the fixed APP0 must follow SOI
        }
        var exifSeen = false
        if (i < segs.size && isExif(bytes, segs[i])) {
            val s = segs[i]
            if (s.end - s.start > Exif.MAX_APP1_BYTES) {
                f += Finding.EXIF_TOO_LARGE // rejected without reading its contents
            } else {
                f += ExifValidator.findings(bytes, s.start, s.end, policy, exifWriter)
            }
            exifSeen = true
            i++
        } else if (segs.drop(i).none { isExif(bytes, it) }) {
            f += Finding.MISSING_EXIF
        }
        var frames = 0
        var frame: Size? = null
        var scans = 0
        for (k in i until segs.size) {
            val s = segs[k]
            val m = s.marker
            when {
                isExif(bytes, s) -> {
                    f += if (exifSeen) Finding.DUPLICATE_EXIF else Finding.SEGMENT_ORDER
                    exifSeen = true
                }

                m in 0xE0..0xEF || m !in JpegIds.CODING -> f += Finding.FORBIDDEN_SEGMENT

                !CodingSegments.valid(bytes, s) -> f += Finding.STRUCTURE

                m in 0xC0..0xC2 -> {
                    frames++
                    // SOF payload: precision, height (2 bytes), width (2 bytes); checked by CodingSegments.valid.
                    frame = Size(JpegParser.u16(bytes, s.start + 7), JpegParser.u16(bytes, s.start + 5))
                    if (scans > 0) f += Finding.SEGMENT_ORDER
                }

                m == JpegParser.SOS -> scans++
            }
        }
        if (frames != 1) f += Finding.FRAME
        if (scans == 0) f += Finding.NO_SCAN
        return Inspection(f.toList(), frame)
    }

    private fun isExif(b: ByteArray, s: Segment) = s.marker == 0xE1 && JpegParser.hasId(b, s, Exif.HEADER)

    private fun isFixedJfif(b: ByteArray, s: Segment): Boolean {
        val fixed = JpegIds.FIXED_JFIF_APP0
        if (s.end - s.start != fixed.size) return false
        for (k in fixed.indices) if (b[s.start + k] != fixed[k]) return false
        return true
    }
}

/**
 * Length checks of the coding segments: each segment must consist of exactly its tables or fields, so no segment can
 * carry extra bytes. Values that only change how the image decodes are left to the decoder.
 */
internal object CodingSegments {
    fun valid(b: ByteArray, s: Segment): Boolean {
        val p = s.start + 4 // first payload byte
        val end = s.start + 2 + JpegParser.u16(b, s.start + 2) // end of the header (SOS data excluded)
        return when (s.marker) {
            0xDB -> tables(b, p, end) { q -> 1 + 64 * (1 + (JpegParser.u8(b, q) ushr 4)) }
            0xC4 -> tables(b, p, end) { q -> huffmanTableSize(b, q, end) }
            0xDD -> end - p == 2
            JpegParser.SOS -> end - p >= 1 && JpegParser.u8(b, p) in 1..4 && end - p == 4 + 2 * JpegParser.u8(b, p)
            else -> frame(b, p, end) // SOF0-2
        }
    }

    /**
     * DQT and DHT: one or more tables that fill the payload exactly. [size] returns the size of the table at a
     * position, or 0 when its header is invalid.
     */
    private inline fun tables(b: ByteArray, start: Int, end: Int, size: (Int) -> Int): Boolean {
        if (start >= end) return false
        var q = start
        while (q < end) {
            val id = JpegParser.u8(b, q)
            if ((id and 0x0F) > 3 || (id ushr 4) > 1) return false // class or precision 0..1, table id 0..3
            val n = size(q)
            if (n <= 0) return false
            q += n
        }
        return q == end
    }

    /** One Huffman table: class/id byte, 16 counts, then that many symbols (at most 256); 0 when truncated. */
    private fun huffmanTableSize(b: ByteArray, q: Int, end: Int): Int {
        if (q + 17 > end) return 0
        var symbols = 0
        for (k in 1..16) symbols += JpegParser.u8(b, q + k)
        return if (symbols > 256) 0 else 17 + symbols
    }

    /** SOF: 8-bit precision, height and width above 0, 1 to 4 components of 3 bytes each, nothing else. */
    private fun frame(b: ByteArray, p: Int, end: Int): Boolean {
        if (end - p < 6) return false
        val components = JpegParser.u8(b, p + 5)
        return JpegParser.u8(b, p) == 8 && JpegParser.u16(b, p + 1) > 0 && JpegParser.u16(b, p + 3) > 0 &&
            components in 1..4 && end - p == 6 + 3 * components
    }
}

/**
 * Checks the library's EXIF APP1 (ADR 0005 decisions 3 and 6). Accepts exactly the bytes that [ExifWriter] writes
 * for the values the segment holds, so no gap, padding byte, unused inline byte, unsorted or repeated entry, IFD1
 * link or extra byte can carry data:
 * 1. bounded reading: big-endian TIFF with IFD0 at 8; IFD0, the Exif and the GPS IFD only (each once, no recursion);
 *    every tag on the closed list of its IFD with the writer's type and count, every value inside the segment;
 * 2. checks that do not depend on the writer: every next-IFD offset is 0 (no IFD1, so no thumbnail), and the
 *    segment is exactly as long as the TIFF header, the IFDs read and their out-of-line values (each padded to an
 *    even length) add up to, so a faulty writer that links an IFD1 or appends bytes is still refused;
 *    Orientation must be 1; Make/Model, the date/time group and the GPS group only when [MetadataPolicy] allows them;
 * 3. every value read is re-checked with [PhotoMetadata.of] (printable ASCII, date, offset and GPS patterns and
 *    ranges); the values that pass are written again with [ExifWriter], and the segment must equal that result byte
 *    for byte. A value that fails its check is missing from the re-written segment, so it fails here too.
 * Reading never throws and never allocates by a length read from the segment beyond the segment's own size.
 */
internal object ExifValidator {
    private class TagRule(val type: Int, val minCount: Int, val maxCount: Int)

    /** A value read from the segment and the position of its first byte (relative to the TIFF start). */
    private class Value(val at: Int, val data: Any)

    private val IFD0: Map<Int, TagRule> =
        mapOf(
            Exif.MAKE to TagRule(Exif.ASCII, 2, 65),
            Exif.MODEL to TagRule(Exif.ASCII, 2, 65),
            Exif.ORIENTATION to TagRule(Exif.SHORT, 1, 1),
            Exif.EXIF_IFD to TagRule(Exif.LONG, 1, 1),
            Exif.GPS_IFD to TagRule(Exif.LONG, 1, 1),
        )
    private val EXIF: Map<Int, TagRule> =
        mapOf(
            Exif.DATE_TIME_ORIGINAL to TagRule(Exif.ASCII, 20, 20),
            Exif.DATE_TIME_DIGITIZED to TagRule(Exif.ASCII, 20, 20),
            Exif.OFFSET_TIME_ORIGINAL to TagRule(Exif.ASCII, 7, 7),
            Exif.OFFSET_TIME_DIGITIZED to TagRule(Exif.ASCII, 7, 7),
            Exif.SUB_SEC_TIME_ORIGINAL to TagRule(Exif.ASCII, 2, 10),
        )
    private val GPS: Map<Int, TagRule> =
        mapOf(
            Exif.GPS_VERSION_ID to TagRule(Exif.BYTE, 4, 4),
            Exif.GPS_LATITUDE_REF to TagRule(Exif.ASCII, 2, 2),
            Exif.GPS_LATITUDE to TagRule(Exif.RATIONAL, 3, 3),
            Exif.GPS_LONGITUDE_REF to TagRule(Exif.ASCII, 2, 2),
            Exif.GPS_LONGITUDE to TagRule(Exif.RATIONAL, 3, 3),
            Exif.GPS_ALTITUDE_REF to TagRule(Exif.BYTE, 1, 1),
            Exif.GPS_ALTITUDE to TagRule(Exif.RATIONAL, 1, 1),
            Exif.GPS_TIME_STAMP to TagRule(Exif.RATIONAL, 3, 3),
            Exif.GPS_IMG_DIRECTION_REF to TagRule(Exif.ASCII, 2, 2),
            Exif.GPS_IMG_DIRECTION to TagRule(Exif.RATIONAL, 1, 1),
            Exif.GPS_DATE_STAMP to TagRule(Exif.ASCII, 11, 11),
            Exif.GPS_H_POSITIONING_ERROR to TagRule(Exif.RATIONAL, 1, 1),
        )

    private fun typeSize(type: Int) = when (type) {
        Exif.SHORT -> 2
        Exif.LONG -> 4
        Exif.RATIONAL -> 8
        else -> 1 // BYTE, ASCII
    }

    /** [segStart] and [segEnd] bound the whole APP1 segment (marker included); it holds "Exif\0\0" and the TIFF. */
    fun findings(
        b: ByteArray,
        segStart: Int,
        segEnd: Int,
        policy: MetadataPolicy,
        exifWriter: (PhotoMetadata) -> ByteArray,
    ): List<String> {
        val f = LinkedHashSet<String>()
        val allow = policy as? MetadataPolicy.KeepAllowlist
        val start = segStart + 4 + Exif.HEADER.size
        val len = segEnd - start
        if (len < 8 || JpegParser.u16(b, start) != 0x4D4D || JpegParser.u16(b, start + 2) != 0x2A ||
            JpegParser.u32(b, start + 4) != 8L
        ) {
            return listOf(Finding.EXIF_INVALID)
        }
        val expected = intArrayOf(8) // bytes the parsed structure accounts for, starting with the TIFF header
        val ifd0 = readIfd(b, start, len, 8L, IFD0, f, expected) ?: return f.toList()
        val orientation = ifd0[Exif.ORIENTATION]
        if (orientation?.data != 1) f += Finding.ORIENTATION_NOT_1
        if ((Exif.MAKE in ifd0 || Exif.MODEL in ifd0) && allow?.camera != true) f += Finding.EXIF_TAG_NOT_ALLOWED
        val exif = subIfd(b, start, len, ifd0[Exif.EXIF_IFD], EXIF, allow?.dateTime == true, f, expected)
        val gps = subIfd(b, start, len, ifd0[Exif.GPS_IFD], GPS, allow?.gps == true, f, expected)
        // Writer-independent: nothing in the segment beyond what the structure read accounts for.
        if (expected[0] != len) f += Finding.EXIF_INVALID

        // Re-check every value and require the writer's exact bytes for the values that pass.
        val metadata = metadataOf(ifd0, exif, gps)
        val actual = b.copyOfRange(segStart, segEnd)
        if (orientation != null && orientation.data != 1) {
            // Already reported as ORIENTATION_NOT_1; compare the rest of the segment as if it were 1.
            ExifWriter.u16(1).copyInto(actual, start - segStart + orientation.at)
        }
        if (!actual.contentEquals(exifWriter(metadata))) f += Finding.EXIF_INVALID
        return f.toList()
    }

    private fun subIfd(
        b: ByteArray,
        start: Int,
        len: Int,
        pointer: Value?,
        rules: Map<Int, TagRule>,
        allowed: Boolean,
        f: MutableSet<String>,
        expected: IntArray,
    ): Map<Int, Value> {
        if (pointer == null) return emptyMap()
        if (!allowed) f += Finding.EXIF_TAG_NOT_ALLOWED
        val offset = pointer.data as? Long ?: return emptyMap()
        return readIfd(b, start, len, offset, rules, f, expected).orEmpty()
    }

    /**
     * Reads one IFD at [offset] (relative to the TIFF start [start]): tag to value, or null when the IFD itself does
     * not fit in the segment. Unknown tags give `EXIF_TAG_NOT_ALLOWED`; a wrong type or count, or a value outside the
     * segment, gives `EXIF_INVALID`; such entries are not read. A next-IFD offset other than 0 (a linked IFD1, where
     * a thumbnail would live) gives `EXIF_INVALID` and is never followed. Adds the size of the IFD and of each
     * out-of-line value read (padded to an even length) to [expected].
     */
    private fun readIfd(
        b: ByteArray,
        start: Int,
        len: Int,
        offset: Long,
        rules: Map<Int, TagRule>,
        f: MutableSet<String>,
        expected: IntArray,
    ): Map<Int, Value>? {
        if (offset + 2 > len) {
            f += Finding.EXIF_INVALID
            return null
        }
        val o = offset.toInt()
        val count = JpegParser.u16(b, start + o)
        if (o + 2 + 12 * count + 4 > len) {
            f += Finding.EXIF_INVALID
            return null
        }
        val out = HashMap<Int, Value>()
        expected[0] += 2 + 12 * count + 4
        if (JpegParser.u32(b, start + o + 2 + 12 * count) != 0L) f += Finding.EXIF_INVALID // no IFD1, no thumbnail
        for (k in 0 until count) {
            val e = start + o + 2 + 12 * k
            val tag = JpegParser.u16(b, e)
            val type = JpegParser.u16(b, e + 2)
            val n = JpegParser.u32(b, e + 4)
            val rule = rules[tag]
            if (rule == null) {
                f += Finding.EXIF_TAG_NOT_ALLOWED
                continue
            }
            if (type != rule.type || n < rule.minCount || n > rule.maxCount) {
                f += Finding.EXIF_INVALID
                continue
            }
            val size = n.toInt() * typeSize(type)
            val at: Long = if (size <= 4) (e + 8 - start).toLong() else JpegParser.u32(b, e + 8)
            if (at + size > len) {
                f += Finding.EXIF_INVALID
                continue
            }
            if (size > 4) expected[0] += (size + 1) and 1.inv()
            out[tag] = Value(at.toInt(), decode(b, start + at.toInt(), type, n.toInt()))
        }
        return out
    }

    /**
     * ASCII: the characters before the last byte, one char per byte (the writer's NUL and every other byte are
     * checked by the byte comparison); SHORT and BYTE with count 1: the number; LONG: the unsigned number;
     * RATIONAL: the list of rationals; BYTE with count 4 (GPSVersionID): not read (the byte comparison checks it).
     */
    private fun decode(b: ByteArray, at: Int, type: Int, n: Int): Any = when (type) {
        Exif.ASCII -> CharArray(n - 1) { (JpegParser.u8(b, at + it)).toChar() }.concatToString()
        Exif.SHORT -> JpegParser.u16(b, at)
        Exif.LONG -> JpegParser.u32(b, at)
        Exif.RATIONAL -> List(n) { Rational(JpegParser.u32(b, at + 8 * it), JpegParser.u32(b, at + 8 * it + 4)) }
        else -> if (n == 1) JpegParser.u8(b, at) else Unit
    }

    private fun metadataOf(ifd0: Map<Int, Value>, exif: Map<Int, Value>, gps: Map<Int, Value>): PhotoMetadata {
        fun text(m: Map<Int, Value>, tag: Int) = m[tag]?.data as? String

        fun ref(tag: Int) = text(gps, tag)?.singleOrNull()

        @Suppress("UNCHECKED_CAST")
        fun rationals(tag: Int) = gps[tag]?.data as? List<Rational>
        return PhotoMetadata.of(
            make = text(ifd0, Exif.MAKE),
            model = text(ifd0, Exif.MODEL),
            dateTimeOriginal = text(exif, Exif.DATE_TIME_ORIGINAL),
            dateTimeDigitized = text(exif, Exif.DATE_TIME_DIGITIZED),
            offsetTimeOriginal = text(exif, Exif.OFFSET_TIME_ORIGINAL),
            offsetTimeDigitized = text(exif, Exif.OFFSET_TIME_DIGITIZED),
            subSecTimeOriginal = text(exif, Exif.SUB_SEC_TIME_ORIGINAL),
            latitudeRef = ref(Exif.GPS_LATITUDE_REF),
            latitude = rationals(Exif.GPS_LATITUDE),
            longitudeRef = ref(Exif.GPS_LONGITUDE_REF),
            longitude = rationals(Exif.GPS_LONGITUDE),
            altitudeRef = gps[Exif.GPS_ALTITUDE_REF]?.data as? Int,
            altitude = rationals(Exif.GPS_ALTITUDE)?.singleOrNull(),
            gpsTimeStamp = rationals(Exif.GPS_TIME_STAMP),
            gpsDateStamp = text(gps, Exif.GPS_DATE_STAMP),
            horizontalError = rationals(Exif.GPS_H_POSITIONING_ERROR)?.singleOrNull(),
            imgDirectionRef = ref(Exif.GPS_IMG_DIRECTION_REF),
            imgDirection = rationals(Exif.GPS_IMG_DIRECTION)?.singleOrNull(),
        )
    }
}
