// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A block of the JPEG minimum-coded-unit grid. */
public data class Block(val x: Int, val y: Int, val size: Int)

/** Collects findings of one check. */
private class Collector {
    val findings = mutableListOf<OracleFinding>()
    var judged = 0L
    var ambiguous = 0L
    var maxDistance: Double? = null

    fun add(code: OracleCode, message: String, count: Int = 1, x: Int? = null, y: Int? = null) {
        findings.add(OracleFinding(code, message, count, x, y))
    }

    fun requireJudged(what: String) {
        if (judged == 0L) add(OracleCode.NOTHING_CHECKED, "$what judged no pixel")
    }

    fun report() = OracleReport(findings.toList(), judged, ambiguous, maxDistance)
}

/** Counts matching pixels and remembers the first. */
private class Tally {
    var count = 0
    var firstX = -1
    var firstY = -1

    fun hit(x: Int, y: Int) {
        if (count++ == 0) {
            firstX = x
            firstY = y
        }
    }
}

/** Which of the zones of FR-004 each pixel is in, derived from the masks by this oracle. */
internal class Zones(val width: Int, val height: Int, masks: List<MaskEllipse>) {
    private val state = ByteArray(width * height)

    init {
        for (m in masks) {
            val reach = 1.0 + m.softEdgeWidth + MaskEllipse.BOUNDARY_EPSILON
            val x0 = max(0, floor(m.cx - reach * m.rx).toInt() - 1)
            val x1 = min(width - 1, ceil(m.cx + reach * m.rx).toInt() + 1)
            val y0 = max(0, floor(m.cy - reach * m.ry).toInt() - 1)
            val y1 = min(height - 1, ceil(m.cy + reach * m.ry).toInt() + 1)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val p = priority(m, m.radius(x, y))
                    val i = y * width + x
                    if (p > state[i]) state[i] = p.toByte()
                }
            }
        }
    }

    private fun priority(m: MaskEllipse, r: Double): Int {
        val eps = MaskEllipse.BOUNDARY_EPSILON
        val outer = 1.0 + m.softEdgeWidth
        return when {
            r <= 1.0 - eps -> INSIDE
            r <= 1.0 + eps -> AMBIGUOUS_INNER
            r <= outer - eps -> SOFT
            r <= outer + eps -> AMBIGUOUS_OUTER
            else -> OUTSIDE
        }
    }

    fun at(x: Int, y: Int): Int = state[y * width + x].toInt()

    fun isAmbiguous(code: Int): Boolean = code == AMBIGUOUS_INNER || code == AMBIGUOUS_OUTER

    companion object {
        const val OUTSIDE = 0
        const val AMBIGUOUS_OUTER = 1
        const val SOFT = 2
        const val AMBIGUOUS_INNER = 3
        const val INSIDE = 4
    }
}

/** How exactly the output reproduces the input outside the masks; decides what "the face survived" means. */
public sealed interface Fidelity {
    /** Lossless or in-memory output: a pixel survives when it equals the source exactly. */
    public data object Exact : Fidelity

    /**
     * Lossy output (JPEG): [reference] is the SAME input encoded and decoded the same way but NOT masked. A pixel
     * survives when it is within [survivalThreshold] of the reference in every channel. Fix the threshold from a
     * baseline before the test is Ready and never widen it after a result.
     */
    public class Lossy(
        public val reference: Raster,
        public val survivalThreshold: Int,
        /** Side of the square window (fully inside r <= 1) in which surviving face TEXTURE is looked for. */
        public val textureWindow: Int = 5,
        /** Windows whose reference luma deviates less than this are flat: a face there cannot be told from a mask. */
        public val minReferenceStdDev: Double = 10.0,
        /** Window texture: output luma deviation at least this and correlation with the reference at least [minCorrelation]. */
        public val minOutputStdDev: Double = 6.0,
        public val minCorrelation: Double = 0.6,
        /** Largest share of flat windows; above it the check reports [OracleCode.CHECK_CANNOT_DISCRIMINATE]. */
        public val maxFlatFraction: Double = 0.5,
    ) : Fidelity {
        init {
            require(survivalThreshold >= 0 && textureWindow >= 2) { "invalid lossy fidelity" }
        }
    }
}

/** Which call path is judged by [PixelOracle.maskApplication]. */
public enum class Expect {
    /** A call that succeeded: every mask must be fully applied. */
    ALL_APPLIED,

    /** A call that raised a typed error: no mask may be applied (nothing partly masked). */
    NONE_APPLIED,
}

/**
 * Evidence that the colours inside the masks do not depend on the input. Alpha-only checks ([PixelOracle.insideOpaque],
 * [PixelOracle.softEdgeOutwardOnly], [PixelOracle.softEdgeWeightBounds]) can pass while the face colours survive with alpha
 * forced to 255 or mixed with grey; evidence is therefore mandatory for [PixelOracle.requireMaskedBy]. Internal on purpose:
 * consumers get a masked verdict only from [PixelOracle.requireMaskedBy] (the oracle builds and runs the pair itself).
 * A pair supplied by the caller is TRUSTED to be the masker's result for the second input (a copy of the first output, or
 * one output file decoded twice, cannot be detected from values); the internal lossy entry exists for oracle tests only.
 */
internal sealed interface ColourIndependence {
    /**
     * The test knows the colour field of the style under test (solid colour, a gradient it defines). It must not be derived
     * from the output under test. Exact fidelity requires tolerance 0 (a tolerance lets a translucent face mix through).
     */
    class Field(val field: (Int, Int) -> Int, val tolerance: Int) : ColourIndependence

    /**
     * A second input that differs from the first in the pixels inside the masks, masked with the SAME mask plan:
     * the outputs must be identical there (no knowledge of the colours needed). Build the second input with
     * [PixelOracle.pairInputFor]. The floors for [minInputDifference] and [minDifferingFraction] cannot be lowered.
     */
    class DifferentialPair(
        val inputB: Raster,
        val outputB: Raster,
        /** Exact fidelity requires 0; a lossy pipeline may state a bound fixed from a baseline. */
        val maxChannelDelta: Int = 0,
        /** Share of the pixels inside the masks in which the two inputs differ by at least [minInputDifference]. */
        val minDifferingFraction: Double = PixelOracle.DEFAULT_MIN_DIFFERING_FRACTION,
        val minInputDifference: Int = PixelOracle.DEFAULT_MIN_INPUT_DIFFERENCE,
    ) : ColourIndependence
}

/**
 * Which pixels inside the masks still carry the face. [Fidelity.Exact]: the RGB equals the source (alpha is not looked
 * at: forcing alpha to 255 does not mask anything). [Fidelity.Lossy]: within the threshold of the unmasked reference
 * decode, or inside a window in which the reference texture survives (output texture correlated with the reference),
 * which finds holes the pixel threshold misses. A lossy output can not show that NO pixel survives: holes smaller than
 * the window are invisible, and flat reference areas can not be judged (counted in [flatWindows]).
 */
internal class Survival(
    private val source: Raster,
    private val output: Raster,
    private val fidelity: Fidelity,
    zones: Zones,
    /** [Fidelity.Exact] only: a second (input, output) pair made with the same mask plan; see [PixelOracle.insideDiffersFromSource]. */
    private val second: Pair<Raster, Raster>? = null,
) {
    private val textureHit: BooleanArray? = if (fidelity is Fidelity.Lossy) {
        BooleanArray(
            source.width * source.height,
        )
    } else {
        null
    }
    var windows = 0
        private set
    var flatWindows = 0
        private set

    init {
        if (fidelity is Fidelity.Lossy) scanTexture(fidelity, zones)
    }

    fun survives(x: Int, y: Int): Boolean = when (fidelity) {
        // A match with the source survives, unless a pair shows that the output did not follow the input: the second
        // output equals the first (an input-independent fill) while it differs from the second input.
        is Fidelity.Exact -> equalRgb(source, output, x, y) &&
            (second == null || equalRgb(second.first, second.second, x, y) || !equalRgb(output, second.second, x, y))

        is Fidelity.Lossy ->
            PixelOracle.channelDelta(output[x, y], fidelity.reference[x, y]) <= fidelity.survivalThreshold ||
                textureHit!![y * source.width + x]
    }

    /** True where the output equals the source but the pair excuses it as a coincidence of an input-independent fill. */
    fun excused(x: Int, y: Int): Boolean = fidelity is Fidelity.Exact && second != null &&
        equalRgb(source, output, x, y) && !survives(x, y)

    private fun equalRgb(a: Raster, b: Raster, x: Int, y: Int): Boolean =
        (a[x, y] and 0xFFFFFF) == (b[x, y] and 0xFFFFFF)

    private fun luma(p: Int): Double =
        0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)

    private fun scanTexture(f: Fidelity.Lossy, zones: Zones) {
        val w = f.textureWindow
        val n = (w * w).toDouble()
        for (y in 0..source.height - w) {
            for (x in 0..source.width - w) {
                if (!windowInside(zones, x, y, w)) continue
                var sr = 0.0
                var so = 0.0
                var srr = 0.0
                var soo = 0.0
                var sro = 0.0
                for (yy in y until y + w) {
                    for (xx in x until x + w) {
                        val r = luma(f.reference[xx, yy])
                        val o = luma(output[xx, yy])
                        sr += r
                        so += o
                        srr += r * r
                        soo += o * o
                        sro += r * o
                    }
                }
                val varR = srr / n - (sr / n) * (sr / n)
                val varO = soo / n - (so / n) * (so / n)
                windows++
                if (varR < f.minReferenceStdDev * f.minReferenceStdDev) {
                    flatWindows++
                    continue
                }
                if (varO < f.minOutputStdDev * f.minOutputStdDev) continue
                val correlation = (sro / n - (sr / n) * (so / n)) / Math.sqrt(varR * varO)
                if (correlation >= f.minCorrelation) {
                    for (yy in y until y + w) for (xx in x until x + w) textureHit!![yy * source.width + xx] = true
                }
            }
        }
    }

    private fun windowInside(zones: Zones, x: Int, y: Int, w: Int): Boolean {
        for (yy in y until y + w) for (xx in x until x + w) if (zones.at(xx, yy) != Zones.INSIDE) return false
        return true
    }
}

/**
 * The independent pixel oracle (ST-045, PRIV-023): decode-and-compare checks on pixels with no library code. The
 * mask region is derived from FR-004 by [MaskEllipse]. A check that judged no pixel never passes
 * ([OracleCode.NOTHING_CHECKED]); sizes that differ are [OracleCode.SIZE_MISMATCH].
 *
 * The alpha checks ([insideOpaque], [softEdgeOutwardOnly], [softEdgeWeightBounds]) are never sufficient alone: they
 * pass while the face colours survive with alpha forced to 255. [requireMasked] adds colour independence.
 *
 * Which checks an UNMASKED image fails (each has a negative control in the self-test): [insideOpaque],
 * [insideColourField], [insideDiffersFromSource], [maskApplication] (ALL_APPLIED), [softEdgeOutwardOnly],
 * [softEdgeWeightBounds], [maskedRegionMatches], [differentialPair] (outputs equal to the inputs). The checks that
 * an unmasked image satisfies by definition are [unchangedOutside], [unchangedAfterError], [identicalBytes] and
 * [identicalPixels]; use [requireMaskedBy] to run the mandatory set together.
 */
public object PixelOracle {
    /** Largest (r - 1) / softEdgeWidth at which the weight may still round to full coverage (ADR 0002 test c2). */
    public const val FULL_WEIGHT_MAX_T: Double = 0.05

    /** The output alpha may rise outward by at most this many levels (rounding). */
    public const val ROUNDING_LEVELS: Int = 1

    /** A pair's inputs must differ by at least this many levels in some channel to count as differing inside the masks. */
    public const val DEFAULT_MIN_INPUT_DIFFERENCE: Int = 32

    /** Share of the pixels inside the masks that must differ strongly. Build pairs with [pairInputFor] (100 %): plain random noise pairs reach about 98.7 % and fail on small masks. */
    public const val DEFAULT_MIN_DIFFERING_FRACTION: Double = 0.98

    /** Floor of the differing share for lossy block pairs (whole 16 x 16 blocks differ, not every pixel). */
    public const val LOSSY_MIN_DIFFERING_FRACTION: Double = 0.10

    /**
     * Ceiling of the pair tolerance (levels per channel) of the internal, caller-supplied [Fidelity.Lossy] entries used by the
     * oracle's own tests; a larger one is [OracleCode.INVALID_EVIDENCE]. Not reachable through public API.
     */
    internal const val LOSSY_MAX_PAIR_TOLERANCE: Int = 8

    /**
     * Ceiling of the pair tolerance (levels per channel) of [requireMaskedLossyBy]: 0, so B's output must equal A's in every
     * channel of every pixel of the block-safe blocks. At 8 levels a mask that keeps 1/32 (about 3 %) of the face colours
     * passed; a platform encoder that needs more needs a measured baseline first. A larger value is
     * [OracleCode.INVALID_EVIDENCE].
     */
    public const val LOSSY_BY_MAX_PAIR_TOLERANCE: Int = 0

    /**
     * Smallest luma correlation, over the pixels with r <= 1, between an input of [requireMaskedLossyBy] and the result of
     * the unmasked pipeline for it: below it the "unmasked" reference is not the input encoded and decoded, and judging
     * survival against it would prove nothing ([OracleCode.INVALID_EVIDENCE]).
     */
    public const val LOSSY_MIN_REFERENCE_CORRELATION: Double = 0.9

    /** Largest share of inside pixels that may equal the source and be excused as a coincidence of a pair-based check. */
    public const val MAX_EXCUSED_FRACTION: Double = 0.2

    /** Pixel-centre slack added to the band in pixels (half a pixel; the band is fixed, this only absorbs sampling). */
    public const val BAND_PIXEL_CENTRE_SLACK: Double = 0.5

    /** Pixels beyond every soft edge are exactly as in the input. (An unmasked image satisfies this by definition.) */
    public fun unchangedOutside(input: Raster, output: Raster, masks: List<MaskEllipse>): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val zones = Zones(input.width, input.height, masks)
        val bad = Tally()
        forEachPixel(input) { x, y ->
            val z = zones.at(x, y)
            if (zones.isAmbiguous(z)) {
                c.ambiguous++
            } else if (z == Zones.OUTSIDE) {
                c.judged++
                if (input[x, y] != output[x, y]) bad.hit(x, y)
            }
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.CHANGED_OUTSIDE_SOFT_EDGE,
                "pixels beyond every soft edge differ from the input",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        c.requireJudged("unchangedOutside")
        return c.report()
    }

    /**
     * Every pixel with r <= 1 whose INPUT alpha is below 0xFF has alpha 0xFF in the output (opacity core). The
     * input must have such pixels, otherwise nothing is judged ([OracleCode.NOTHING_CHECKED]): on an opaque input or a
     * decoded JPEG (no alpha) the check proves nothing, and it says so. In-memory ARGB outputs only.
     */
    public fun insideOpaque(input: Raster, output: Raster, masks: List<MaskEllipse>): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val zones = Zones(output.width, output.height, masks)
        val bad = Tally()
        forEachPixel(output) { x, y ->
            val z = zones.at(x, y)
            if (zones.isAmbiguous(z)) {
                c.ambiguous++
            } else if (z == Zones.INSIDE && (input[x, y] ushr 24) != 0xFF) {
                c.judged++
                if ((output[x, y] ushr 24) != 0xFF) bad.hit(x, y)
            }
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.NOT_OPAQUE_INSIDE,
                "pixels with r <= 1 are not fully opaque",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        c.requireJudged("insideOpaque (the input needs alpha below 255 inside the masks)")
        return c.report()
    }

    /**
     * Every pixel with r <= 1 has alpha 0xFF in the output, whatever the input alpha (also for opaque inputs). Used by
     * [requireMaskedBy]; an unmasked opaque image satisfies it, so it never stands alone.
     */
    public fun insideAlphaIs255(output: Raster, masks: List<MaskEllipse>): OracleReport {
        val c = Collector()
        val zones = Zones(output.width, output.height, masks)
        val bad = Tally()
        forEachPixel(output) { x, y ->
            val z = zones.at(x, y)
            if (zones.isAmbiguous(z)) {
                c.ambiguous++
            } else if (z == Zones.INSIDE) {
                c.judged++
                if ((output[x, y] ushr 24) != 0xFF) bad.hit(x, y)
            }
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.NOT_OPAQUE_INSIDE,
                "pixels with r <= 1 are not fully opaque",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        c.requireJudged("insideAlphaIs255")
        return c.report()
    }

    /**
     * Every pixel with r <= 1 equals [expected] (alpha included) within [maxChannelDelta] per channel. The check must be
     * able to tell a masked pixel from a face pixel: if more than [maxIndistinguishableFraction] of the judged pixels have
     * an INPUT within [maxChannelDelta] of the expected colour, the face could hide inside the tolerance and the check
     * reports [OracleCode.CHECK_CANNOT_DISCRIMINATE]. Use inputs far from the mask colours (saturated noise, planted faces).
     */
    public fun insideColourField(
        input: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        expected: (Int, Int) -> Int,
        maxChannelDelta: Int,
        maxIndistinguishableFraction: Double = 0.0,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val zones = Zones(output.width, output.height, masks)
        val bad = Tally()
        var indistinguishable = 0
        forEachPixel(output) { x, y ->
            val z = zones.at(x, y)
            if (zones.isAmbiguous(z)) {
                c.ambiguous++
            } else if (z == Zones.INSIDE) {
                c.judged++
                if (channelDelta(output[x, y], expected(x, y)) > maxChannelDelta) bad.hit(x, y)
                if (channelDelta(input[x, y], expected(x, y)) <= maxChannelDelta) indistinguishable++
            }
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.COLOUR_FIELD_MISMATCH,
                "pixels with r <= 1 differ from the expected colour field",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        if (c.judged > 0 && indistinguishable > maxIndistinguishableFraction * c.judged) {
            c.add(
                OracleCode.CHECK_CANNOT_DISCRIMINATE,
                "$indistinguishable of ${c.judged} input pixels are within the tolerance of the expected colour",
                indistinguishable,
            )
        }
        c.requireJudged("insideColourField")
        return c.report()
    }

    /**
     * No more than [maxSurvivingPixels] pixels with r <= 1 survive. What "survives" means depends on [fidelity]:
     * [Fidelity.Exact] (output equals source exactly; requires the output to be exact outside the masks, else
     * [OracleCode.INEXACT_OUTPUT] because a lossy output cannot be judged this way) or [Fidelity.Lossy] (within a
     * threshold of the unmasked reference decode). On noise inputs equality with the source is never by chance. On real
     * photos a fill taken from the surroundings can equal a face pixel by chance (a flat background): pass the second
     * input and output of a differential pair as [pair]; a pixel then survives only if the output equals the source in
     * BOTH inputs, which a fill that does not depend on the input never does (a leaking face always does). [pair] is
     * used for [Fidelity.Exact] only.
     */
    public fun insideDiffersFromSource(
        source: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        fidelity: Fidelity,
        maxSurvivingPixels: Int = 0,
        pair: Pair<Raster, Raster>? = null,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, source, output)) return c.report()
        if (fidelity is Fidelity.Lossy && !sameSize(c, source, fidelity.reference)) return c.report()
        val zones = Zones(source.width, source.height, masks)
        val survival = Survival(source, output, fidelity, zones, pair)
        val surviving = Tally()
        val inexact = Tally()
        var excusedCount = 0
        forEachPixel(source) { x, y ->
            val z = zones.at(x, y)
            if (zones.isAmbiguous(z)) {
                c.ambiguous++
            } else if (z == Zones.INSIDE) {
                c.judged++
                if (survival.survives(x, y)) surviving.hit(x, y)
                if (survival.excused(x, y)) excusedCount++
            } else if (z == Zones.OUTSIDE && fidelity is Fidelity.Exact && source[x, y] != output[x, y]) {
                inexact.hit(x, y)
            }
        }
        if (inexact.count > 0) {
            c.add(
                OracleCode.INEXACT_OUTPUT,
                "Exact fidelity needs an output identical to the source outside the masks; use Lossy",
                inexact.count,
                inexact.firstX,
                inexact.firstY,
            )
        }
        if (fidelity is Fidelity.Lossy && survival.windows > 0 &&
            survival.flatWindows.toDouble() / survival.windows > fidelity.maxFlatFraction
        ) {
            c.add(
                OracleCode.CHECK_CANNOT_DISCRIMINATE,
                "${survival.flatWindows} of ${survival.windows} windows inside the masks are flat in the reference: " +
                    "a lossy output can not show a face hole there; use an Exact output",
                survival.flatWindows,
            )
        }
        if (c.judged > 0 && excusedCount > MAX_EXCUSED_FRACTION * c.judged) {
            c.add(
                OracleCode.CHECK_CANNOT_DISCRIMINATE,
                "$excusedCount of ${c.judged} output pixels equal the source and are only excused by the pair: the " +
                    "input has too much flat colour (or the pair is not what it claims) to tell a coincidence from a leak",
                excusedCount,
            )
        }
        if (surviving.count > maxSurvivingPixels) {
            c.add(
                OracleCode.EQUALS_SOURCE_INSIDE,
                "pixels with r <= 1 survive from the source",
                surviving.count,
                surviving.firstX,
                surviving.firstY,
            )
        }
        c.requireJudged("insideDiffersFromSource")
        return c.report()
    }

    /**
     * (c) Only outward changes: in the soft edge the output alpha is never lower than the input alpha (a mask never
     * changes pixels inward), and the soft edge exists: at least one soft-edge pixel differs from the input, otherwise
     * [OracleCode.SOFT_EDGE_MISSING] (an unmasked image, or a mask without outward edge). The owner's band: a changed
     * pixel further beyond the ellipse than [MaskEllipse.maxBandWidthPx] (fixed from the geometry and the feather, never
     * from the output; +0.5 px for the pixel centre) is [OracleCode.CHANGED_BEYOND_BAND]; the largest distance found is in
     * the report. In-memory ARGB outputs only.
     */
    public fun softEdgeOutwardOnly(input: Raster, output: Raster, masks: List<MaskEllipse>): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val zones = Zones(input.width, input.height, masks)
        val lowered = Tally()
        val beyondBand = Tally()
        var changed = 0
        forEachPixel(input) { x, y ->
            val z = zones.at(x, y)
            if (z == Zones.SOFT) {
                c.judged++
                if ((output[x, y] ushr 24) < (input[x, y] ushr 24)) lowered.hit(x, y)
                if (output[x, y] != input[x, y]) changed++
            }
            if (z != Zones.INSIDE && output[x, y] != input[x, y]) {
                // The owner's rule in pixels: a changed pixel lies within the fixed band of some mask.
                var nearest = Double.MAX_VALUE
                var within = false
                for (m in masks) {
                    val r = m.radius(x, y)
                    // (r - 1) x the smaller semi-axis is a lower bound of the distance; the exact value only where it can matter.
                    val lower = (r - 1.0) * m.smallerSemiAxis
                    val limit = m.maxBandWidthPx + BAND_PIXEL_CENTRE_SLACK
                    val d = when {
                        r <= 1.0 -> 0.0
                        lower > limit -> lower
                        else -> m.distanceTo(x + 0.5, y + 0.5)
                    }
                    nearest = minOf(nearest, d)
                    if (d <= limit) within = true
                }
                if (nearest != Double.MAX_VALUE) c.maxDistance = maxOf(c.maxDistance ?: 0.0, nearest)
                if (!within) beyondBand.hit(x, y)
            }
        }
        if (lowered.count > 0) {
            c.add(
                OracleCode.ALPHA_LOWERED_IN_SOFT_EDGE,
                "the output alpha is lower than the input alpha in the soft edge",
                lowered.count,
                lowered.firstX,
                lowered.firstY,
            )
        }
        if (beyondBand.count > 0) {
            c.add(
                OracleCode.CHANGED_BEYOND_BAND,
                "pixels changed further beyond the ellipse than the band fixed in pixels",
                beyondBand.count,
                beyondBand.firstX,
                beyondBand.firstY,
            )
        }
        if (c.judged > 0 &&
            changed == 0
        ) {
            c.add(OracleCode.SOFT_EDGE_MISSING, "no soft-edge pixel differs from the input")
        }
        c.requireJudged("softEdgeOutwardOnly")
        return c.report()
    }

    /**
     * (c2) Bounded feather weight (ADR 0002 decision 10, bounds fixed in advance, never widened after a run), on an
     * input whose alpha is 0 in the soft edge and beyond (so that output alpha = 255 x weight after rounding):
     * alpha 255 for r <= 1; never rising outward by more than [ROUNDING_LEVELS] level (sorted by r); 255 beyond r = 1
     * only where t = (r - 1) / soft-edge width <= [FULL_WEIGHT_MAX_T]; alpha 0 beyond the soft edge. Masks whose soft
     * edges overlap another mask's reach are judged only where they are clear of the others. Only pixels whose INPUT alpha is 0 are judged; with no such pixel
     * the check reports [OracleCode.NOTHING_CHECKED].
     */
    public fun softEdgeWeightBounds(input: Raster, output: Raster, masks: List<MaskEllipse>): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val eps = MaskEllipse.BOUNDARY_EPSILON
        val outOfBounds = Tally()
        val rises = Tally()
        val notOpaque = Tally()
        val beyond = Tally()
        for ((index, m) in masks.withIndex()) {
            val outer = 1.0 + m.softEdgeWidth
            val samples = ArrayList<Pair<Double, Int>>()
            forEachPixel(input) { x, y ->
                val r = m.radius(x, y)
                if (r > outer + 1.0) return@forEachPixel
                val others = masks.withIndex().any { (j, o) ->
                    j != index &&
                        o.radius(x, y) <= 1.0 + o.softEdgeWidth + eps
                }
                if (others) return@forEachPixel
                val alpha = output[x, y] ushr 24
                when {
                    r <= 1.0 - eps -> {
                        if ((input[x, y] ushr 24) == 0) {
                            c.judged++
                            if (alpha != 0xFF) notOpaque.hit(x, y)
                        }
                    }

                    r > 1.0 + eps && r <= outer - eps -> {
                        if ((input[x, y] ushr 24) == 0) {
                            c.judged++
                            samples.add(r to alpha)
                            if (alpha == 0xFF && (r - 1.0) / m.softEdgeWidth > FULL_WEIGHT_MAX_T) outOfBounds.hit(x, y)
                        }
                    }

                    r > outer + eps -> {
                        if ((input[x, y] ushr 24) == 0) {
                            c.judged++
                            if (alpha != 0) beyond.hit(x, y)
                        }
                    }
                }
            }
            samples.sortBy { it.first }
            var lowest = 255
            for (s in samples) {
                if (s.second > lowest + ROUNDING_LEVELS) rises.hit(0, 0)
                lowest = min(lowest, s.second)
            }
        }
        if (notOpaque.count > 0) {
            c.add(
                OracleCode.NOT_OPAQUE_INSIDE,
                "alpha is not 255 inside r <= 1",
                notOpaque.count,
                notOpaque.firstX,
                notOpaque.firstY,
            )
        }
        if (rises.count > 0) {
            c.add(
                OracleCode.SOFT_EDGE_OPACITY_INCREASES,
                "the alpha rises outward by more than $ROUNDING_LEVELS level",
                rises.count,
            )
        }
        if (outOfBounds.count > 0) {
            c.add(
                OracleCode.SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS,
                "alpha 255 beyond r = 1 where t > $FULL_WEIGHT_MAX_T",
                outOfBounds.count,
                outOfBounds.firstX,
                outOfBounds.firstY,
            )
        }
        if (beyond.count > 0) {
            c.add(
                OracleCode.SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS,
                "alpha is not 0 beyond the soft edge",
                beyond.count,
                beyond.firstX,
                beyond.firstY,
            )
        }
        c.requireJudged("softEdgeWeightBounds (the input needs alpha 0 around and inside the masks)")
        return c.report()
    }

    /** Two outputs are byte-identical (lossless file mode). */
    public fun identicalBytes(a: ByteArray, b: ByteArray): OracleReport {
        val c = Collector()
        c.judged = max(a.size, b.size).toLong()
        if (a.size != b.size) {
            c.add(OracleCode.BYTES_DIFFER, "lengths ${a.size} and ${b.size} differ")
        } else {
            val i = a.indices.firstOrNull { a[it] != b[it] }
            if (i !=
                null
            ) {
                c.add(OracleCode.BYTES_DIFFER, "first difference at byte $i", a.indices.count { a[it] != b[it] })
            }
        }
        c.requireJudged("identicalBytes")
        return c.report()
    }

    /** Two decoded outputs are pixel-identical within [maxChannelDelta] per channel (0 = exact). */
    public fun identicalPixels(a: Raster, b: Raster, maxChannelDelta: Int = 0): OracleReport {
        val c = Collector()
        if (!sameSize(c, a, b)) return c.report()
        val bad = Tally()
        forEachPixel(a) { x, y ->
            c.judged++
            if (channelDelta(a[x, y], b[x, y]) > maxChannelDelta) bad.hit(x, y)
        }
        if (bad.count > 0) c.add(OracleCode.PIXELS_DIFFER, "decoded pixels differ", bad.count, bad.firstX, bad.firstY)
        return c.report()
    }

    /**
     * Precondition of a differential pair: the inputs differ, and only where r <= 1 of the masks. With [mcuSize] set
     * (lossy inputs, called with the rasters BEFORE they are encoded) they may differ only in blocks of that size
     * whose whole one-block neighbourhood has r <= 1, because chroma upsampling reads neighbouring samples
     * (PRIV-005). The inputs must differ by at least [minInputDifference] levels in some channel in at least
     * [minDifferingFraction] of the pixels inside the masks (noise against face; a pair that differs by one level proves
     * nothing), otherwise [OracleCode.PAIR_TOO_THIN]; lossy block pairs state a lower bound.
     * A pair that fails is reported as [OracleCode.INVALID_PAIR] or [OracleCode.PAIR_NOT_DIFFERENT].
     */
    public fun pairPrecondition(
        inputA: Raster,
        inputB: Raster,
        masks: List<MaskEllipse>,
        mcuSize: Int? = null,
        minDifferingFraction: Double = DEFAULT_MIN_DIFFERING_FRACTION,
        minInputDifference: Int = DEFAULT_MIN_INPUT_DIFFERENCE,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, inputA, inputB)) return c.report()
        val zones = Zones(inputA.width, inputA.height, masks)
        val outside = Tally()
        val unsafe = Tally()
        var differing = 0
        var strongInside = 0L
        var insideCount = 0L
        val blockSafe = HashMap<Long, Boolean>()
        forEachPixel(inputA) { x, y ->
            if (zones.at(x, y) == Zones.INSIDE) insideCount++
            if (inputA[x, y] == inputB[x, y]) return@forEachPixel
            differing++
            c.judged++
            if (zones.at(x, y) == Zones.INSIDE &&
                channelDelta(inputA[x, y], inputB[x, y]) >= minInputDifference
            ) {
                strongInside++
            }
            if (zones.at(x, y) != Zones.INSIDE) {
                outside.hit(x, y)
            } else if (mcuSize != null) {
                val key = (y / mcuSize).toLong() * inputA.width + x / mcuSize
                if (!blockSafe.getOrPut(key) {
                        blockHasMargin(zones, x / mcuSize, y / mcuSize, mcuSize)
                    }
                ) {
                    unsafe.hit(x, y)
                }
            }
        }
        if (outside.count > 0) {
            c.add(
                OracleCode.INVALID_PAIR,
                "the inputs differ outside r <= 1",
                outside.count,
                outside.firstX,
                outside.firstY,
            )
        }
        if (unsafe.count > 0) {
            c.add(
                OracleCode.INVALID_PAIR,
                "the inputs differ in a block without one block of margin inside r <= 1",
                unsafe.count,
                unsafe.firstX,
                unsafe.firstY,
            )
        }
        if (differing == 0) {
            c.add(OracleCode.PAIR_NOT_DIFFERENT, "the inputs are identical, the pair proves nothing")
        } else if (c.findings.isEmpty() && insideCount > 0 &&
            strongInside.toDouble() / insideCount < minDifferingFraction
        ) {
            c.add(
                OracleCode.PAIR_TOO_THIN,
                "the inputs differ by at least $minInputDifference levels in $strongInside of $insideCount pixels " +
                    "inside the masks (required share $minDifferingFraction)",
                differing,
            )
        }
        return c.report()
    }

    /**
     * Differential pair (PRIV-005): the inputs satisfy [pairPrecondition] and the outputs made with ONE fixed
     * mask plan are identical within [maxChannelDelta] (0 for lossless). With lossy inputs pass the DECODED inputs here
     * (their difference is then checked to lie inside r <= 1) and check the encoder-side pair with [pairPrecondition]
     * and `mcuSize`. A pair that breaks its precondition is reported instead of being judged.
     */
    public fun differentialPair(
        inputA: Raster,
        inputB: Raster,
        outputA: Raster,
        outputB: Raster,
        masks: List<MaskEllipse>,
        maxChannelDelta: Int = 0,
        minDifferingFraction: Double = DEFAULT_MIN_DIFFERING_FRACTION,
        minInputDifference: Int = DEFAULT_MIN_INPUT_DIFFERENCE,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, inputA, inputB) || !sameSize(c, inputA, outputA) ||
            !sameSize(c, inputA, outputB)
        ) {
            return c.report()
        }
        val precondition = pairPrecondition(
            inputA,
            inputB,
            masks,
            minDifferingFraction = minDifferingFraction,
            minInputDifference = minInputDifference,
        )
        if (!precondition.passed) return precondition
        val bad = Tally()
        forEachPixel(outputA) { x, y ->
            c.judged++
            if (channelDelta(outputA[x, y], outputB[x, y]) > maxChannelDelta) bad.hit(x, y)
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.DEPENDS_ON_MASKED_INPUT,
                "outputs differ although the inputs differ only inside the masks",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        return c.report()
    }

    /** 16 x 16 (or [mcuSize]) blocks usable to build lossy differential pairs: whole block and one block of margin inside. */
    public fun mcuSafeBlocks(width: Int, height: Int, masks: List<MaskEllipse>, mcuSize: Int = 16): List<Block> {
        val zones = Zones(width, height, masks)
        val blocks = mutableListOf<Block>()
        for (by in 0 until height / mcuSize) {
            for (bx in 0 until width / mcuSize) {
                if (blockHasMargin(zones, bx, by, mcuSize)) blocks.add(Block(bx * mcuSize, by * mcuSize, mcuSize))
            }
        }
        return blocks
    }

    /** Whole block and its one-block neighbourhood lie in the image and in [zone] (r <= 1 by default). */
    private fun blockHasMargin(zones: Zones, bx: Int, by: Int, size: Int, zone: Int = Zones.INSIDE): Boolean {
        val x0 = (bx - 1) * size
        val y0 = (by - 1) * size
        val x1 = (bx + 2) * size
        val y1 = (by + 2) * size
        if (x0 < 0 || y0 < 0 || x1 > zones.width || y1 > zones.height) return false
        for (y in y0 until y1) for (x in x0 until x1) if (zones.at(x, y) != zone) return false
        return true
    }

    /** 16 x 16 blocks beyond every soft edge with one block of margin there: a lossy encoder gives them the same pixels. */
    internal fun farBlocks(width: Int, height: Int, masks: List<MaskEllipse>, mcuSize: Int = 16): List<Block> {
        val zones = Zones(width, height, masks)
        val blocks = mutableListOf<Block>()
        for (by in 0 until height / mcuSize) {
            for (bx in 0 until width / mcuSize) {
                if (blockHasMargin(zones, bx, by, mcuSize, Zones.OUTSIDE)) {
                    blocks.add(Block(bx * mcuSize, by * mcuSize, mcuSize))
                }
            }
        }
        return blocks
    }

    /**
     * The masked area of [output] lies where and as large as [expected] says (scaled position, FR-FM-017/018). A pixel
     * is masked when it differs from [reference] (the source rendered at the output size by the test) by more than
     * [changeThreshold] in some channel. The centre may be off by [positionTolerancePx] and each half-width by
     * [semiAxisTolerancePx] (it must cover the soft edge, 0.10 x the semi-axis, and the lossy noise), and at least
     * [minCoverage] of the pixels inside the expected ellipse must have changed (position and size alone would accept
     * a ring over an untouched face). There are no default tolerances: fix them from a baseline before the test is
     * Ready and never widen them after a result.
     */
    public fun maskedRegionMatches(
        reference: Raster,
        output: Raster,
        expected: MaskEllipse,
        changeThreshold: Int,
        positionTolerancePx: Double,
        semiAxisTolerancePx: Double,
        minCoverage: Double,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, reference, output)) return c.report()
        var changedInside = 0L
        var insideTotal = 0L
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        forEachPixel(reference) { x, y ->
            c.judged++
            val inside = expected.radius(x, y) <= 1.0 - MaskEllipse.BOUNDARY_EPSILON
            if (inside) insideTotal++
            if (channelDelta(reference[x, y], output[x, y]) > changeThreshold) {
                if (inside) changedInside++
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
        if (maxX < 0) {
            c.add(OracleCode.NOTHING_CHECKED, "no pixel differs from the reference: the mask is missing")
            return c.report()
        }
        val measuredCx = (minX + maxX + 1) / 2.0
        val measuredCy = (minY + maxY + 1) / 2.0
        val measuredRx = (maxX - minX + 1) / 2.0
        val measuredRy = (maxY - minY + 1) / 2.0
        if (insideTotal == 0L) {
            c.add(OracleCode.NOTHING_CHECKED, "the expected ellipse lies outside the image")
        } else if (changedInside.toDouble() / insideTotal < minCoverage) {
            c.add(
                OracleCode.GEOMETRY_COVERAGE,
                "$changedInside of $insideTotal pixels of the expected ellipse changed (required share $minCoverage)",
            )
        }
        if (abs(measuredCx - expected.cx) > positionTolerancePx ||
            abs(measuredCy - expected.cy) > positionTolerancePx
        ) {
            c.add(
                OracleCode.GEOMETRY_POSITION,
                "masked area is centred at ($measuredCx, $measuredCy), expected (${expected.cx}, ${expected.cy})",
            )
        }
        if (abs(measuredRx - expected.rx) > semiAxisTolerancePx ||
            abs(measuredRy - expected.ry) > semiAxisTolerancePx
        ) {
            c.add(
                OracleCode.GEOMETRY_SIZE,
                "masked area has half-widths ($measuredRx, $measuredRy), expected (${expected.rx}, ${expected.ry})",
            )
        }
        return c.report()
    }

    /** After a typed error the caller's buffer is exactly as before (FR-018). */
    public fun unchangedAfterError(before: Raster, after: Raster): OracleReport {
        val c = Collector()
        if (!sameSize(c, before, after)) return c.report()
        val bad = Tally()
        forEachPixel(before) { x, y ->
            c.judged++
            if (before[x, y] != after[x, y]) bad.hit(x, y)
        }
        if (bad.count > 0) {
            c.add(
                OracleCode.BUFFER_CHANGED_AFTER_ERROR,
                "the buffer changed although the call failed",
                bad.count,
                bad.firstX,
                bad.firstY,
            )
        }
        return c.report()
    }

    /**
     * Whether the masks were applied, for the path under test ([expect]). A pixel with r <= 1 counts as applied when it
     * does not survive ([Fidelity]; for [Fidelity.Exact] it must also be fully opaque). With [Expect.ALL_APPLIED] every
     * mask must be fully applied: nothing applied is [OracleCode.NOT_MASKED], some applied is [OracleCode.PARTLY_MASKED]
     * (a skipped entry). With [Expect.NONE_APPLIED] (a call that raised a typed error) any applied pixel is
     * [OracleCode.PARTLY_MASKED]. Masks entirely outside the image are not judged.
     */
    public fun maskApplication(
        input: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        expect: Expect,
        fidelity: Fidelity,
        pair: Pair<Raster, Raster>? = null,
    ): OracleReport {
        val c = Collector()
        if (!sameSize(c, input, output)) return c.report()
        val eps = MaskEllipse.BOUNDARY_EPSILON
        val survival = Survival(input, output, fidelity, Zones(input.width, input.height, masks), pair)
        var fullyApplied = 0
        var notApplied = 0
        var partlyApplied = 0
        for (m in masks) {
            var inside = 0
            var applied = 0
            forEachPixel(input) { x, y ->
                if (m.radius(x, y) <= 1.0 - eps) {
                    inside++
                    val opaque = fidelity !is Fidelity.Exact || (output[x, y] ushr 24) == 0xFF
                    if (opaque && !survival.survives(x, y)) applied++
                }
            }
            if (inside == 0) continue
            c.judged += inside
            when (applied) {
                inside -> fullyApplied++
                0 -> notApplied++
                else -> partlyApplied++
            }
        }
        when (expect) {
            Expect.ALL_APPLIED -> {
                if (partlyApplied > 0 || (fullyApplied > 0 && notApplied > 0)) {
                    c.add(
                        OracleCode.PARTLY_MASKED,
                        "$fullyApplied mask(s) applied, $notApplied not applied, $partlyApplied partly applied",
                    )
                } else if (notApplied > 0) {
                    c.add(OracleCode.NOT_MASKED, "none of the $notApplied mask(s) was applied")
                }
            }

            Expect.NONE_APPLIED -> {
                if (fullyApplied > 0 || partlyApplied > 0) {
                    c.add(
                        OracleCode.PARTLY_MASKED,
                        "masking happened although the call failed ($fullyApplied full, $partlyApplied partial)",
                    )
                }
            }
        }
        c.requireJudged("maskApplication")
        return c.report()
    }

    /**
     * The mandatory set for "this output is masked" on one success path: every mask applied, no face pixel survives
     * ([fidelity]; for [Fidelity.Exact] judged on RGB, so forcing alpha to 255 is not masking), nothing changed beyond the
     * soft edge, for [Fidelity.Exact] ARGB outputs also the opaque core, the outward-only soft edge within the owner's band
     * and [independence]:
     * proof that the colours inside the masks do not depend on the input (a colour field the test knows, or a differential
     * pair with the same mask plan). Without it a face mixed with grey, or kept with alpha 255, would pass. An unmasked
     * image fails ([OracleCode.NOT_MASKED] and others).
     *
     * The evidence is validated, not trusted ([OracleCode.INVALID_EVIDENCE]): a pair must not share buffers with the
     * input or output (an in-place masker that was called twice on one buffer), and [Fidelity.Exact] allows no tolerance
     * (colour-field tolerance 0, pair delta 0). Prefer [requireMaskedBy], where the oracle builds the second input and
     * calls the masker itself. [libraryFeather] is the feather of the library configuration under test; when given, the
     * masks must have been built with the same value (a wider feather would judge a wider band). For [Fidelity.Lossy]
     * [input] and the pair's inputB are the rasters given to the pipeline BEFORE encoding, [output] and the pair's outputB
     * the decoded files, and the pair differs in whole 16 x 16 blocks ([mcuSafeBlocks]); the pair tolerance is at most
     * [LOSSY_MAX_PAIR_TOLERANCE]. A caller-supplied second output is trusted.
     */
    internal fun requireMasked(
        input: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        fidelity: Fidelity,
        independence: ColourIndependence,
        libraryFeather: Double? = null,
    ): OracleReport {
        val pair = (independence as? ColourIndependence.DifferentialPair)?.let { it.inputB to it.outputB }
        var report = validateEvidence(input, output, masks, fidelity, independence, libraryFeather) +
            maskApplication(input, output, masks, Expect.ALL_APPLIED, fidelity, pair) +
            insideDiffersFromSource(input, output, masks, fidelity, pair = pair)
        if (fidelity is Fidelity.Exact) {
            report += unchangedOutside(input, output, masks) + insideAlphaIs255(output, masks) +
                softEdgeOutwardOnly(input, output, masks)
        }
        report += when (independence) {
            is ColourIndependence.Field ->
                insideColourField(input, output, masks, independence.field, independence.tolerance)

            is ColourIndependence.DifferentialPair -> differentialPair(
                input,
                independence.inputB,
                output,
                independence.outputB,
                masks,
                independence.maxChannelDelta,
                independence.minDifferingFraction,
                independence.minInputDifference,
            )
        }
        return report
    }

    private fun validateEvidence(
        input: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        fidelity: Fidelity,
        independence: ColourIndependence,
        libraryFeather: Double?,
    ): OracleReport {
        val c = Collector()
        // Objects and pixel arrays are compared: two Raster wrappers can share one IntArray.
        if (output === input || output.argb === input.argb) {
            c.add(OracleCode.INVALID_EVIDENCE, "the output is the input object: nothing was copied or masked")
        }
        if (libraryFeather != null && masks.any { it.feather != libraryFeather }) {
            c.add(
                OracleCode.INVALID_EVIDENCE,
                "the masks use a feather other than the configuration under test ($libraryFeather)",
            )
        }
        when (independence) {
            is ColourIndependence.Field -> {
                if (fidelity is Fidelity.Exact && independence.tolerance != 0) {
                    c.add(
                        OracleCode.INVALID_EVIDENCE,
                        "exact fidelity needs a colour-field tolerance of 0, not ${independence.tolerance}",
                    )
                }
            }

            is ColourIndependence.DifferentialPair -> {
                val rasters = listOf(input, output, independence.inputB, independence.outputB)
                val shared = rasters.indices.any { i ->
                    (i + 1 until rasters.size).any { j ->
                        rasters[i] === rasters[j] ||
                            rasters[i].argb === rasters[j].argb
                    }
                }
                if (shared) {
                    c.add(
                        OracleCode.INVALID_EVIDENCE,
                        "the pair shares a buffer with the input or the output (called twice in place?)",
                    )
                }
                if (fidelity is Fidelity.Exact && independence.maxChannelDelta != 0) {
                    c.add(
                        OracleCode.INVALID_EVIDENCE,
                        "exact fidelity needs a pair tolerance of 0, not ${independence.maxChannelDelta}",
                    )
                }
                if (fidelity is Fidelity.Lossy && independence.maxChannelDelta > LOSSY_MAX_PAIR_TOLERANCE) {
                    c.add(
                        OracleCode.INVALID_EVIDENCE,
                        "a lossy pair tolerance may not exceed $LOSSY_MAX_PAIR_TOLERANCE levels, not ${independence.maxChannelDelta}",
                    )
                }
                val exact = fidelity is Fidelity.Exact
                val fractionFloor = if (exact) DEFAULT_MIN_DIFFERING_FRACTION else LOSSY_MIN_DIFFERING_FRACTION
                if (independence.minInputDifference < DEFAULT_MIN_INPUT_DIFFERENCE ||
                    independence.minDifferingFraction < fractionFloor
                ) {
                    c.add(
                        OracleCode.INVALID_EVIDENCE,
                        "the pair precondition may not be lowered below $DEFAULT_MIN_INPUT_DIFFERENCE levels in $fractionFloor of the pixels",
                    )
                }
            }
        }
        return c.report()
    }

    /**
     * The second input for a differential pair, built by the oracle: [input] with every pixel inside the masks changed by
     * 64 to 191 levels in every channel (alpha included, seeded), so the pair differs strongly in all of them.
     */
    public fun pairInputFor(input: Raster, masks: List<MaskEllipse>, seed: Long): Raster {
        val zones = Zones(input.width, input.height, masks)
        val random = java.util.Random(seed)
        val out = input.copy()
        forEachPixel(input) { x, y ->
            if (zones.at(x, y) == Zones.INSIDE) {
                var p = 0
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    val channel = (input[x, y] ushr shift) and 0xFF
                    p = p or (((channel + 64 + random.nextInt(128)) and 0xFF) shl shift)
                }
                out.argb[y * input.width + x] = p
            }
        }
        return out
    }

    /**
     * The masked verdict for a success path (the only public one for [Fidelity.Exact]): the oracle builds the evidence
     * itself and runs [masker] (input raster in, masked raster out; it may work in place) on copies:
     * - on [input] (A) and on [pairInputFor] (B, seeded): the pair proves the colours inside do not depend on the input;
     * - for [Fidelity.Exact] also on A with alpha 0 everywhere, so the bounded feather weight and the opacity core are
     *   always judged, and on A with seeded noise beyond every soft edge, so a masker lambda that ignores its argument
     *   is found ([OracleCode.MASKER_IGNORES_ITS_ARGUMENT]).
     * The results are copied right after each call, so an in-place masker or a shared buffer can not alias the pair.
     * [libraryFeather] is the feather of the library configuration under test (mandatory: a wider feather would judge a
     * wider band). [fidelity] must be [Fidelity.Exact] and [pairTolerance] 0: anything else is
     * [OracleCode.INVALID_EVIDENCE] before the masker runs (the built pair is not block-safe and a pair tolerance lets a
     * translucent face mix through). For lossy file mode use [requireMaskedLossyBy]. File mode is also proven on the ARGB
     * path of the same renderer: the weight rule needs alpha.
     */
    public fun requireMaskedBy(
        input: Raster,
        masks: List<MaskEllipse>,
        fidelity: Fidelity,
        masker: (Raster) -> Raster,
        libraryFeather: Double,
        seed: Long = 1L,
        pairTolerance: Int = 0,
    ): OracleReport {
        if (fidelity !is Fidelity.Exact || pairTolerance != 0) {
            return OracleReport(
                listOf(
                    OracleFinding(
                        OracleCode.INVALID_EVIDENCE,
                        "requireMaskedBy needs exact fidelity and a pair tolerance of 0 (lossy: requireMaskedLossyBy)",
                    ),
                ),
                0L,
            )
        }
        val original = input.copy()
        val inputB = pairInputFor(original, masks, seed)
        val outputA = masker(original.copy()).copy()
        val outputB = masker(inputB.copy()).copy()
        var report = requireMasked(
            original,
            outputA,
            masks,
            fidelity,
            ColourIndependence.DifferentialPair(inputB, outputB, pairTolerance),
            libraryFeather,
        )
        // Alpha 0 everywhere: (c2) and the opacity core are judged on every proof, whatever the input alpha.
        val zero = Raster(
            original.width,
            original.height,
            IntArray(original.argb.size) {
                original.argb[it] and
                    0xFFFFFF
            },
        )
        val outputZero = masker(zero.copy()).copy()
        report += softEdgeWeightBounds(zero, outputZero, masks) + insideOpaque(zero, outputZero, masks)
        // Noise beyond every soft edge: the output must follow it there.
        val noisy = outsideNoiseFor(original, masks, seed + 1)
        val outputNoisy = masker(noisy.copy()).copy()
        val followed = unchangedOutside(noisy, outputNoisy, masks)
        if (!followed.passed) {
            report += OracleReport(
                listOf(
                    OracleFinding(
                        OracleCode.MASKER_IGNORES_ITS_ARGUMENT,
                        "the output does not follow an input changed beyond the soft edges",
                    ),
                ),
                followed.pixelsJudged,
            )
        }
        return report
    }

    /**
     * Lossy file-mode pipelines, internal until a variant that runs the pipeline itself exists: a masked verdict from a
     * CALLER-SUPPLIED second input and output. The second output is TRUSTED to be the masker's result for [inputB]; a
     * copy of the first output, or one output file decoded twice, can not be detected from values. [input] and [inputB] are
     * the rasters given to the pipeline before encoding (the pair differs in whole 16 x 16 blocks, [mcuSafeBlocks]); [output]
     * and [outputB] are decoded files. Same checks as [requireMaskedBy] without the alpha-0 and noise calls (JPEG has no
     * alpha). The floor of [minDifferingFraction] is 0.10 and the pair delta is at most [LOSSY_MAX_PAIR_TOLERANCE].
     */
    internal fun requireMaskedLossy(
        input: Raster,
        output: Raster,
        masks: List<MaskEllipse>,
        fidelity: Fidelity.Lossy,
        inputB: Raster,
        outputB: Raster,
        libraryFeather: Double,
        pairTolerance: Int = 0,
        minDifferingFraction: Double = LOSSY_MIN_DIFFERING_FRACTION,
    ): OracleReport = requireMasked(
        input,
        output,
        masks,
        fidelity,
        ColourIndependence.DifferentialPair(inputB, outputB, pairTolerance, minDifferingFraction),
        libraryFeather,
    )

    /**
     * The masked verdict for a LOSSY file-mode pipeline (JPEG output), with evidence the oracle builds itself (the only
     * public lossy one). [masker] is the pipeline under test: encoded input in (PNG, written by the oracle), output file
     * bytes out, masking with ONE fixed mask plan that matches [masks]. [unmaskedPipeline] is the SAME pipeline with no
     * mask (for the library: the same call with an empty plan); its output is the reference against which surviving face
     * pixels and texture are judged ([Fidelity.Lossy] with the committed window parameters and [survivalThreshold]).
     *
     * The oracle builds, encodes and passes on copies:
     * - A, the [input] (alpha must be 255 everywhere: a JPEG output has no alpha);
     * - B, A with every pixel of the block-safe 16 x 16 blocks inside r <= 1 ([mcuSafeBlocks]) changed by 64 to 191 levels
     *   in R, G and B (seeded); B's output must equal A's within [pairTolerance] (at most [LOSSY_BY_MAX_PAIR_TOLERANCE] = 0,
     *   so exactly) and the blocks must hold at least [LOSSY_MIN_DIFFERING_FRACTION] of the pixels inside the masks
     *   ([OracleCode.PAIR_TOO_THIN]);
     * - N, A with seeded noise beyond every soft edge: in the 16 x 16 blocks beyond every soft edge with one block of margin
     *   ([farBlocks]) the masked output must follow N within the pair tolerance as the unmasked pipeline does, otherwise
     *   the masker does not process its argument ([OracleCode.MASKER_IGNORES_ITS_ARGUMENT]).
     * In those far blocks the masked and unmasked outputs of A must agree within [pairTolerance]
     * ([OracleCode.CHANGED_OUTSIDE_SOFT_EDGE]). The reference is validated as well ([OracleCode.INVALID_EVIDENCE]): inside
     * the masks it must follow its input (luma correlation at least [LOSSY_MIN_REFERENCE_CORRELATION] for A and for B),
     * it must follow N in the far blocks and be the same for A and B there. Masks too small for one safe block, or an
     * image without far blocks, are reported, never passed. A lossy verdict can not show that no single pixel survives
     * (holes smaller than the texture window); prove pixel-exact coverage with [requireMaskedBy].
     *
     * Residual floor: in the block-safe blocks a leak is shown when it changes at least one decoded output level of B
     * against A. A mask that keeps less than about 1/128 of the face colours changes no 8-bit level before encoding (half a
     * level at the smallest pair difference of 64) and can not be shown; the JPEG quantisation can hide a somewhat larger
     * share. A mask that keeps 1/32 (about 3 %) fails (control in the oracle's tests). Outside the block-safe blocks (the rim
     * and the parts of r <= 1 not covered by whole safe blocks) only lossy survival judges: translucent leaks of about 10 %
     * or less there are not shown. Every file-mode consumer test therefore pairs this verdict with [requireMaskedBy] (exact,
     * in-memory) for the same mask configuration.
     */
    public fun requireMaskedLossyBy(
        input: Raster,
        masks: List<MaskEllipse>,
        survivalThreshold: Int,
        masker: (ByteArray) -> ByteArray,
        unmaskedPipeline: (ByteArray) -> ByteArray,
        libraryFeather: Double,
        seed: Long = 1L,
        pairTolerance: Int = 0,
    ): OracleReport {
        val c = Collector()
        val original = input.copy()
        if (masks.isEmpty()) c.add(OracleCode.INVALID_EVIDENCE, "no masks: there is nothing to prove")
        val translucent = original.argb.count { (it ushr 24) != 0xFF }
        if (translucent > 0) {
            c.add(
                OracleCode.INVALID_EVIDENCE,
                "the input has pixels with alpha below 255; a JPEG can not show them",
                translucent,
            )
        }
        if (survivalThreshold < 0) c.add(OracleCode.INVALID_EVIDENCE, "the survival threshold may not be negative")
        if (pairTolerance !in 0..LOSSY_BY_MAX_PAIR_TOLERANCE) {
            c.add(OracleCode.INVALID_EVIDENCE, "a lossy pair tolerance above $LOSSY_BY_MAX_PAIR_TOLERANCE levels")
        }
        if (c.findings.isNotEmpty()) return c.report()
        val safe = mcuSafeBlocks(original.width, original.height, masks)
        if (safe.isEmpty()) {
            c.add(
                OracleCode.PAIR_TOO_THIN,
                "no 16 x 16 block with one block of margin lies inside r <= 1: the masks are too small",
            )
        }
        val far = farBlocks(original.width, original.height, masks)
        if (far.isEmpty()) {
            c.add(
                OracleCode.CHECK_CANNOT_DISCRIMINATE,
                "no 16 x 16 block with one block of margin lies beyond every soft edge: the image is too small for the controls",
            )
        }
        if (c.findings.isNotEmpty()) return c.report()

        val inputB = blockPairInputFor(original, safe, seed)
        val noisy = outsideNoiseFor(original, masks, seed + 1).let { n ->
            Raster(n.width, n.height, IntArray(n.argb.size) { n.argb[it] or OPAQUE })
        }
        val encoded = listOf(original, inputB, noisy).map { encodePng(it) }
        // Each call gets its own copy of the bytes; the results are decoded at once.
        val outputs = encoded.map { decodeFor(c, "masked output", masker(it.copyOf())) }
        val references = encoded.map { decodeFor(c, "unmasked output", unmaskedPipeline(it.copyOf())) }
        if (c.findings.isNotEmpty()) return c.report()
        val (outputA, outputB, outputN) = outputs.map { it!! }
        val (referenceA, referenceB, referenceN) = references.map { it!! }
        for (r in outputs + references) if (!sameSize(c, original, r!!)) return c.report()

        var report = requireMasked(
            original,
            outputA,
            masks,
            Fidelity.Lossy(referenceA, survivalThreshold),
            ColourIndependence.DifferentialPair(inputB, outputB, pairTolerance, LOSSY_MIN_DIFFERING_FRACTION),
            libraryFeather,
        )
        // The built pair itself: block-safe and strong enough (a self-check of the oracle's own evidence).
        report +=
            pairPrecondition(original, inputB, masks, mcuSize = 16, minDifferingFraction = LOSSY_MIN_DIFFERING_FRACTION)
        report += referenceFollows(original, referenceA, masks, "A") + referenceFollows(inputB, referenceB, masks, "B")

        val e = Collector()
        if (!farEqual(e, far, outputA, referenceA, pairTolerance)) {
            e.add(
                OracleCode.CHANGED_OUTSIDE_SOFT_EDGE,
                "beyond the soft edges the masked output differs from the unmasked pipeline's output",
            )
        }
        if (!farEqual(e, far, referenceA, referenceB, pairTolerance) ||
            farEqual(e, far, referenceN, referenceA, pairTolerance)
        ) {
            e.add(
                OracleCode.INVALID_EVIDENCE,
                "beyond the soft edges the unmasked pipeline does not follow its input",
            )
        }
        if (!farEqual(e, far, outputN, referenceN, pairTolerance) ||
            farEqual(e, far, outputN, outputA, pairTolerance)
        ) {
            e.add(
                OracleCode.MASKER_IGNORES_ITS_ARGUMENT,
                "the masked output does not follow an input changed beyond the soft edges",
            )
        }
        return report + e.report()
    }

    /** [input] with every pixel of [blocks] changed by 64 to 191 levels in R, G and B (seeded); alpha is kept. */
    internal fun blockPairInputFor(input: Raster, blocks: List<Block>, seed: Long): Raster {
        val random = java.util.Random(seed)
        val out = input.copy()
        for (b in blocks) {
            for (y in b.y until b.y + b.size) {
                for (x in b.x until b.x + b.size) {
                    var p = input[x, y] and OPAQUE
                    for (shift in intArrayOf(16, 8, 0)) {
                        val channel = (input[x, y] ushr shift) and 0xFF
                        p = p or (((channel + 64 + random.nextInt(128)) and 0xFF) shl shift)
                    }
                    out.argb[y * input.width + x] = p
                }
            }
        }
        return out
    }

    private const val OPAQUE: Int = -0x1000000

    private fun encodePng(raster: Raster): ByteArray {
        val image = java.awt.image.BufferedImage(raster.width, raster.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.argb, 0, raster.width)
        val out = java.io.ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    private fun decodeFor(c: Collector, what: String, bytes: ByteArray): Raster? = try {
        PixelDecoder.decode(bytes.copyOf())
    } catch (e: UndecodableImageException) {
        c.add(OracleCode.UNDECODABLE, "the $what can not be decoded: ${e.message}")
        null
    }

    /** True when [a] and [b] agree within [tolerance] in every pixel of [blocks]. */
    private fun farEqual(c: Collector, blocks: List<Block>, a: Raster, b: Raster, tolerance: Int): Boolean {
        for (block in blocks) {
            for (y in block.y until block.y + block.size) {
                for (x in block.x until block.x + block.size) {
                    c.judged++
                    if (channelDelta(a[x, y], b[x, y]) > tolerance) return false
                }
            }
        }
        return true
    }

    /** The unmasked pipeline's [reference] follows [input] inside the masks (luma correlation). */
    private fun referenceFollows(
        input: Raster,
        reference: Raster,
        masks: List<MaskEllipse>,
        name: String,
    ): OracleReport {
        val c = Collector()
        val zones = Zones(input.width, input.height, masks)
        var n = 0
        var si = 0.0
        var sr = 0.0
        var sii = 0.0
        var srr = 0.0
        var sir = 0.0
        forEachPixel(input) { x, y ->
            if (zones.at(x, y) == Zones.INSIDE) {
                val i = luma(input[x, y])
                val r = luma(reference[x, y])
                n++
                si += i
                sr += r
                sii += i * i
                srr += r * r
                sir += i * r
            }
        }
        c.judged = n.toLong()
        c.requireJudged("the reference check")
        if (n == 0) return c.report()
        val varI = sii / n - (si / n) * (si / n)
        val varR = srr / n - (sr / n) * (sr / n)
        if (varI <= 0.0) {
            c.add(OracleCode.CHECK_CANNOT_DISCRIMINATE, "input $name is flat inside the masks")
        } else if (varR <= 0.0 ||
            (sir / n - (si / n) * (sr / n)) / Math.sqrt(varI * varR) < LOSSY_MIN_REFERENCE_CORRELATION
        ) {
            c.add(
                OracleCode.INVALID_EVIDENCE,
                "the unmasked pipeline's output for input $name does not follow it inside the masks",
            )
        }
        return c.report()
    }

    private fun luma(p: Int): Double =
        0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)

    /** [input] with seeded noise in every pixel beyond all soft edges (the zone that must stay as given). */
    internal fun outsideNoiseFor(input: Raster, masks: List<MaskEllipse>, seed: Long): Raster {
        val zones = Zones(input.width, input.height, masks)
        val random = java.util.Random(seed)
        val out = input.copy()
        forEachPixel(input) { x, y ->
            if (zones.at(x, y) == Zones.OUTSIDE) out.argb[y * input.width + x] = random.nextInt()
        }
        return out
    }

    private fun sameSize(c: Collector, a: Raster, b: Raster): Boolean {
        if (a.width == b.width && a.height == b.height) return true
        c.add(OracleCode.SIZE_MISMATCH, "images are ${a.width} x ${a.height} and ${b.width} x ${b.height}")
        return false
    }

    private inline fun forEachPixel(r: Raster, action: (Int, Int) -> Unit) {
        for (y in 0 until r.height) for (x in 0 until r.width) action(x, y)
    }

    /** Largest difference of any of the four channels. */
    internal fun channelDelta(p: Int, q: Int): Int {
        var d = 0
        for (shift in intArrayOf(24, 16, 8, 0)) d = max(d, abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF)))
        return d
    }
}
