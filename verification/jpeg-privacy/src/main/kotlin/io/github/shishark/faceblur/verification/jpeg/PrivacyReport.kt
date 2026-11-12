// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** Everything the checker learned about one file. Findings carry no metadata values. */
public class PrivacyReport internal constructor(
    public val findings: List<Finding>,
    public val segments: List<SegmentInfo>,
    public val fileSize: Int,
    public val width: Int?,
    public val height: Int?,
    /** EXIF tags found (union over all EXIF segments), for building the expected tag set from an input. */
    public val exifTags: Set<ExifTag>,
    public val orientation: Int?,
    public val exifSegmentBytes: Int?,
    /** SHA-256 of the ICC profile, if the file has one. */
    public val iccSha256: String?,
    /** Description text of the ICC profile; for tests only, do not log. */
    public val iccDescription: String?,
) {
    public val failures: List<Finding> get() = findings.filter { it.severity == Severity.FAIL }

    public val information: List<Finding> get() = findings.filter { it.severity == Severity.INFO }

    /** True when no finding makes the output not private. Unparseable files never pass. */
    public val passed: Boolean get() = failures.isEmpty()

    public val failureCodes: Set<FindingCode> get() = failures.mapTo(linkedSetOf()) { it.code }

    public val informationCodes: Set<FindingCode> get() = information.mapTo(linkedSetOf()) { it.code }

    /** True if the file could not be understood (counts as not private, PRIV-020). */
    public val isUnparseable: Boolean get() = failureCodes.any { it in UNPARSEABLE_CODES }

    /** The marker walk in order, for example `SOI JFIF EXIF DQT SOF DHT SOS EOI`. */
    public val segmentList: String get() = segments.joinToString(" ") { it.kind.name }

    public fun summary(): String = if (passed) {
        "PASS (${segments.size} segments: $segmentList)"
    } else {
        "FAIL " + failures.joinToString("; ") { f ->
            f.code.name + (f.offset?.let { "@$it" } ?: "") + ": " + f.message
        }
    }

    internal companion object {
        val UNPARSEABLE_CODES = setOf(
            FindingCode.NOT_JPEG,
            FindingCode.UNPARSEABLE,
            FindingCode.EXIF_UNPARSEABLE,
            FindingCode.EXIF_BAD_HEADER,
            FindingCode.DECODER_REJECTED,
            FindingCode.FILE_TOO_LARGE,
            FindingCode.NO_EOI,
            FindingCode.CHECKER_INTERNAL_ERROR,
        )
    }
}
