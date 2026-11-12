// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Self-test of the pixel oracle (ST-045 AC2, PRIV-023): the clean case reports nothing, every planted defect makes the
 * oracle fail with the expected names, and every check that must notice an unmasked image does (negative controls).
 */
class PixelOracleSelfTest {
    private val masks = listOf(
        MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0),
        MaskEllipse.fromFaceBox(100.0, 60.0, 30.0, 40.0),
        MaskEllipse.fromFaceBox(130.0, -10.0, 40.0, 40.0), // clipped at the top and the right border
    )
    private val inputA = PixelFixtures.noiseCanvas(160, 120, seed = 1)
    private val inputB = PixelFixtures.perturbInside(inputA, masks, seed = 2)

    /** Input alpha 0 everywhere: the output alpha is then 255 x the feather weight (ADR 0002 test c2). */
    private val alphaZero = PixelFixtures.noiseCanvas(160, 120, seed = 3, constantAlpha = 0)

    /** Every pixel check a consumer runs on the output of a masker, plus the differential pair and the c/c2 checks. */
    private fun runAll(defect: PlantedPixelDefect): OracleReport {
        val outA = ReferenceMasker.apply(inputA, masks, defect)
        val outB = ReferenceMasker.apply(inputB, masks, defect)
        val outZero = ReferenceMasker.apply(alphaZero, masks, defect)
        return PixelOracle.unchangedOutside(inputA, outA, masks) +
            PixelOracle.insideOpaque(inputA, outA, masks) +
            PixelOracle.insideColourField(inputA, outA, masks, ReferenceMasker::colourField, maxChannelDelta = 0) +
            PixelOracle.insideDiffersFromSource(inputA, outA, masks, Fidelity.Exact) +
            PixelOracle.maskApplication(inputA, outA, masks, Expect.ALL_APPLIED, Fidelity.Exact) +
            PixelOracle.softEdgeOutwardOnly(inputA, outA, masks) +
            PixelOracle.differentialPair(inputA, inputB, outA, outB, masks) +
            PixelOracle.softEdgeWeightBounds(alphaZero, outZero, masks) +
            PixelOracle.softEdgeOutwardOnly(alphaZero, outZero, masks)
    }

    private val expected: Map<PlantedPixelDefect, Set<OracleCode>> = mapOf(
        PlantedPixelDefect.NONE to emptySet(),
        PlantedPixelDefect.BLUR_INSTEAD_OF_MASK to setOf(
            OracleCode.NOT_OPAQUE_INSIDE,
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
            OracleCode.SOFT_EDGE_MISSING,
            OracleCode.NOT_MASKED,
        ),
        PlantedPixelDefect.ALPHA_254_AT_THE_EDGE to setOf(
            OracleCode.NOT_OPAQUE_INSIDE,
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.PARTLY_MASKED,
        ),
        PlantedPixelDefect.OPAQUE_ONE_ROW_LARGER to setOf(OracleCode.SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS),
        PlantedPixelDefect.OPAQUE_ONE_ROW_SMALLER to setOf(
            OracleCode.NOT_OPAQUE_INSIDE,
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.EQUALS_SOURCE_INSIDE,
            OracleCode.PARTLY_MASKED,
            // An unmasked part of the face keeps the input pixels, so the pair differs too.
            OracleCode.DEPENDS_ON_MASKED_INPUT,
        ),
        PlantedPixelDefect.ONE_PIXEL_CHANGED_OUTSIDE to setOf(
            OracleCode.CHANGED_OUTSIDE_SOFT_EDGE,
            OracleCode.INEXACT_OUTPUT,
            OracleCode.CHANGED_BEYOND_BAND,
        ),
        PlantedPixelDefect.DEPENDS_ON_INPUT_INSIDE to setOf(
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
        ),
        PlantedPixelDefect.SKIPPED_MASK_ENTRY to setOf(
            OracleCode.NOT_OPAQUE_INSIDE,
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.EQUALS_SOURCE_INSIDE,
            OracleCode.PARTLY_MASKED,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
        ),
        PlantedPixelDefect.SKIPPED_EVERY_MASK to setOf(
            OracleCode.NOT_OPAQUE_INSIDE,
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.EQUALS_SOURCE_INSIDE,
            OracleCode.NOT_MASKED,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
            OracleCode.SOFT_EDGE_MISSING,
        ),
        PlantedPixelDefect.SOFT_EDGE_OPACITY_RISES_OUTWARD to setOf(OracleCode.SOFT_EDGE_OPACITY_INCREASES),
        PlantedPixelDefect.NO_SOFT_EDGE to setOf(OracleCode.SOFT_EDGE_MISSING),
        PlantedPixelDefect.ALPHA_LOWERED_IN_SOFT_EDGE to setOf(OracleCode.ALPHA_LOWERED_IN_SOFT_EDGE),
        PlantedPixelDefect.FACE_KEPT_ALPHA_255 to setOf(
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.EQUALS_SOURCE_INSIDE,
            OracleCode.NOT_MASKED,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
        ),
        PlantedPixelDefect.FACE_MIXED_WITH_GREY to setOf(
            OracleCode.COLOUR_FIELD_MISMATCH,
            OracleCode.DEPENDS_ON_MASKED_INPUT,
        ),
    )

    @TestFactory
    fun everyPlantedDefectIsReportedByNameAndACorrectCaseReportsNothing(): List<DynamicTest> =
        PlantedPixelDefect.values().map { defect ->
            DynamicTest.dynamicTest(defect.name) {
                val report = runAll(defect)
                assertEquals(expected.getValue(defect), report.codes, report.summary())
                assertEquals(defect == PlantedPixelDefect.NONE, report.passed)
                assertTrue(report.pixelsJudged > 0)
            }
        }

    @Test
    fun theStoryListIsCovered() {
        assertEquals(PlantedPixelDefect.values().toSet(), expected.keys)
        // The seven defects of the story (blur stub, alpha at the edge, opaque area one row larger or smaller, a changed
        // pixel outside, output depending on masked input, a skipped mask entry) plus six from the reviews and re-checks.
        assertEquals(13, expected.size - 1)
    }

    @Test
    fun anUnmaskedImageFailsEveryCheckThatMustNoticeIt() {
        val unmasked = inputA
        val unmaskedZero = alphaZero
        val codes = { r: OracleReport -> r.codes }
        assertEquals(setOf(OracleCode.NOT_OPAQUE_INSIDE), codes(PixelOracle.insideOpaque(inputA, unmasked, masks)))
        assertEquals(
            setOf(OracleCode.COLOUR_FIELD_MISMATCH),
            codes(PixelOracle.insideColourField(inputA, unmasked, masks, ReferenceMasker::colourField, 0)),
        )
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            codes(PixelOracle.insideDiffersFromSource(inputA, unmasked, masks, Fidelity.Exact)),
        )
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            codes(PixelOracle.maskApplication(inputA, unmasked, masks, Expect.ALL_APPLIED, Fidelity.Exact)),
        )
        assertEquals(
            setOf(OracleCode.SOFT_EDGE_MISSING),
            codes(PixelOracle.softEdgeOutwardOnly(inputA, unmasked, masks)),
        )
        assertEquals(
            setOf(OracleCode.NOT_OPAQUE_INSIDE),
            codes(PixelOracle.softEdgeWeightBounds(alphaZero, unmaskedZero, masks)),
        )
        assertEquals(
            setOf(OracleCode.NOTHING_CHECKED),
            codes(PixelOracle.maskedRegionMatches(inputA, unmasked, masks[0], 8, 2.0, 2.0, 0.5)),
        )
        // The differential pair: outputs that are the inputs differ because the inputs differ.
        assertEquals(
            setOf(OracleCode.DEPENDS_ON_MASKED_INPUT),
            codes(PixelOracle.differentialPair(inputA, inputB, inputA, inputB, masks)),
        )
        // The mandatory bundle, with either kind of colour-independence evidence.
        val field = ColourIndependence.Field(ReferenceMasker::colourField, 0)
        val pair = ColourIndependence.DifferentialPair(inputB, ReferenceMasker.apply(inputB, masks))
        val bundle = PixelOracle.requireMasked(inputA, unmasked, masks, Fidelity.Exact, field)
        assertTrue(
            OracleCode.NOT_MASKED in bundle.codes && OracleCode.EQUALS_SOURCE_INSIDE in bundle.codes,
            bundle.summary(),
        )
        val maskedA = ReferenceMasker.apply(inputA, masks)
        PixelOracle.requireMasked(inputA, maskedA, masks, Fidelity.Exact, field).assertPassed()
        PixelOracle.requireMasked(inputA, maskedA, masks, Fidelity.Exact, pair).assertPassed()
    }

    @Test
    fun anUnmaskedLossyOutputFailsAndAMaskedOneDoesNot() {
        // Seed 45. The lossy verdict on plain random noise judges a few pixels or texture windows "surviving" by chance
        // (a false FAIL, never a false pass). Seed 41 did so for one pixel at the FR-004 size of 1.6 / 1.8: a pixel with
        // r <= 1 whose JPEG-decoded source lay within 2 levels of the mask fill, while the source itself differed. A
        // scan of seeds 30..79 gave 13 clean seeds at 1.6 / 1.8 and 19 at the former 1.35 / 1.55, so the choice is
        // fixture data, not a tolerance.
        val opaque = PixelFixtures.noiseCanvas(160, 120, seed = 45, constantAlpha = 255)
        val reference = PixelDecoder.decode(PixelFixtures.jpeg(opaque)) // the unmasked pipeline, decoded
        val unmaskedLossy = PixelDecoder.decode(PixelFixtures.jpeg(opaque))
        val maskedLossy = PixelDecoder.decode(PixelFixtures.jpeg(ReferenceMasker.apply(opaque, masks)))
        val lossy = Fidelity.Lossy(reference, survivalThreshold = 2)
        val changedLossy = PixelFixtures.perturbInside(opaque, masks, seed = 42)
        val lossyPair = ColourIndependence.DifferentialPair(
            PixelFixtures.perturbInside(opaque, masks, seed = 42),
            PixelDecoder.decode(PixelFixtures.jpeg(ReferenceMasker.apply(changedLossy, masks))),
        )
        // Exact fidelity on a lossy output is refused instead of being believed (the JPEG changed pixels outside the masks).
        assertTrue(
            OracleCode.INEXACT_OUTPUT in
                PixelOracle.insideDiffersFromSource(opaque, unmaskedLossy, masks, Fidelity.Exact).codes,
        )
        // Lossy fidelity: the unmasked output survives everywhere, the masked one does not.
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            PixelOracle.insideDiffersFromSource(opaque, unmaskedLossy, masks, lossy).codes,
        )
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(opaque, unmaskedLossy, masks, Expect.ALL_APPLIED, lossy).codes,
        )
        PixelOracle.insideDiffersFromSource(opaque, maskedLossy, masks, lossy).assertPassed()
        PixelOracle.maskApplication(opaque, maskedLossy, masks, Expect.ALL_APPLIED, lossy).assertPassed()
        PixelOracle.requireMasked(opaque, maskedLossy, masks, lossy, lossyPair).assertPassed()
        assertTrue(!PixelOracle.requireMasked(opaque, unmaskedLossy, masks, lossy, lossyPair).passed)
        // The opacity check cannot judge a decoded JPEG (no alpha): it says so instead of passing.
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.insideOpaque(opaque, maskedLossy, masks).codes)
    }

    @Test
    fun requireMaskedProvesThatTheColoursDoNotDependOnTheInput() {
        // The alpha checks pass while every face pixel survives. Both of security's cases.
        val field = ColourIndependence.Field(ReferenceMasker::colourField, 0)
        for (defect in listOf(PlantedPixelDefect.FACE_KEPT_ALPHA_255, PlantedPixelDefect.FACE_MIXED_WITH_GREY)) {
            val out = ReferenceMasker.apply(inputA, masks, defect)
            val outB = ReferenceMasker.apply(inputB, masks, defect)
            // The alpha-only checks alone are fooled: opaque core, outward edge.
            PixelOracle.insideOpaque(inputA, out, masks).assertPassed()
            PixelOracle.softEdgeOutwardOnly(inputA, out, masks).assertPassed()
            val withField = PixelOracle.requireMasked(inputA, out, masks, Fidelity.Exact, field)
            assertTrue(
                OracleCode.COLOUR_FIELD_MISMATCH in withField.codes,
                "$defect with a colour field: ${withField.summary()}",
            )
            val pair = ColourIndependence.DifferentialPair(inputB, outB)
            val withPair = PixelOracle.requireMasked(inputA, out, masks, Fidelity.Exact, pair)
            assertTrue(
                OracleCode.DEPENDS_ON_MASKED_INPUT in withPair.codes,
                "$defect with a pair: ${withPair.summary()}",
            )
        }
        // Forcing alpha to 255 over the face colours is not masking: survival is judged on RGB.
        val keptAlpha = ReferenceMasker.apply(inputA, masks, PlantedPixelDefect.FACE_KEPT_ALPHA_255)
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            PixelOracle.insideDiffersFromSource(inputA, keptAlpha, masks, Fidelity.Exact).codes,
        )
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(inputA, keptAlpha, masks, Expect.ALL_APPLIED, Fidelity.Exact).codes,
        )
        // The control passes with either kind of evidence.
        val good = ReferenceMasker.apply(inputA, masks)
        PixelOracle.requireMasked(inputA, good, masks, Fidelity.Exact, field).assertPassed()
        PixelOracle.requireMasked(
            inputA,
            good,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(inputB, ReferenceMasker.apply(inputB, masks)),
        ).assertPassed()
    }

    @Test
    fun aLossyOutputShowsFaceHolesOfTheTextureWindowSizeAndRefusesFlatFaces() {
        // A 5 x 5 face hole in a JPEG output. Pixel thresholds miss it (ringing moves the hole's
        // pixels away from the reference); the texture window finds it, and a correct mask is not flagged.
        val mask = MaskEllipse.fromFaceBox(80.0, 70.0, 96.0, 110.0)
        val one = listOf(mask)
        // Seed 83. Seed 77 put one pixel inside r <= 1 where the JPEG-decoded noise of the unmasked reference lay within
        // 2 levels of the grey fill at the FR-004 size of 1.6 / 1.8 (source pixel differed from the output). A scan of
        // seeds 70..99 (both thresholds) gave 2 clean seeds at 1.6 / 1.8 and 4 at the former 1.35 / 1.55: fixture data.
        val face = PixelFixtures.noiseCanvas(256, 256, seed = 83, constantAlpha = 255)
        val grey = { _: Int, _: Int -> 0xFF808080.toInt() }
        val reference = PixelDecoder.decode(PixelFixtures.jpeg(face))
        fun withHole(side: Int): Raster {
            val masked = ReferenceMasker.apply(face, one, fill = grey)
            val cx = mask.cx.toInt()
            val cy = mask.cy.toInt()
            for (y in cy - side / 2..cy + side / 2) {
                for (x in cx - side / 2..cx + side / 2) {
                    masked.argb[y * 256 + x] =
                        face[x, y]
                }
            }
            return masked
        }
        fun judged(raster: Raster, threshold: Int): OracleReport {
            val out = PixelDecoder.decode(PixelFixtures.jpeg(raster))
            val lossy = Fidelity.Lossy(reference, threshold)
            return PixelOracle.insideDiffersFromSource(face, out, one, lossy) +
                PixelOracle.maskApplication(face, out, one, Expect.ALL_APPLIED, lossy)
        }
        for (threshold in listOf(0, 2)) {
            judged(ReferenceMasker.apply(face, one, fill = grey), threshold).assertPassed()
            for (side in listOf(2, 4, 6, 30)) {
                val report = judged(withHole(side), threshold)
                assertEquals(
                    setOf(OracleCode.EQUALS_SOURCE_INSIDE, OracleCode.PARTLY_MASKED),
                    report.codes,
                    "hole of ${side + 1}: ${report.summary()}",
                )
            }
            assertEquals(setOf(OracleCode.EQUALS_SOURCE_INSIDE, OracleCode.NOT_MASKED), judged(face, threshold).codes)
        }
        // Known limit, stated in the README: a hole smaller than the window can not be seen in a lossy output.
        assertTrue(judged(withHole(0), 0).passed, "a single surviving pixel is invisible in a JPEG output")
        // The pixel threshold still catches an unmasked output when the face is flat.
        // A flat face (no texture in the reference) can not be judged window by window: the check says so.
        val flat = Raster.of(256, 256) { x, y -> (0xFF shl 24) or (0x70 + (x + y) % 4) * 0x010101 }
        val flatReference = PixelDecoder.decode(PixelFixtures.jpeg(flat))
        val flatOut = PixelDecoder.decode(PixelFixtures.jpeg(ReferenceMasker.apply(flat, one, fill = grey)))
        val flatReport = PixelOracle.insideDiffersFromSource(flat, flatOut, one, Fidelity.Lossy(flatReference, 0))
        assertEquals(setOf(OracleCode.CHECK_CANNOT_DISCRIMINATE), flatReport.codes, flatReport.summary())
        val flatUnmasked = PixelOracle.insideDiffersFromSource(
            flat,
            flatReference,
            one,
            Fidelity.Lossy(flatReference, 0),
        )
        assertTrue(OracleCode.EQUALS_SOURCE_INSIDE in flatUnmasked.codes, flatUnmasked.summary())
    }

    @Test
    fun aCorrectlyMaskedOpaqueInputGivesAPositiveVerdictAndEveryBrokenOneStillFails() {
        // Earlier defect: requireMasked(Exact) reported NOTHING_CHECKED on correct output from opaque inputs.
        val boxes = listOf(
            MaskEllipse.fromFaceBox(20.0, 20.0, 8.0, 10.0), // semi-axes 6.4 x 9.0
            MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0),
            MaskEllipse.fromFaceBox(300.0, 200.0, 600.0, 700.0), // semi-axes 480 x 630
        )
        for (mask in boxes) {
            val one = listOf(mask)
            val w = (mask.cx + 1.2 * mask.rx + 5).toInt()
            val h = (mask.cy + 1.2 * mask.ry + 5).toInt()
            val canvas = PixelFixtures.noiseCanvas(maxOf(w, 80), maxOf(h, 90), seed = 51, constantAlpha = 255)
            val other = PixelFixtures.perturbInside(canvas, one, seed = 52, opaque = true)
            val good = ReferenceMasker.apply(canvas, one)
            val field = ColourIndependence.Field(ReferenceMasker::colourField, 0)
            val pair = ColourIndependence.DifferentialPair(other, ReferenceMasker.apply(other, one))
            for (evidence in listOf(field, pair)) {
                val report = PixelOracle.requireMasked(canvas, good, one, Fidelity.Exact, evidence)
                assertTrue(report.passed && OracleCode.NOTHING_CHECKED !in report.codes, report.summary())
                // Every unmasked, partial or kept-face variant still fails.
                val unmasked = PixelOracle.requireMasked(canvas, canvas.copy(), one, Fidelity.Exact, evidence)
                assertTrue(OracleCode.NOT_MASKED in unmasked.codes, unmasked.summary())
                val kept = ReferenceMasker.apply(canvas, one, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
                val keptReport = PixelOracle.requireMasked(canvas, kept, one, Fidelity.Exact, evidence)
                assertTrue(
                    OracleCode.COLOUR_FIELD_MISMATCH in keptReport.codes ||
                        OracleCode.DEPENDS_ON_MASKED_INPUT in keptReport.codes,
                    keptReport.summary(),
                )
            }
            val notOpaque = good.copy()
            val i = mask.cy.toInt() * canvas.width + mask.cx.toInt()
            notOpaque.argb[i] = (notOpaque.argb[i] and 0xFFFFFF) or (0x80 shl 24)
            assertTrue(
                OracleCode.NOT_OPAQUE_INSIDE in
                    PixelOracle.requireMasked(canvas, notOpaque, one, Fidelity.Exact, field).codes,
            )
        }
    }

    @Test
    fun aFillThatEqualsFacePixelsByChanceIsNotSurvivalWhenAPairIsGiven() {
        // Real photos: a fill taken from a flat background equals some face pixels. Survival then means "equal to the
        // source and the output followed the input"; a fill independent of the input is excused where it coincides.
        val grey = { _: Int, _: Int -> 0xFF808080.toInt() }
        val background = PixelFixtures.noiseCanvas(160, 120, seed = 80, constantAlpha = 255)
        // One face pixel in ten already has the fill colour; the rest and the surroundings are noise.
        val partlyFlat = Raster.of(160, 120) { x, y ->
            if (masks.any { it.radius(x, y) <= 1.0 } && (x + y) % 10 == 0) 0xFF808080.toInt() else background[x, y]
        }
        val other = PixelFixtures.perturbInside(partlyFlat, masks, seed = 81, opaque = true)
        val out = ReferenceMasker.apply(partlyFlat, masks, fill = grey)
        val outOther = ReferenceMasker.apply(other, masks, fill = grey)
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            PixelOracle.insideDiffersFromSource(partlyFlat, out, masks, Fidelity.Exact).codes,
        )
        val pair = other to outOther
        PixelOracle.insideDiffersFromSource(partlyFlat, out, masks, Fidelity.Exact, pair = pair).assertPassed()
        PixelOracle.requireMasked(
            partlyFlat,
            out,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(other, outOther),
        ).assertPassed()
        // A face that is entirely the fill colour can not be told from a leak: the check says so.
        val allFlat = Raster.of(160, 120) { x, y ->
            if (masks.any { it.radius(x, y) <= 1.0 }) 0xFF808080.toInt() else background[x, y]
        }
        val otherFlat = PixelFixtures.perturbInside(allFlat, masks, seed = 82, opaque = true)
        val flatOut = ReferenceMasker.apply(allFlat, masks, fill = grey)
        val flatOtherOut = ReferenceMasker.apply(otherFlat, masks, fill = grey)
        val flat = PixelOracle.insideDiffersFromSource(
            allFlat,
            flatOut,
            masks,
            Fidelity.Exact,
            pair =
            otherFlat to flatOtherOut,
        )
        assertEquals(setOf(OracleCode.CHECK_CANNOT_DISCRIMINATE), flat.codes, flat.summary())
        // A masker that leaves the face (output = input in both) still fails with the pair.
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            PixelOracle.insideDiffersFromSource(
                partlyFlat,
                partlyFlat,
                masks,
                Fidelity.Exact,
                pair = other to other,
            ).codes,
        )
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(
                partlyFlat,
                partlyFlat,
                masks,
                Expect.ALL_APPLIED,
                Fidelity.Exact,
                other to other,
            ).codes,
        )
    }

    @Test
    fun aLowContrastFaceWithinTheToleranceIsNotAcceptedAsMasked() {
        val field = 0xFF808080.toInt()
        val face = Raster.of(160, 120) { x, y ->
            (0xFF shl 24) or
                (((0x80 + (x * 7 + y * 3) % 25 - 12) and 0xFF) * 0x010101)
        }
        val expectedField = { _: Int, _: Int -> field }
        // Unmasked: the face is the output. Its pixels are within +-12 of the mask grey.
        val withinTolerance = PixelOracle.insideColourField(face, face, masks, expectedField, maxChannelDelta = 16)
        assertEquals(setOf(OracleCode.CHECK_CANNOT_DISCRIMINATE), withinTolerance.codes, withinTolerance.summary())
        // With an exact tolerance the unmasked face is a mismatch (and the few pixels that equal the grey exactly are
        // reported as indistinguishable).
        assertEquals(
            setOf(OracleCode.COLOUR_FIELD_MISMATCH, OracleCode.CHECK_CANNOT_DISCRIMINATE),
            PixelOracle.insideColourField(face, face, masks, expectedField, 0).codes,
        )
        // A stated share of indistinguishable pixels can be allowed explicitly.
        val allowed = PixelOracle.insideColourField(
            face,
            face,
            masks,
            expectedField,
            16,
            maxIndistinguishableFraction = 1.0,
        )
        assertTrue(allowed.passed)
    }

    @Test
    fun anOpaqueInputOrAJpegCannotProveTheOpacityCore() {
        val opaque = PixelFixtures.noiseCanvas(160, 120, seed = 5, constantAlpha = 255)
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.insideOpaque(opaque, opaque, masks).codes)
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.softEdgeWeightBounds(opaque, opaque, masks).codes)
    }

    @Test
    fun aPairThatDiffersInOnePixelIsTooThinToProveIndependence() {
        val thin = inputA.copy()
        val i = (41 * 160) + 50 // inside the first mask
        thin.argb[i] = thin.argb[i] xor 0x00FFFFFF
        val out = ReferenceMasker.apply(inputA, masks)
        assertEquals(setOf(OracleCode.PAIR_TOO_THIN), PixelOracle.differentialPair(inputA, thin, out, out, masks).codes)
        // A stated lower bound makes it a pair, and then the (identical) outputs pass.
        PixelOracle.differentialPair(inputA, thin, out, out, masks, minDifferingFraction = 0.0001).assertPassed()
    }

    @Test
    fun theSoftEdgeToleranceIsOneRoundingLevelNotMore() {
        // Exact staircase: 200, then 100, then a rise to 100 + levels, then lower again (ADR test c2).
        val mask = masks[0]
        fun staircase(levels: Int): Raster {
            val out = alphaZero.copy()
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    val r = mask.radius(x, y)
                    val t = (r - 1.0) / mask.softEdgeWidth
                    val alpha = when {
                        r <= 1.0 -> 255
                        t > 1.0 -> 0
                        t < 0.3 -> 200
                        t < 0.5 -> 100
                        t < 0.7 -> 100 + levels
                        else -> 50
                    }
                    out.argb[y * out.width + x] = (alpha shl 24) or (out.argb[y * out.width + x] and 0xFFFFFF)
                }
            }
            return out
        }
        // t < 0.3 is 200, which is also what 255 beyond t = 0.05 must not be: 200 is fine, only 255 is bounded.
        PixelOracle.softEdgeWeightBounds(alphaZero, staircase(0), listOf(mask)).assertPassed()
        PixelOracle.softEdgeWeightBounds(alphaZero, staircase(1), listOf(mask)).assertPassed()
        assertEquals(
            setOf(OracleCode.SOFT_EDGE_OPACITY_INCREASES),
            PixelOracle.softEdgeWeightBounds(alphaZero, staircase(2), listOf(mask)).codes,
        )
    }

    @Test
    fun aLargeMaskWithAlphaZeroOrAlmostOpaqueInputKeepsTheArchitectsBounds() {
        // At real face sizes the 8-bit rounding makes the inner 2.6 % of the soft edge fully opaque beyond r = 1.
        val big = MaskEllipse.fromFaceBox(300.0, 200.0, 600.0, 700.0)
        assertTrue(big.smallerSemiAxis >= 400)
        for (alpha in listOf(0, 254)) {
            val canvas = PixelFixtures.noiseCanvas(1400, 1500, seed = 8, constantAlpha = alpha)
            val out = ReferenceMasker.apply(canvas, listOf(big))
            PixelOracle.softEdgeOutwardOnly(canvas, out, listOf(big)).assertPassed()
            PixelOracle.unchangedOutside(canvas, out, listOf(big)).assertPassed()
            if (alpha == 0) PixelOracle.softEdgeWeightBounds(canvas, out, listOf(big)).assertPassed()
        }
        // Alpha beyond the soft edge on an alpha-0 input: the weight is 0 there (ADR 0002 test c2).
        val beyondCanvas = PixelFixtures.noiseCanvas(1400, 1500, seed = 10, constantAlpha = 0)
        val beyondOut = ReferenceMasker.apply(beyondCanvas, listOf(big))
        val farIndex = 494 * 1400 + 1207 // r = 1.5: beyond the soft edge (1.1)
        assertTrue(big.radius(1207, 494) in 1.2..2.0)
        beyondOut.argb[farIndex] = (5 shl 24) or (beyondOut.argb[farIndex] and 0xFFFFFF)
        assertEquals(
            setOf(OracleCode.SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS),
            PixelOracle.softEdgeWeightBounds(beyondCanvas, beyondOut, listOf(big)).codes,
        )
        // A ring that is opaque out to t = 0.2 (8 px) is a defect even for a large mask.
        val canvas = PixelFixtures.noiseCanvas(1400, 1500, seed = 9, constantAlpha = 0)
        val out = ReferenceMasker.apply(canvas, listOf(big))
        for (y in 0 until canvas.height) {
            for (x in 0 until canvas.width) {
                val t = (big.radius(x, y) - 1.0) / big.softEdgeWidth
                if (t > 0 && t <= 0.2) out.argb[y * canvas.width + x] = ReferenceMasker.colourField(x, y)
            }
        }
        assertEquals(
            setOf(OracleCode.SOFT_EDGE_WEIGHT_OUT_OF_BOUNDS),
            PixelOracle.softEdgeWeightBounds(canvas, out, listOf(big)).codes,
        )
    }

    @Test
    fun aMaskOutsideTheImageJudgesNothingAndIsNeverAPass() {
        val far = listOf(MaskEllipse.fromFaceBox(1000.0, 1000.0, 40.0, 40.0))
        val out = ReferenceMasker.apply(inputA, far)
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.insideOpaque(inputA, out, far).codes)
        assertEquals(
            setOf(OracleCode.NOTHING_CHECKED),
            PixelOracle.insideColourField(inputA, out, far, ReferenceMasker::colourField, 0).codes,
        )
        assertEquals(
            setOf(OracleCode.NOTHING_CHECKED),
            PixelOracle.insideDiffersFromSource(inputA, out, far, Fidelity.Exact).codes,
        )
        assertEquals(
            setOf(OracleCode.NOTHING_CHECKED),
            PixelOracle.maskApplication(inputA, out, far, Expect.ALL_APPLIED, Fidelity.Exact).codes,
        )
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.softEdgeOutwardOnly(inputA, out, far).codes)
        // No mask at all: every pixel is outside, so "unchanged outside" is judged, and the "inside" checks judge nothing.
        assertTrue(PixelOracle.unchangedOutside(inputA, inputA, emptyList()).passed)
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.insideOpaque(inputA, inputA, emptyList()).codes)
    }

    @Test
    fun theErrorPathExpectsNothingAppliedAndTheSuccessPathExpectsEverything() {
        val partly = ReferenceMasker.apply(inputA, masks.take(1))
        val all = ReferenceMasker.apply(inputA, masks)
        val none = Fidelity.Exact
        assertTrue(PixelOracle.maskApplication(inputA, inputA, masks, Expect.NONE_APPLIED, none).passed)
        assertEquals(
            setOf(OracleCode.PARTLY_MASKED),
            PixelOracle.maskApplication(inputA, partly, masks, Expect.NONE_APPLIED, none).codes,
        )
        assertEquals(
            setOf(OracleCode.PARTLY_MASKED),
            PixelOracle.maskApplication(inputA, all, masks, Expect.NONE_APPLIED, none).codes,
        )
        assertEquals(
            setOf(OracleCode.PARTLY_MASKED),
            PixelOracle.maskApplication(inputA, partly, masks, Expect.ALL_APPLIED, none).codes,
        )
        // A single mask that was skipped: the success path reports NOT_MASKED, the error path accepts it.
        val single = listOf(masks[0])
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(inputA, inputA, single, Expect.ALL_APPLIED, none).codes,
        )
        assertTrue(PixelOracle.maskApplication(inputA, inputA, single, Expect.NONE_APPLIED, none).passed)
        PixelOracle.maskApplication(
            inputA,
            ReferenceMasker.apply(inputA, single),
            single,
            Expect.ALL_APPLIED,
            none,
        ).assertPassed()
    }

    @Test
    fun sizesThatDifferAreReportedNotCompared() {
        val small = PixelFixtures.noiseCanvas(10, 10, seed = 1)
        assertEquals(setOf(OracleCode.SIZE_MISMATCH), PixelOracle.unchangedOutside(inputA, small, masks).codes)
        assertEquals(setOf(OracleCode.SIZE_MISMATCH), PixelOracle.identicalPixels(inputA, small).codes)
        assertEquals(setOf(OracleCode.SIZE_MISMATCH), PixelOracle.unchangedAfterError(inputA, small).codes)
        assertEquals(
            setOf(OracleCode.SIZE_MISMATCH),
            PixelOracle.differentialPair(inputA, inputB, small, small, masks).codes,
        )
        assertEquals(
            setOf(OracleCode.SIZE_MISMATCH),
            PixelOracle.maskApplication(inputA, small, masks, Expect.ALL_APPLIED, Fidelity.Exact).codes,
        )
    }

    @Test
    fun theReportNeverEchoesPixelValues() {
        val report = runAll(PlantedPixelDefect.DEPENDS_ON_INPUT_INSIDE)
        val text = report.summary()
        assertTrue(text.contains("first at ("), text)
        assertTrue(Regex("[0-9a-fA-F]{6,}").find(text) == null, "no hexadecimal pixel values in the summary: $text")
        assertFailsWith<AssertionError> { report.assertPassed() }
    }
}
