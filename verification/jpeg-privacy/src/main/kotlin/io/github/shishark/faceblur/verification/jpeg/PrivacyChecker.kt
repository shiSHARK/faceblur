// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.nio.file.Files
import java.nio.file.Path

/**
 * Independent privacy checker for JPEG output files (PRIV-007, PRIV-021, ST-042). It walks the JPEG markers
 * itself and shares no code with the library.
 *
 * It fails closed: a file it cannot parse is reported as unparseable and does not pass. Its work and its memory
 * are bounded: at most [MAX_MARKERS] markers and [MAX_FINDINGS] findings are processed per file (more is reported
 * as unparseable), no allocation depends on a count read from the file, and files above
 * `CheckOptions.maxFileBytes` are not read.
 */
public object PrivacyChecker {
    /** Most markers (segments and standalone markers) processed per file. */
    public const val MAX_MARKERS: Int = 4096

    /** Most findings recorded per file; the last one is the 'unparseable' failure that reports the overflow. */
    public const val MAX_FINDINGS: Int = 256

    /** Checks [data] against [options]. Does not throw for any input that fits in memory. */
    public fun check(data: ByteArray, options: CheckOptions = CheckOptions()): PrivacyReport =
        Inspection(data, options).run()

    /**
     * Reads [path] (size-capped) and checks it. An unreadable file is an I/O error, not a verdict: the
     * [java.io.IOException] (whose message contains the path) is the caller's to handle.
     */
    public fun checkFile(path: Path, options: CheckOptions = CheckOptions()): PrivacyReport {
        val size = Files.size(path)
        if (size > options.maxFileBytes) {
            return Inspection(ByteArray(0), options).tooLarge(size)
        }
        return check(Files.readAllBytes(path), options)
    }

    /**
     * The helper every test uses on every output (PRIV-021): throws an [AssertionError] listing the findings
     * (names, offsets and counts only) unless the output passes. Returns the report for further assertions.
     *
     * Read before use. The defaults check shape and every ICC profile fails unless its hash is in
     * `allowedIccSha256`. Three checks are silent unless the test supplies the input: `canaries` (planted
     * strings; a canary is also searched as UTF-16), `sourceIccProfile` (a test whose input carries an ICC
     * profile must pass it; use a distinctive profile, a common sRGB profile can equal the encoder's) and, for
     * the allowlist policy, `KeepAllowlist.withRequiredFrom(input)`. Canary search is linear in the file size
     * times the canary length.
     */
    public fun assertPrivateOutput(data: ByteArray, options: CheckOptions = CheckOptions()): PrivacyReport {
        val report = check(data, options)
        if (!report.passed) throw AssertionError("output is not private: " + report.summary())
        return report
    }

    /**
     * Returns the ICC profile of [data] (the bytes after `ICC_PROFILE\0` and the two sequence bytes, chunks in
     * order), or null if the file has none. Meant for input fixtures. Throws [IllegalArgumentException] if the
     * chunks are incomplete or inconsistent, because a half-read source profile would turn the copy check into
     * a no-op.
     */
    public fun extractIccProfile(data: ByteArray): ByteArray? {
        val report = check(data, CheckOptions(requireExif = false, enforceSegmentOrder = false, measureIccOnly = true))
        val icc = report.segments.filter { it.kind == SegmentKind.ICC }
        if (icc.isEmpty()) return null
        val chunks = icc.map { seg ->
            val payload = seg.offset + 4
            val end = seg.offset + seg.length
            require(payload + 14 <= end) { "ICC chunk header truncated" }
            Triple(data[payload + 12].toInt() and 0xFF, data[payload + 13].toInt() and 0xFF, payload to end)
        }.sortedBy { it.first }
        val count = chunks.first().second
        require(
            chunks.size == count && chunks.map {
                it.first
            } == (1..count).toList() && chunks.all { it.second == count },
        ) {
            "ICC chunks are incomplete or inconsistent"
        }
        val out = java.io.ByteArrayOutputStream()
        chunks.forEach { out.write(data, it.third.first + 14, it.third.second - it.third.first - 14) }
        return out.toByteArray()
    }

    /**
     * PRIV-001 / FR-FM-014: the ICC segment must not depend on the input. At least two [reports] (outputs for
     * unrelated inputs) are required; all must carry the same profile hash, or all carry none.
     */
    public fun assertIccIdenticalAcrossOutputs(reports: List<PrivacyReport>) {
        require(reports.size >= 2) { "comparing outputs needs at least two reports" }
        val hashes = reports.map { it.iccSha256 }.toSet()
        if (hashes.size > 1) {
            throw AssertionError("ICC profile differs between outputs of unrelated inputs (${hashes.size} variants)")
        }
    }
}

private const val FF = 0xFF
private const val MAX_CANARY_REPORT = 8
private const val JPEG_SIGNATURE = "\u00FF\u00D8\u00FF"

/** One walk over one file. */
private class Inspection(private val data: ByteArray, private val options: CheckOptions) {
    private val findings = mutableListOf<Finding>()
    private val segments = mutableListOf<SegmentInfo>()
    private val exifTags = mutableSetOf<ExifTag>()
    private var orientation: Int? = null
    private var exifBytes: Int? = null
    private var iccSha: String? = null
    private var iccDescription: String? = null
    private var width: Int? = null
    private var height: Int? = null
    private var frames = 0
    private var scans = 0
    private var jfifCount = 0
    private var exifCount = 0
    private var iccCount = 0
    private var rank = 0
    private var markers = 0
    private var reachedEoi = false
    private var overflowed = false
    private val coding = CodingValidator(data) { code, message, offset -> fail(code, message, offset) }

    fun tooLarge(size: Long): PrivacyReport {
        fail(FindingCode.FILE_TOO_LARGE, "file of $size bytes exceeds the checker limit", null)
        return report()
    }

    fun run(): PrivacyReport {
        try {
            walk()
        } catch (e: Fatal) {
            if (!overflowed) fail(e.code, e.message ?: "unparseable", e.offset)
        } catch (e: RuntimeException) {
            // Fail closed: an internal error is a failure, never a pass. Tests assert it never happens.
            if (!overflowed) fail(FindingCode.CHECKER_INTERNAL_ERROR, "checker error ${e::class.simpleName}", null)
        }
        try {
            searchCanaries()
            // Last: only a file that passed everything else is decoded (a failing file needs no second opinion).
            if (options.decodeCheck && reachedEoi && findings.none { it.severity == Severity.FAIL }) decode()
        } catch (e: Fatal) {
            check(overflowed) { "only the findings cap can stop the late checks" }
        }
        return report()
    }

    private fun report() = PrivacyReport(
        findings.toList(), segments.toList(), data.size, width, height, exifTags.toSet(),
        orientation, exifBytes, iccSha, iccDescription,
    )

    private fun fail(code: FindingCode, message: String, offset: Int?) {
        add(Finding(code, Severity.FAIL, message, offset))
    }

    /** Records a finding; the (MAX_FINDINGS + 1)th ends the walk as unparseable so memory stays bounded. */
    private fun add(finding: Finding) {
        if (overflowed) throw Fatal(FindingCode.UNPARSEABLE, "too many findings", null)
        if (findings.size >= PrivacyChecker.MAX_FINDINGS) {
            overflowed = true
            findings.add(
                Finding(
                    FindingCode.UNPARSEABLE,
                    Severity.FAIL,
                    "more than ${PrivacyChecker.MAX_FINDINGS} findings",
                    null,
                ),
            )
            throw Fatal(FindingCode.UNPARSEABLE, "too many findings", null)
        }
        findings.add(finding)
    }

    private fun u8(pos: Int): Int = data[pos].toInt() and 0xFF

    private fun u16(pos: Int): Int = (u8(pos) shl 8) or u8(pos + 1)

    private fun walk() {
        if (data.size < 2 || u8(0) != FF || u8(1) != 0xD8) {
            throw Fatal(FindingCode.NOT_JPEG, "file does not start with a JPEG SOI marker", 0)
        }
        segments.add(SegmentInfo(0xD8, 0, 2, SegmentKind.SOI))
        var pos = 2
        var eoiEnd = -1
        while (true) {
            if (pos >= data.size) {
                fail(FindingCode.NO_EOI, "file ends without an EOI marker", pos)
                break
            }
            if (u8(pos) != FF) throw Fatal(FindingCode.UNPARSEABLE, "expected a marker, found other data", pos)
            while (pos < data.size && u8(pos) == FF) pos++
            if (pos >= data.size) {
                fail(FindingCode.NO_EOI, "file ends inside a marker", pos)
                break
            }
            val marker = u8(pos)
            val markerPos = pos - 1
            pos++
            if (++markers > PrivacyChecker.MAX_MARKERS) {
                throw Fatal(FindingCode.UNPARSEABLE, "more than ${PrivacyChecker.MAX_MARKERS} markers", markerPos)
            }
            when {
                marker == 0x00 -> throw Fatal(FindingCode.UNPARSEABLE, "stuffed zero outside entropy data", markerPos)

                marker == 0xD8 -> {
                    fail(FindingCode.SECOND_SOI, "a second SOI marker (second image)", markerPos)
                    segments.add(SegmentInfo(marker, markerPos, 2, SegmentKind.SOI))
                }

                marker == 0xD9 -> {
                    segments.add(SegmentInfo(marker, markerPos, 2, SegmentKind.EOI))
                    eoiEnd = pos
                    break
                }

                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    fail(FindingCode.UNEXPECTED_MARKER, "marker ${hex(marker)} outside entropy data", markerPos)
                }

                else -> {
                    if (pos + 2 > data.size) throw Fatal(FindingCode.UNPARSEABLE, "truncated segment length", markerPos)
                    val len = u16(pos)
                    if (len < 2) throw Fatal(FindingCode.UNPARSEABLE, "segment length below 2", markerPos)
                    if (pos + len >
                        data.size
                    ) {
                        throw Fatal(FindingCode.UNPARSEABLE, "segment runs past the end of the file", markerPos)
                    }
                    segment(marker, markerPos, pos + 2, len - 2)
                    pos += len
                    if (marker == 0xDA) pos = skipScan(pos)
                }
            }
        }
        structure()
        coding.finish()
        if (eoiEnd in 0 until data.size) trailing(eoiEnd)
        reachedEoi = eoiEnd >= 0
        if (options.requireExif && exifCount == 0) {
            fail(FindingCode.EXIF_MISSING, "no EXIF segment (Orientation = 1 must be written)", null)
        }
    }

    /** Returns the offset of the next marker after the entropy-coded data that starts at [from]. */
    private fun skipScan(from: Int): Int {
        var p = from
        while (p < data.size) {
            if (u8(p) != FF) {
                p++
                continue
            }
            if (p + 1 >= data.size) return data.size
            val next = u8(p + 1)
            when {
                next == 0x00 || next in 0xD0..0xD7 -> p += 2
                next == FF -> p++
                else -> return p
            }
        }
        return p
    }

    private fun structure() {
        if (frames == 0) fail(FindingCode.NO_FRAME, "no start-of-frame segment", null)
        if (scans == 0) fail(FindingCode.NO_SCAN, "no start-of-scan segment", null)
        if (frames > 1) fail(FindingCode.MULTIPLE_FRAMES, "$frames start-of-frame segments", null)
    }

    private fun decode() {
        val pixels = (width ?: 0).toLong() * (height ?: 0)
        if (pixels > options.maxSubsampledDecodePixels) {
            fail(
                FindingCode.DECODE_SKIPPED,
                "$pixels pixels are above the decode check limit: the image was not decoded",
                null,
            )
            return
        }
        val subsampling = if (pixels > options.maxDecodePixels) {
            Math.ceil(Math.sqrt(pixels.toDouble() / options.maxDecodePixels)).toInt()
        } else {
            1
        }
        val result = JdkDecodeCheck.run(data, subsampling)
        if (result.failure != null) {
            fail(FindingCode.DECODER_REJECTED, "the JDK decoder rejects the image (${result.failure})", null)
        } else if (result.warnings.isNotEmpty()) {
            fail(
                FindingCode.DECODER_WARNING,
                "the JDK decoder warns ${result.warnings.size} time(s), first: ${result.warnings.first().take(80)}",
                null,
            )
        }
    }

    private fun trailing(eoiEnd: Int) {
        val count = data.size - eoiEnd
        fail(FindingCode.TRAILING_BYTES, "$count bytes after the final EOI", eoiEnd)
        if (count >= 3 && u8(eoiEnd) == FF && u8(eoiEnd + 1) == 0xD8 && u8(eoiEnd + 2) == FF) {
            fail(FindingCode.SECOND_IMAGE, "a second JPEG image starts after the EOI", eoiEnd)
        }
    }

    private fun startsWith(at: Int, avail: Int, prefix: String): Boolean {
        if (avail < prefix.length) return false
        for (i in prefix.indices) if (u8(at + i) != prefix[i].code) return false
        return true
    }

    private fun contains(at: Int, avail: Int, needle: String): Boolean {
        val n = needle.length
        outer@ for (i in 0..avail - n) {
            for (j in 0 until n) if (u8(at + i + j) != needle[j].code) continue@outer
            return true
        }
        return false
    }

    private fun segment(marker: Int, markerPos: Int, payload: Int, size: Int) {
        val kind = classify(marker, payload, size)
        segments.add(SegmentInfo(marker, markerPos, size + 4, kind))
        when (kind) {
            SegmentKind.SOF -> sof(markerPos, payload, size)

            SegmentKind.SOS -> {
                coding.sos(markerPos, payload, size)
                scans++
                if (frames == 0) fail(FindingCode.NO_FRAME, "scan before any frame header", markerPos)
            }

            SegmentKind.DRI -> coding.dri(markerPos, size)

            SegmentKind.DQT -> coding.dqt(markerPos, payload, size)

            SegmentKind.DHT -> coding.dht(markerPos, payload, size)

            SegmentKind.JFIF -> jfif(markerPos, payload, size)

            SegmentKind.JFXX -> fail(
                FindingCode.JFXX_THUMBNAIL,
                "JFIF extension segment (can carry a thumbnail)",
                markerPos,
            )

            SegmentKind.EXIF -> exif(markerPos, payload, size)

            SegmentKind.XMP -> fail(FindingCode.XMP_PRESENT, "XMP packet in APP1", markerPos)

            SegmentKind.ICC -> icc(markerPos, payload, size)

            SegmentKind.MPF -> fail(
                FindingCode.MPF_PRESENT,
                "multi-picture (MPF) segment: secondary image or gain map",
                markerPos,
            )

            SegmentKind.IPTC -> fail(FindingCode.IPTC_APP13, "APP13 (IPTC / Photoshop resources)", markerPos)

            SegmentKind.COM -> fail(FindingCode.COM_PRESENT, "comment segment", markerPos)

            SegmentKind.OTHER_APP -> fail(
                FindingCode.APP_NOT_ALLOWED,
                "segment ${hex(marker)} is not in the allowed list",
                markerPos,
            )

            SegmentKind.OTHER -> fail(
                FindingCode.UNEXPECTED_MARKER,
                "marker ${hex(marker)} is not in the allowed list",
                markerPos,
            )

            else -> Unit
        }
        if (kind !in CODING_KINDS && contains(payload, size, JPEG_SIGNATURE)) {
            fail(
                FindingCode.EMBEDDED_JPEG_IN_SEGMENT,
                "an embedded JPEG signature (FF D8 FF) inside ${kind.name}",
                markerPos,
            )
        }
        order(kind, markerPos)
    }

    private fun classify(marker: Int, payload: Int, size: Int): SegmentKind = when {
        marker == 0xDB -> SegmentKind.DQT

        marker == 0xC4 -> SegmentKind.DHT

        marker == 0xDD -> SegmentKind.DRI

        marker == 0xDA -> SegmentKind.SOS

        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> SegmentKind.SOF

        marker == 0xFE -> SegmentKind.COM

        marker == 0xE0 -> when {
            startsWith(payload, size, "JFIF\u0000") -> SegmentKind.JFIF
            startsWith(payload, size, "JFXX\u0000") -> SegmentKind.JFXX
            else -> SegmentKind.OTHER_APP
        }

        marker == 0xE1 -> when {
            startsWith(payload, size, "Exif\u0000\u0000") -> SegmentKind.EXIF

            startsWith(payload, size, "http://ns.adobe.com/xap/1.0/") ||
                startsWith(payload, size, "http://ns.adobe.com/xmp/extension/") ||
                contains(payload, size, "<x:xmpmeta") || contains(payload, size, "<?xpacket") -> SegmentKind.XMP

            else -> SegmentKind.OTHER_APP
        }

        marker == 0xE2 -> when {
            startsWith(payload, size, "ICC_PROFILE\u0000") -> SegmentKind.ICC
            startsWith(payload, size, "MPF\u0000") -> SegmentKind.MPF
            else -> SegmentKind.OTHER_APP
        }

        marker == 0xED -> SegmentKind.IPTC

        marker in 0xE0..0xEF -> SegmentKind.OTHER_APP

        else -> SegmentKind.OTHER
    }

    private fun order(kind: SegmentKind, pos: Int) {
        val r = when (kind) {
            SegmentKind.JFIF -> 0
            SegmentKind.EXIF -> 1
            SegmentKind.ICC -> 2
            else -> 3
        }
        if (options.enforceSegmentOrder && r < rank) {
            fail(
                FindingCode.SEGMENT_ORDER,
                "${kind.name} after a later segment kind (expected JFIF, EXIF, ICC, coding segments)",
                pos,
            )
        }
        if (r > rank) rank = r
    }

    private fun sof(markerPos: Int, payload: Int, size: Int) {
        frames++
        coding.sof(markerPos, payload, size)
        val h = u16(payload + 1)
        val w = u16(payload + 3)
        if (w == 0 || h == 0) throw Fatal(FindingCode.UNPARSEABLE, "image dimension is zero", markerPos)
        if (frames == 1) {
            width = w
            height = h
        }
    }

    private fun jfif(markerPos: Int, payload: Int, size: Int) {
        jfifCount++
        if (!options.allowJfif) fail(FindingCode.APP_NOT_ALLOWED, "JFIF segment not allowed", markerPos)
        if (jfifCount > 1) fail(FindingCode.DUPLICATE_APP0, "more than one JFIF segment", markerPos)
        // JFIF 1.x: 14 payload bytes; thumbnail width/height at payload bytes 12 and 13 must be zero.
        if (size != 14 || u8(payload + 12) != 0 || u8(payload + 13) != 0) {
            fail(FindingCode.JFIF_THUMBNAIL, "JFIF segment carries a thumbnail or extra bytes", markerPos)
        }
    }

    private fun exif(markerPos: Int, payload: Int, size: Int) {
        exifCount++
        if (exifCount > 1) fail(FindingCode.DUPLICATE_EXIF, "more than one EXIF segment", markerPos)
        val total = size + 4
        if (exifCount == 1) exifBytes = total
        if (total > options.maxExifSegmentBytes) {
            fail(
                FindingCode.EXIF_TOO_LARGE,
                "EXIF segment of $total bytes exceeds ${options.maxExifSegmentBytes}",
                markerPos,
            )
        }
        val result = ExifInspector(data, payload, size, options) { code, severity, message ->
            add(Finding(code, severity, message, markerPos))
        }.inspect()
        exifTags.addAll(result.tags)
        if (exifCount == 1) orientation = result.orientation
    }

    private fun icc(markerPos: Int, payload: Int, size: Int) {
        iccCount++
        if (!options.allowIcc) fail(FindingCode.ICC_NOT_ALLOWED, "ICC profile not allowed", markerPos)
        if (iccCount > 1) fail(FindingCode.ICC_MULTIPLE_SEGMENTS, "more than one ICC_PROFILE segment", markerPos)
        if (iccCount > 1) return
        // 12 bytes "ICC_PROFILE\0", then chunk number and chunk count.
        if (size < 14 || u8(payload + 12) != 1 || u8(payload + 13) != 1) {
            fail(FindingCode.ICC_MALFORMED, "ICC chunk header is not 1 of 1", markerPos)
            return
        }
        val from = payload + 14
        val to = payload + size
        val result = IccInspector.inspect(data, from, to) { code, message -> fail(code, message, markerPos) } ?: return
        iccSha = result.sha256
        iccDescription = result.description
        val source = options.sourceIccProfile
        if (source != null && source.size == to - from && sha256Hex(source) == result.sha256) {
            fail(
                FindingCode.ICC_COPIED_FROM_SOURCE,
                "the ICC profile is byte-identical to the source's profile",
                markerPos,
            )
        }
        if (!options.measureIccOnly && result.sha256 !in options.allowedIccSha256) {
            fail(FindingCode.ICC_NOT_ON_ALLOWLIST, "the ICC profile is not one of the reviewed profiles", markerPos)
        }
    }

    private fun searchCanaries() {
        var reported = 0
        for ((index, canary) in options.canaries.withIndex()) {
            if (canary.isEmpty()) continue
            // EXIF UserComment and ICC v4 descriptions are UTF-16: search those encodings of the canary too.
            val text = String(canary, Charsets.ISO_8859_1)
            val at = listOf(canary, text.toByteArray(Charsets.UTF_16BE), text.toByteArray(Charsets.UTF_16LE))
                .map { indexOf(it) }.firstOrNull { it >= 0 } ?: -1
            if (at >= 0 && reported++ < MAX_CANARY_REPORT) {
                fail(FindingCode.CANARY_FOUND, "canary #$index (${canary.size} bytes) occurs in the output", at)
            }
        }
    }

    private fun indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        // The coding segments are validated structurally (CodingValidator); every other segment, ICC included,
        // is searched for an embedded JPEG signature.
        val CODING_KINDS = setOf(
            SegmentKind.DQT,
            SegmentKind.DHT,
            SegmentKind.SOF,
            SegmentKind.SOS,
            SegmentKind.DRI,
            SegmentKind.OTHER,
        )
    }
}
