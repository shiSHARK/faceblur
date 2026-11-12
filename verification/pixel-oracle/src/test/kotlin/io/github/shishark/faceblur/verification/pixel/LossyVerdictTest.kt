// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `requireMaskedLossyBy`: the lossy masked verdict whose evidence the oracle builds itself. The pipeline under test is a
 * JPEG round trip of the reference masker (JDK encoder, quality 0.9); every negative control must fail with the named code.
 */
class LossyVerdictTest {
    private val width = 256
    private val height = 192
    private val masks = listOf(MaskEllipse.fromFaceBox(20.0, 20.0, 90.0, 100.0))
    private val input = PixelFixtures.noiseCanvas(width, height, seed = 61, constantAlpha = 255)
    private val grey = { _: Int, _: Int -> 0xFF808080.toInt() }

    private fun jpegOf(raster: Raster): ByteArray = PixelFixtures.jpeg(raster)

    /** Masks the decoded input with [m], then replaces the pixels with r <= 1 by [inside] (default: the grey fill). */
    private fun pipeline(
        m: List<MaskEllipse> = masks,
        inside: ((Int, Int, Int) -> Int)? = null,
    ): (ByteArray) -> ByteArray = { bytes ->
        val decoded = PixelDecoder.decode(bytes)
        val out = ReferenceMasker.apply(decoded, m, fill = grey)
        if (inside != null) {
            for (y in 0 until decoded.height) {
                for (x in 0 until decoded.width) {
                    if (m.any { it.radius(x, y) <= 1.0 }) out.argb[y * decoded.width + x] = inside(x, y, decoded[x, y])
                }
            }
        }
        jpegOf(out)
    }

    private val unmasked: (ByteArray) -> ByteArray = { bytes -> jpegOf(PixelDecoder.decode(bytes)) }

    private fun verdict(
        masker: (ByteArray) -> ByteArray = pipeline(),
        reference: (ByteArray) -> ByteArray = unmasked,
        source: Raster = input,
        m: List<MaskEllipse> = masks,
        // 0 as in the internal lossy test: on this noise face a threshold of 2 lets a few rim pixels equal the reference
        // by chance (MEASURED, reported as EQUALS_SOURCE_INSIDE), which is a false FAIL, never a false pass.
        survivalThreshold: Int = 0,
        feather: Double = 0.10,
        pairTolerance: Int = 0,
    ) = PixelOracle.requireMaskedLossyBy(
        source,
        m,
        survivalThreshold,
        masker,
        reference,
        feather,
        pairTolerance = pairTolerance,
    )

    private fun assertFailsWith(code: OracleCode, report: OracleReport, what: String) {
        assertTrue(!report.passed && code in report.codes, "$what: expected $code, got ${report.summary()}")
    }

    @Test
    fun anHonestLossyPipelinePassesAndItsEvidenceIsNotEmpty() {
        val report = verdict()
        assertTrue(report.passed, report.summary())
        assertTrue(report.pixelsJudged > 0)
        assertTrue(PixelOracle.mcuSafeBlocks(width, height, masks).isNotEmpty())
        assertTrue(PixelOracle.farBlocks(width, height, masks).isNotEmpty())
    }

    /** Keeps [share] of the source colour in every channel inside r <= 1, the rest is the grey fill (a translucent mask). */
    private fun faceMix(share: Int, of: Int) = pipeline { _, _, source ->
        var p = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val mixed = (share * ((source ushr shift) and 0xFF) + (of - share) * 0x80 + of / 2) / of
            p = p or (mixed shl shift)
        }
        p
    }

    @Test
    fun aFaceMixOfOneThirtySecondFailsAndNoPublicTolerancePassesIt() {
        // 1/32 (about 3 %) of the face colours: B's output moves by 2 to 6 levels against A's. At pair tolerance 0 the pair
        // sees it; a public tolerance above 0 is invalid evidence, so the mix can never hide inside a tolerance.
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, verdict(masker = faceMix(1, 32)), "1/32 mix")
        for (tolerance in 1..PixelOracle.LOSSY_MAX_PAIR_TOLERANCE) {
            assertFailsWith(
                OracleCode.INVALID_EVIDENCE,
                verdict(masker = faceMix(1, 32), pairTolerance = tolerance),
                "1/32 mix at tolerance $tolerance",
            )
        }
        assertEquals(0, PixelOracle.LOSSY_BY_MAX_PAIR_TOLERANCE)
    }

    @Test
    fun requireMaskedByHasNoLossyOrTolerantRoute() {
        // The raster entry builds a pair that is not block-safe and has no noise control for a JPEG round trip: a lossy
        // fidelity or a pair tolerance there is invalid evidence, reported before the masker runs.
        var calls = 0
        val mix: (Raster) -> Raster = { raster ->
            calls++
            PixelDecoder.decode(faceMix(1, 32)(PixelFixtures.png(raster)))
        }
        val reference = PixelDecoder.decode(unmasked(PixelFixtures.png(input)))
        for ((fidelity, tolerance) in listOf(
            Fidelity.Lossy(reference, 0) to 0,
            Fidelity.Lossy(reference, 0) to PixelOracle.LOSSY_MAX_PAIR_TOLERANCE,
            Fidelity.Exact to 1,
        )) {
            val report = PixelOracle.requireMaskedBy(input, masks, fidelity, mix, 0.10, pairTolerance = tolerance)
            assertFailsWith(OracleCode.INVALID_EVIDENCE, report, "$fidelity, tolerance $tolerance")
        }
        assertEquals(0, calls, "the masker ran on invalid evidence")
    }

    @Test
    fun theBuiltPairDiffersInWholeSafeBlocksOnlyAndStrongly() {
        val blocks = PixelOracle.mcuSafeBlocks(width, height, masks)
        val b = PixelOracle.blockPairInputFor(input, blocks, seed = 5)
        val inBlock = { x: Int, y: Int ->
            blocks.any {
                x >= it.x && x < it.x + it.size && y >= it.y &&
                    y < it.y + it.size
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = PixelOracle.channelDelta(input[x, y], b[x, y])
                if (inBlock(x, y)) assertTrue(d >= 64, "($x, $y) differs by $d") else assertEquals(0, d, "($x, $y)")
                assertEquals(0xFF, b[x, y] ushr 24, "alpha kept")
            }
        }
        assertTrue(PixelOracle.pairPrecondition(input, b, masks, mcuSize = 16, minDifferingFraction = 0.10).passed)
    }

    // ---- negative controls: maskers that do not mask, or do not mask what they are given

    @Test
    fun anUnmaskedPipelineFails() = assertFailsWith(OracleCode.NOT_MASKED, verdict(masker = unmasked), "unmasked")

    @Test
    fun returningTheInputFileFails() =
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, verdict(masker = { it }), "input bytes returned")

    @Test
    fun aMaskerThatReturnsItsFirstOutputAgainFails() {
        var first: ByteArray? = null
        val shared: (ByteArray) -> ByteArray = { bytes -> first ?: pipeline()(bytes).also { first = it } }
        assertFailsWith(OracleCode.MASKER_IGNORES_ITS_ARGUMENT, verdict(masker = shared), "shared output file")
    }

    @Test
    fun aMaskerThatMasksACapturedImageFails() {
        val captured = PixelFixtures.png(input)
        assertFailsWith(
            OracleCode.MASKER_IGNORES_ITS_ARGUMENT,
            verdict(masker = { pipeline()(captured) }),
            "captured input",
        )
    }

    @Test
    fun aFiveByFiveFaceHoleFails() {
        val cx = masks[0].cx.toInt()
        val cy = masks[0].cy.toInt()
        val hole = pipeline { x, y, source -> if (abs(x - cx) <= 2 && abs(y - cy) <= 2) source else 0xFF808080.toInt() }
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, verdict(masker = hole), "5 x 5 hole")
    }

    @Test
    fun aHalfTranslucentFaceFails() {
        val mix = pipeline { _, _, source ->
            var p = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) p = p or ((((source ushr shift) and 0xFF) + 0x80) / 2 shl shift)
            p
        }
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, verdict(masker = mix), "50 % mix")
    }

    @Test
    fun aMaskShiftedByOneBlockFails() {
        val shifted = listOf(MaskEllipse.fromFaceBox(36.0, 20.0, 90.0, 100.0))
        assertFailsWith(OracleCode.PARTLY_MASKED, verdict(masker = pipeline(shifted)), "mask 16 px off")
    }

    @Test
    fun anUndecodableOutputFails() =
        assertFailsWith(OracleCode.UNDECODABLE, verdict(masker = { byteArrayOf(1, 2, 3) }), "garbage output")

    // ---- the reference is validated, not trusted

    @Test
    fun aReferenceOfAnotherImageIsInvalidEvidence() {
        val other = jpegOf(PixelFixtures.noiseCanvas(width, height, seed = 62, constantAlpha = 255))
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(reference = { other }), "unrelated reference")
    }

    @Test
    fun aReferenceThatIsWrongOnlyInsideTheMasksIsInvalidEvidence() {
        // Right beyond the soft edges, unrelated noise inside: only the correlation rule can see it.
        val other = PixelFixtures.noiseCanvas(width, height, seed = 64, constantAlpha = 255)
        val wrongInside: (ByteArray) -> ByteArray = { bytes ->
            val d = PixelDecoder.decode(bytes)
            jpegOf(
                Raster.of(d.width, d.height) { x, y ->
                    if (masks.any { it.radius(x, y) <= 1.0 }) other[x, y] else d[x, y]
                },
            )
        }
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(reference = wrongInside), "reference wrong inside")
    }

    @Test
    fun aReferenceThatIgnoresItsArgumentIsInvalidEvidence() {
        val captured = PixelFixtures.png(input)
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(reference = { unmasked(captured) }), "captured reference")
    }

    @Test
    fun aReferenceThatIgnoresItsArgumentBeyondTheSoftEdgesIsInvalidEvidence() {
        // Right inside the masks, the captured image beyond them: only the far-block rule of the reference can see it.
        val wrongOutside: (ByteArray) -> ByteArray = { bytes ->
            val d = PixelDecoder.decode(bytes)
            val zoneOf = { x: Int, y: Int -> masks.any { it.radius(x, y) <= 1.0 + it.softEdgeWidth } }
            jpegOf(Raster.of(d.width, d.height) { x, y -> if (zoneOf(x, y)) d[x, y] else input[x, y] })
        }
        assertFailsWith(
            OracleCode.INVALID_EVIDENCE,
            verdict(reference = wrongOutside),
            "reference ignores its argument outside",
        )
    }

    @Test
    fun aBrighterReferenceDoesNotMatchThePipelineBeyondTheSoftEdges() {
        val brighter: (ByteArray) -> ByteArray = { bytes ->
            val d = PixelDecoder.decode(bytes)
            jpegOf(Raster.of(d.width, d.height) { x, y -> d[x, y] or 0x00404040 })
        }
        assertFailsWith(OracleCode.CHANGED_OUTSIDE_SOFT_EDGE, verdict(reference = brighter), "brighter reference")
    }

    @Test
    fun aFaceKeptByBothPipelinesFails() {
        // Both leave the source inside r <= 1 (only the soft edge is filled): the reference is valid, the face survives.
        val kept = pipeline { _, _, source -> source }
        val report = verdict(masker = kept, reference = kept)
        assertFailsWith(OracleCode.NOT_MASKED, report, "face kept by both")
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, report, "face kept by both")
    }

    @Test
    fun aMaskedReferenceIsInvalidEvidenceAndNeverHelpsAnUnmaskedFace() {
        // A masked "unmasked" reference does not follow its input inside the masks; the unmasked face still fails the pair.
        val report = verdict(masker = unmasked, reference = pipeline())
        assertFailsWith(OracleCode.INVALID_EVIDENCE, report, "masked reference")
        assertFailsWith(OracleCode.DEPENDS_ON_MASKED_INPUT, report, "masked reference")
    }

    // ---- floors and preconditions

    @Test
    fun theEvidenceConditionsCanNotBeLoweredOrSkipped() {
        val translucent = Raster.of(width, height) { x, y -> input[x, y] and 0x7FFFFFFF }
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(source = translucent), "alpha below 255")
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(pairTolerance = 1), "tolerance above the ceiling 0")
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(pairTolerance = -1), "negative tolerance")
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(survivalThreshold = -1), "negative threshold")
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(m = emptyList()), "no masks")
        assertFailsWith(OracleCode.INVALID_EVIDENCE, verdict(feather = 0.2), "feather of another configuration")
        val tiny = listOf(MaskEllipse.fromFaceBox(40.0, 40.0, 20.0, 20.0))
        assertFailsWith(OracleCode.PAIR_TOO_THIN, verdict(m = tiny, masker = pipeline(tiny)), "masks too small")
        val small = PixelFixtures.noiseCanvas(160, 120, seed = 63, constantAlpha = 255)
        assertFailsWith(OracleCode.CHECK_CANNOT_DISCRIMINATE, verdict(source = small), "no block beyond the soft edges")
        assertEquals(0.9, PixelOracle.LOSSY_MIN_REFERENCE_CORRELATION)
    }

    @Test
    fun invalidEvidenceIsReportedBeforeThePipelineRuns() {
        var calls = 0
        val counting: (ByteArray) -> ByteArray = { bytes ->
            calls++
            pipeline()(bytes)
        }
        val ceiling = PixelOracle.LOSSY_MAX_PAIR_TOLERANCE
        for (tolerance in listOf(-1, 1, ceiling, ceiling + 1)) {
            assertFailsWith(
                OracleCode.INVALID_EVIDENCE,
                verdict(masker = counting, pairTolerance = tolerance),
                "tolerance $tolerance",
            )
        }
        assertEquals(0, calls, "the masker ran on invalid evidence")
    }

    @Test
    fun theVerdictIsDeterministic() {
        assertEquals(verdict().summary(), verdict().summary())
        assertEquals(verdict(masker = unmasked).summary(), verdict(masker = unmasked).summary())
    }
}
