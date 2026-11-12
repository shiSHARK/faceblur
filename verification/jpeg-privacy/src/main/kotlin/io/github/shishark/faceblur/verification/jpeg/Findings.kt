// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** FAIL makes the output not private; INFO is reported only. */
public enum class Severity { FAIL, INFO }

/** The name under which each defect is reported (ST-042 AC1: "reports each defect by name"). */
public enum class FindingCode {
    // The file cannot be understood: counts as not private (PRIV-020).
    NOT_JPEG,
    UNPARSEABLE,
    EXIF_UNPARSEABLE,
    FILE_TOO_LARGE,
    NO_EOI,
    CHECKER_INTERNAL_ERROR,

    // JPEG structure and segments.
    TRAILING_BYTES,
    SECOND_IMAGE,
    SECOND_SOI,
    EMBEDDED_JPEG_IN_SEGMENT,
    UNEXPECTED_MARKER,
    NO_FRAME,
    NO_SCAN,
    MULTIPLE_FRAMES,
    CODING_TABLE_REDUNDANT,
    DECODER_WARNING,
    DECODER_REJECTED,
    DECODE_SKIPPED,
    COM_PRESENT,
    XMP_PRESENT,
    IPTC_APP13,
    MPF_PRESENT,
    JFXX_THUMBNAIL,
    JFIF_THUMBNAIL,
    APP_NOT_ALLOWED,
    DUPLICATE_APP0,
    SEGMENT_ORDER,
    CANARY_FOUND,

    // EXIF.
    EXIF_MISSING,
    DUPLICATE_EXIF,
    EXIF_TOO_LARGE,
    EXIF_BAD_HEADER,
    EXIF_THUMBNAIL_IFD1,
    EXIF_SUBIFD_NOT_ALLOWED,
    GPS_NOT_ALLOWED,
    EXIF_INTEROP_NOT_ALLOWED,
    MAKERNOTE_PRESENT,
    EXIF_TAG_NOT_ALLOWED,
    EXIF_DUPLICATE_TAG,
    EXIF_VALUE_INVALID,
    EXIF_UNREFERENCED_BYTES,
    ORIENTATION_MISSING,
    ORIENTATION_NOT_1,
    REQUIRED_TAG_MISSING,
    ALLOWLISTED_TAG_ABSENT,

    // ICC.
    ICC_NOT_ALLOWED,
    ICC_MULTIPLE_SEGMENTS,
    ICC_MALFORMED,
    ICC_NOT_RGB,
    ICC_COPIED_FROM_SOURCE,
    ICC_NOT_ON_ALLOWLIST,
}

/**
 * One result of the checker. [message] never contains metadata values or file content, only names, tag
 * identifiers, counts and offsets, so it is safe to print in logs.
 */
public data class Finding(val code: FindingCode, val severity: Severity, val message: String, val offset: Int? = null)

/** Kind of a JPEG segment as classified by the checker. */
public enum class SegmentKind {
    SOI,
    EOI,
    JFIF,
    JFXX,
    EXIF,
    XMP,
    ICC,
    MPF,
    IPTC,
    COM,
    OTHER_APP,
    DQT,
    DHT,
    SOF,
    SOS,
    DRI,
    OTHER,
}

/** One segment of the marker walk. [length] counts the marker and the length field, [offset] is the marker offset. */
public data class SegmentInfo(val marker: Int, val offset: Int, val length: Int, val kind: SegmentKind)
