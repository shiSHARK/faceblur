// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

/** The name under which each pixel defect is reported. */
public enum class OracleCode {
    /** A raster cannot be decoded, or compared images differ in size. */
    UNDECODABLE,
    SIZE_MISMATCH,

    /** A check that judged no pixel at all (empty zone, mask outside the image): never a pass. */
    NOTHING_CHECKED,

    /** The evidence given to `requireMasked` is not usable: shared buffers, or a tolerance the exact mode does not allow. */
    INVALID_EVIDENCE,

    /** The masker given to `requireMaskedBy` does not follow its argument (it masks something captured instead). */
    MASKER_IGNORES_ITS_ARGUMENT,

    /** The pair given to the differential check does not meet its own precondition. */
    INVALID_PAIR,
    PAIR_NOT_DIFFERENT,

    /** A pixel beyond every soft edge differs from the input. */
    CHANGED_OUTSIDE_SOFT_EDGE,

    /** A pixel with r <= 1 is not fully opaque. */
    NOT_OPAQUE_INSIDE,

    /** A pixel with r <= 1 differs from the expected colour field. */
    COLOUR_FIELD_MISMATCH,

    /** A pixel with r <= 1 still equals the source pixel. */
    EQUALS_SOURCE_INSIDE,

    /** In the soft edge the opacity grows with r (by more than one rounding level). */
    SOFT_EDGE_OPACITY_INCREASES,

    /** In the soft edge the output alpha is lower than the input alpha: the mask changed pixels inward. */
    ALPHA_LOWERED_IN_SOFT_EDGE,

    /** The soft edge blends nothing: the mask has no outward edge (or is not applied). */
    SOFT_EDGE_MISSING,

    /** A pixel changed further beyond the ellipse than the band the owner fixed in pixels. */
    CHANGED_BEYOND_BAND,

    /** The feather weight leaves the bounds fixed in advance (ADR 0002 test c2). */
    SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS,

    /** Nothing was masked on a path that must mask every face. */
    NOT_MASKED,

    /** `Fidelity.Exact` was used on an output that is not exact outside the masks (a lossy output). */
    INEXACT_OUTPUT,

    /** The check cannot tell a masked pixel from an unmasked one for these inputs (the face is within the tolerance). */
    CHECK_CANNOT_DISCRIMINATE,

    /** The pair differs in too small a share of the pixels inside the masks to prove independence. */
    PAIR_TOO_THIN,

    /** The masked area does not cover the expected ellipse (for example a ring only). */
    GEOMETRY_COVERAGE,

    /** Two outputs that must be byte-identical or pixel-identical are not. */
    BYTES_DIFFER,
    PIXELS_DIFFER,

    /** Inputs that differ only inside the masks produce outputs that differ: the output depends on masked input. */
    DEPENDS_ON_MASKED_INPUT,

    /** The measured masked region is not where, or as large as, the expected ellipse. */
    GEOMETRY_POSITION,
    GEOMETRY_SIZE,

    /** After a typed error the caller's buffer is not exactly as before. */
    BUFFER_CHANGED_AFTER_ERROR,

    /** Some masks are applied and others are not. */
    PARTLY_MASKED,
}

/** One reported defect: how many pixels, and where the first one is (coordinates only, never pixel values). */
public data class OracleFinding(
    val code: OracleCode,
    val message: String,
    val count: Int = 1,
    val firstX: Int? = null,
    val firstY: Int? = null,
)

/** Result of one or more oracle checks. */
public class OracleReport(
    public val findings: List<OracleFinding>,
    /** Pixels the checks actually judged (ambiguous boundary pixels are not counted). */
    public val pixelsJudged: Long,
    /** Pixels too close to a boundary to judge. */
    public val ambiguousPixels: Long = 0,
    /** Largest distance in pixels of any changed pixel beyond the ellipse, when the check measured it. */
    public val maxChangedDistancePx: Double? = null,
) {
    public val passed: Boolean get() = findings.isEmpty()

    public val codes: Set<OracleCode> get() = findings.mapTo(linkedSetOf()) { it.code }

    public operator fun plus(other: OracleReport): OracleReport = OracleReport(
        findings + other.findings,
        pixelsJudged + other.pixelsJudged,
        ambiguousPixels + other.ambiguousPixels,
        listOfNotNull(maxChangedDistancePx, other.maxChangedDistancePx).maxOrNull(),
    )

    public fun summary(): String = if (passed) {
        "PASS ($pixelsJudged pixels judged, $ambiguousPixels ambiguous" +
            (maxChangedDistancePx?.let { ", changed up to %.2f px beyond the ellipse".format(it) } ?: "") + ")"
    } else {
        "FAIL " + findings.joinToString("; ") { f ->
            f.code.name + " x" + f.count + (f.firstX?.let { " first at ($it, ${f.firstY})" } ?: "") + ": " +
                f.message
        }
    }

    /** Throws an [AssertionError] listing the findings unless the check passed. Returns this report. */
    public fun assertPassed(): OracleReport {
        if (!passed) throw AssertionError("pixel oracle: " + summary())
        return this
    }
}
