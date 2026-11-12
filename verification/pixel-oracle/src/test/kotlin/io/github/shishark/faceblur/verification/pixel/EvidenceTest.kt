// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The evidence given to `requireMasked` is validated, not trusted: asymmetric pairs, aliased or swapped buffers, thin pairs and
 * caller-chosen tolerances can not make an unmasked or translucent face pass (earlier reviews of this oracle).
 */
class EvidenceTest {
    private val masks = listOf(
        MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0),
        MaskEllipse.fromFaceBox(100.0, 60.0, 30.0, 40.0),
    )
    private val input = PixelFixtures.noiseCanvas(160, 120, seed = 91, constantAlpha = 255)
    private val inputB = PixelOracle.pairInputFor(input, masks, seed = 92)
    private val good = ReferenceMasker.apply(input, masks)
    private val goodB = ReferenceMasker.apply(inputB, masks)
    private val grey = { _: Int, _: Int -> 0xFF808080.toInt() }
    private val greyField = ColourIndependence.Field(grey, 0)

    private fun pair(outputB: Raster, delta: Int = 0) = ColourIndependence.DifferentialPair(inputB, outputB, delta)

    @Test
    fun theOraclesOwnPairDiffersStronglyInEveryInsidePixel() {
        val zones = Zones(input.width, input.height, masks)
        var inside = 0
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                if (zones.at(x, y) == Zones.INSIDE) {
                    inside++
                    assertTrue(PixelOracle.channelDelta(input[x, y], inputB[x, y]) >= 64, "pixel $x,$y")
                    for (shift in intArrayOf(24, 16, 8, 0)) {
                        assertTrue(
                            Math.abs(((input[x, y] ushr shift) and 0xFF) - ((inputB[x, y] ushr shift) and 0xFF)) >= 64,
                        )
                    }
                } else if (!zones.isAmbiguous(zones.at(x, y))) {
                    assertEquals(input[x, y], inputB[x, y], "outside pixels are untouched")
                }
            }
        }
        assertTrue(inside > 1000)
        assertTrue(
            PixelOracle.pairInputFor(input, masks, 92).argb.contentEquals(inputB.argb),
            "seeded and deterministic",
        )
        assertTrue(!PixelOracle.pairInputFor(input, masks, 93).argb.contentEquals(inputB.argb))
    }

    @Test
    fun anUnmaskedFirstOutputWithACorrectlyMaskedSecondOneFailsTheStandaloneChecksToo() {
        // With a pair the survival rule used to excuse every pixel when only the second output was masked.
        val pairPart = inputB to goodB
        val unmasked = PixelOracle.insideDiffersFromSource(input, input, masks, Fidelity.Exact, pair = pairPart)
        assertEquals(setOf(OracleCode.EQUALS_SOURCE_INSIDE), unmasked.codes, unmasked.summary())
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(input, input, masks, Expect.ALL_APPLIED, Fidelity.Exact, pairPart).codes,
        )
        // A 5 x 5 hole in the first output only.
        val hole = good.copy()
        val cx = masks[0].cx.toInt()
        val cy = masks[0].cy.toInt()
        for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) hole.argb[y * hole.width + x] = input[x, y]
        assertEquals(
            setOf(OracleCode.EQUALS_SOURCE_INSIDE),
            PixelOracle.insideDiffersFromSource(input, hole, masks, Fidelity.Exact, pair = pairPart).codes,
        )
        assertEquals(
            setOf(OracleCode.PARTLY_MASKED),
            PixelOracle.maskApplication(input, hole, masks, Expect.ALL_APPLIED, Fidelity.Exact, pairPart).codes,
        )
        // An unrelated second output does not help either.
        val noise = PixelFixtures.noiseCanvas(160, 120, seed = 93, constantAlpha = 255)
        assertEquals(
            setOf(OracleCode.NOT_MASKED),
            PixelOracle.maskApplication(input, input, masks, Expect.ALL_APPLIED, Fidelity.Exact, inputB to noise).codes,
        )
        // The correct pair still passes.
        PixelOracle.insideDiffersFromSource(input, good, masks, Fidelity.Exact, pair = pairPart).assertPassed()
    }

    @Test
    fun aliasedOrSwappedOutputsAreRejectedAsEvidence() {
        // One buffer masked in place twice: output and outputB are the same object holding the second result.
        val shared = goodB
        for (output in listOf(shared, input)) {
            val aliased = PixelOracle.requireMasked(input, output, masks, Fidelity.Exact, pair(shared))
            assertTrue(OracleCode.INVALID_EVIDENCE in aliased.codes, aliased.summary())
        }
        // The typo: the first output passed as the second.
        val faceKept = ReferenceMasker.apply(input, masks, PlantedPixelDefect.FACE_KEPT_ALPHA_255)
        val typo = PixelOracle.requireMasked(input, faceKept, masks, Fidelity.Exact, pair(faceKept))
        assertTrue(OracleCode.INVALID_EVIDENCE in typo.codes, typo.summary())
        // The same with a copy: the values look like a pair, but the output equals the source and the "second output".
        val copied = PixelOracle.requireMasked(input, faceKept, masks, Fidelity.Exact, pair(faceKept.copy()))
        assertTrue(!copied.passed && OracleCode.CHECK_CANNOT_DISCRIMINATE in copied.codes, copied.summary())
        // Input object reused as the second input.
        val reused = PixelOracle.requireMasked(
            input,
            good,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(input, goodB),
        )
        assertTrue(OracleCode.INVALID_EVIDENCE in reused.codes, reused.summary())
        // The output must be a different object from the input.
        assertTrue(
            OracleCode.INVALID_EVIDENCE in
                PixelOracle.requireMasked(input, input, masks, Fidelity.Exact, greyField).codes,
        )
        // An honest pair passes.
        PixelOracle.requireMasked(input, good, masks, Fidelity.Exact, pair(goodB)).assertPassed()
    }

    @Test
    fun aPairThatDiffersByOneLevelProvesNothing() {
        val thin = Raster.of(input.width, input.height) { x, y ->
            if (masks.any { it.radius(x, y) <= 1.0 - MaskEllipse.BOUNDARY_EPSILON }) input[x, y] xor 1 else input[x, y]
        }
        val outThin = ReferenceMasker.apply(thin, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
        val mixed = ReferenceMasker.apply(input, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
        val report = PixelOracle.requireMasked(
            input,
            mixed,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(thin, outThin),
        )
        assertTrue(OracleCode.PAIR_TOO_THIN in report.codes, report.summary())
    }

    /** A saturated binary face mixed with grey at the given face weight, opaque. */
    private fun mixed(face: Raster, weight: Double): Raster = Raster.of(face.width, face.height) { x, y ->
        if (masks.any { it.radius(x, y) <= 1.0 }) {
            val v = if ((face[x, y] and 0xFF) >= 128) 255.0 else 0.0
            val c = Math.round(128 + (v - 128) * weight).toInt()
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        } else {
            face[x, y]
        }
    }

    @Test
    fun aTranslucentFaceMixFailsAtEveryToleranceTheExactModeCouldBeAskedToAccept() {
        val faceA = Raster.of(160, 120) { x, y ->
            (0xFF shl 24) or
                (if ((x * 7 + y * 13) % 5 < 2) 0xFFFFFF else 0x000000)
        }
        val faceB = PixelOracle.pairInputFor(faceA, masks, seed = 94)
        for (weight in listOf(0.01, 0.03, 0.1, 0.25, 0.5)) {
            val outA = mixed(faceA, weight)
            val outB = mixed(faceB, weight)
            for (tolerance in listOf(0, 1, 2, 4, 8)) {
                val field = PixelOracle.requireMasked(
                    faceA,
                    outA,
                    masks,
                    Fidelity.Exact,
                    ColourIndependence.Field(grey, tolerance),
                )
                assertTrue(!field.passed, "field tolerance $tolerance, face weight $weight: ${field.summary()}")
                val pair = PixelOracle.requireMasked(
                    faceA,
                    outA,
                    masks,
                    Fidelity.Exact,
                    ColourIndependence.DifferentialPair(faceB, outB, tolerance),
                )
                assertTrue(!pair.passed, "pair tolerance $tolerance, face weight $weight: ${pair.summary()}")
                if (tolerance > 0) {
                    assertTrue(OracleCode.INVALID_EVIDENCE in field.codes && OracleCode.INVALID_EVIDENCE in pair.codes)
                }
            }
        }
    }

    @Test
    fun theOracleBuildsAndRunsThePairItselfWhenGivenAMasker() {
        // Correct masker, an in-place masker that returns its argument, and one that returns a shared buffer.
        val good = PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, {
            ReferenceMasker.apply(it, masks)
        }, libraryFeather = 0.10)
        good.assertPassed()
        PixelOracle.requireMaskedBy(
            input,
            masks,
            Fidelity.Exact,
            { raster ->
                val masked = ReferenceMasker.apply(raster, masks)
                masked.argb.copyInto(raster.argb)
                raster
            },
            libraryFeather = 0.10,
        ).assertPassed()
        val shared = input.copy()
        PixelOracle.requireMaskedBy(
            input,
            masks,
            Fidelity.Exact,
            { raster ->
                ReferenceMasker.apply(raster, masks).argb.copyInto(shared.argb)
                shared
            },
            libraryFeather = 0.10,
        ).assertPassed()
        // A leaking masker that keeps its result in a shared buffer must fail: the result is copied right after each call.
        val leakShared = input.copy()
        val leaking = PixelOracle.requireMaskedBy(
            input,
            masks,
            Fidelity.Exact,
            { raster ->
                ReferenceMasker.apply(
                    raster,
                    masks,
                    PlantedPixelDefect.FACE_KEPT_ALPHA_255,
                ).argb.copyInto(leakShared.argb)
                leakShared
            },
            libraryFeather = 0.10,
        )
        assertTrue(!leaking.passed, leaking.summary())
        // Broken maskers fail: nothing masked, face kept, face mixed with grey, a hole.
        for (broken in listOf<(Raster) -> Raster>(
            { it },
            { ReferenceMasker.apply(it, masks, PlantedPixelDefect.FACE_KEPT_ALPHA_255) },
            { ReferenceMasker.apply(it, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY) },
            { ReferenceMasker.apply(it, masks, PlantedPixelDefect.DEPENDS_ON_INPUT_INSIDE) },
        )) {
            assertTrue(!PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, broken, libraryFeather = 0.10).passed)
        }
        // The caller's input is never modified, and the exact mode allows no pair tolerance.
        val before = input.copy()
        PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, {
            ReferenceMasker.apply(it, masks)
        }, libraryFeather = 0.10)
        assertTrue(before.argb.contentEquals(input.argb))
        val tolerant = PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, {
            ReferenceMasker.apply(it, masks)
        }, pairTolerance = 4, libraryFeather = 0.10)
        assertTrue(OracleCode.INVALID_EVIDENCE in tolerant.codes)
    }

    @Test
    fun theBoundedFeatherWeightIsPartOfTheMandatorySetWhenTheInputHasAlphaZeroPixels() {
        val alphaZero = PixelFixtures.noiseCanvas(160, 120, seed = 95, constantAlpha = 0)
        val rises = PixelOracle.requireMaskedBy(
            alphaZero,
            masks,
            Fidelity.Exact,
            {
                ReferenceMasker.apply(it, masks, PlantedPixelDefect.SOFT_EDGE_OPACITY_RISES_OUTWARD)
            },
            libraryFeather = 0.10,
        )
        assertTrue(OracleCode.SOFT_EDGE_OPACITY_INCREASES in rises.codes, rises.summary())
        PixelOracle.requireMaskedBy(alphaZero, masks, Fidelity.Exact, {
            ReferenceMasker.apply(it, masks)
        }, libraryFeather = 0.10).assertPassed()
        // Without alpha-0 pixels (c2) can not judge and is left out; the correct masker still gets a positive verdict.
        PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, {
            ReferenceMasker.apply(it, masks)
        }, libraryFeather = 0.10).assertPassed()
    }

    @Test
    fun theFeatherOfTheMasksMustMatchTheLibraryConfiguration() {
        PixelOracle.requireMasked(input, good, masks, Fidelity.Exact, pair(goodB), libraryFeather = 0.10).assertPassed()
        val wide = masks.map { MaskEllipse(it.cx, it.cy, it.rx, it.ry, feather = 0.5) }
        val report = PixelOracle.requireMasked(input, good, wide, Fidelity.Exact, pair(goodB), libraryFeather = 0.10)
        assertTrue(OracleCode.INVALID_EVIDENCE in report.codes, report.summary())
    }

    @Test
    fun noMaskMeansNoDistanceAndAScaledSliverIsRaisedAfterScaling() {
        val none = PixelOracle.softEdgeOutwardOnly(input, good, emptyList())
        assertNull(none.maxChangedDistancePx)
        // The raise to one pixel happens after scaling, as in the renderer.
        val sliver = MaskEllipse(10.0, 10.0, 0.3, 0.3)
        assertEquals(1.0, sliver.rx, 0.0)
        assertEquals(1.2, sliver.scaled(4.0).rx, 1e-12)
        assertEquals(1.0, sliver.scaled(2.0).rx, 0.0)
    }

    // ---- the public surface, the floors, and the maskers that misbehave

    @Test
    fun theOnlyPublicMaskedVerdictIsRequireMaskedBy() {
        // Internal functions are public in the bytecode but carry a module suffix after a dollar sign.
        val names = PixelOracle::class.java.methods.map { it.name }.filter { '$' !in it }.toSet()
        assertTrue("requireMaskedBy" in names)
        assertTrue("requireMaskedLossyBy" in names)
        val public = setOf("requireMaskedBy", "requireMaskedLossyBy")
        assertTrue(
            names.none { it.startsWith("requireMasked") && it !in public },
            "a caller-supplied pair or field must not be public API: $names",
        )
    }

    @Test
    fun theFloorsOfThePairPreconditionCannotBeLowered() {
        val thin = Raster.of(input.width, input.height) { x, y ->
            if (masks.any { it.radius(x, y) <= 1.0 - MaskEllipse.BOUNDARY_EPSILON }) input[x, y] xor 1 else input[x, y]
        }
        val mixedA = ReferenceMasker.apply(input, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
        val mixedThin = ReferenceMasker.apply(thin, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
        val lowered = PixelOracle.requireMasked(
            input,
            mixedA,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(thin, mixedThin, minInputDifference = 1, minDifferingFraction = 1.0),
            0.10,
        )
        assertTrue(OracleCode.INVALID_EVIDENCE in lowered.codes && !lowered.passed, lowered.summary())
        val looserShare = PixelOracle.requireMasked(
            input,
            good,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(inputB, goodB, minDifferingFraction = 0.5),
            0.10,
        )
        assertTrue(OracleCode.INVALID_EVIDENCE in looserShare.codes, looserShare.summary())
        // Lossy block pairs have their own, lower floor (0.10) and the same 32-level floor.
        val lossy = Fidelity.Lossy(good, 2)
        fun lossyWith(fraction: Double, difference: Int) = PixelOracle.requireMasked(
            input,
            good,
            masks,
            lossy,
            ColourIndependence.DifferentialPair(inputB, goodB, 0, fraction, difference),
            0.10,
        )
        assertTrue(OracleCode.INVALID_EVIDENCE !in lossyWith(0.10, 32).codes)
        assertTrue(OracleCode.INVALID_EVIDENCE in lossyWith(0.05, 32).codes)
        assertTrue(OracleCode.INVALID_EVIDENCE in lossyWith(0.10, 8).codes)
    }

    @Test
    fun twoRasterWrappersOverOnePixelArrayAreOneBuffer() {
        val mixedA = ReferenceMasker.apply(input, masks, PlantedPixelDefect.FACE_MIXED_WITH_GREY)
        // The second output is a second wrapper over the first output's pixels (an in-place masker on one reused buffer).
        val wrapped = Raster(mixedA.width, mixedA.height, mixedA.argb)
        val report = PixelOracle.requireMasked(input, mixedA, masks, Fidelity.Exact, pair(wrapped), 0.10)
        assertTrue(OracleCode.INVALID_EVIDENCE in report.codes && !report.passed, report.summary())
        // The output is a wrapper over the input's pixels: nothing was copied or masked.
        val outputOverInput = Raster(input.width, input.height, input.argb)
        val sameArray = PixelOracle.requireMasked(input, outputOverInput, masks, Fidelity.Exact, greyField, 0.10)
        assertTrue(OracleCode.INVALID_EVIDENCE in sameArray.codes, sameArray.summary())
        val inputWrapped = Raster(input.width, input.height, input.argb)
        val reusedInput = PixelOracle.requireMasked(
            input,
            good,
            masks,
            Fidelity.Exact,
            ColourIndependence.DifferentialPair(inputWrapped, goodB),
            0.10,
        )
        assertTrue(OracleCode.INVALID_EVIDENCE in reusedInput.codes, reusedInput.summary())
    }

    /** Masks [raster] like the reference masker, then replaces every pixel inside the masks by [inside]. */
    private fun masker(inside: ((Int, Int, Int) -> Int)?): (Raster) -> Raster = { raster ->
        val masked = ReferenceMasker.apply(raster, masks)
        if (inside != null) {
            for (y in 0 until raster.height) {
                for (x in 0 until raster.width) {
                    if (masks.any { it.radius(x, y) <= 1.0 }) {
                        masked.argb[y * raster.width + x] =
                            inside(x, y, raster[x, y])
                    }
                }
            }
        }
        masked.argb.copyInto(raster.argb) // in place, returns the argument
        raster
    }

    private fun mixWith(face: Double): (Int, Int, Int) -> Int = { x, y, source ->
        val g = ReferenceMasker.colourField(x, y)
        var p = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val c = Math.round(((g ushr shift) and 0xFF) * (1 - face) + ((source ushr shift) and 0xFF) * face).toInt()
            p = p or (c shl shift)
        }
        p
    }

    @Test
    fun requireMaskedByHoldsForEveryInputAlphaAndEveryDefectOfTheSecurityProbes() {
        for (alpha in listOf(0xFF, 0x80, 0x00)) {
            val canvas = PixelFixtures.noiseCanvas(160, 120, seed = 96, constantAlpha = alpha)
            PixelOracle.requireMaskedBy(
                canvas,
                masks,
                Fidelity.Exact,
                masker(null),
                libraryFeather = 0.10,
            ).assertPassed()
            val cx = masks[0].cx.toInt()
            val cy = masks[0].cy.toInt()
            val defects = mapOf<String, (Int, Int, Int) -> Int>(
                "face kept" to { _, _, s -> (0xFF shl 24) or (s and 0xFFFFFF) },
                "mix 50 %" to mixWith(0.5),
                "mix 3 %" to mixWith(0.03),
                "mix 1 %" to mixWith(0.01),
                "hole 1x1" to { x, y, s -> if (x == cx && y == cy) s else ReferenceMasker.colourField(x, y) },
                "hole 5x5" to { x, y, s ->
                    if (Math.abs(x - cx) <= 2 && Math.abs(y - cy) <= 2) s else ReferenceMasker.colourField(x, y)
                },
            )
            for ((name, inside) in defects) {
                val report = PixelOracle.requireMaskedBy(
                    canvas,
                    masks,
                    Fidelity.Exact,
                    masker(inside),
                    libraryFeather = 0.10,
                )
                assertTrue(!report.passed, "$name with input alpha $alpha must fail: ${report.summary()}")
            }
            val unmasked = PixelOracle.requireMaskedBy(canvas, masks, Fidelity.Exact, { it }, libraryFeather = 0.10)
            assertTrue(!unmasked.passed)
            // The weight rule is judged on every proof, whatever the input alpha.
            val rising = PixelOracle.requireMaskedBy(
                canvas,
                masks,
                Fidelity.Exact,
                { raster ->
                    val m = ReferenceMasker.apply(raster, masks, PlantedPixelDefect.SOFT_EDGE_OPACITY_RISES_OUTWARD)
                    m.argb.copyInto(raster.argb)
                    raster
                },
                libraryFeather = 0.10,
            )
            assertTrue(OracleCode.SOFT_EDGE_OPACITY_INCREASES in rising.codes, "alpha $alpha: ${rising.summary()}")
        }
    }

    @Test
    fun aMaskerThatIgnoresItsArgumentOrKeepsStateIsFound() {
        for (inside in listOf(mixWith(0.5), mixWith(0.03), { _: Int, _: Int, s: Int ->
            (0xFF shl 24) or (s and 0xFFFFFF)
        })) {
            val ignoring = PixelOracle.requireMaskedBy(
                input,
                masks,
                Fidelity.Exact,
                { _ -> masker(inside)(input.copy()) }, // masks the captured input instead of its argument
                libraryFeather = 0.10,
            )
            assertTrue(!ignoring.passed, ignoring.summary())
        }
        // The translucent defects are only found through the argument-following control call.
        val mix3 = PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, { _ ->
            masker(mixWith(0.03))(input.copy())
        }, libraryFeather = 0.10)
        assertTrue(OracleCode.MASKER_IGNORES_ITS_ARGUMENT in mix3.codes, mix3.summary())
        var calls = 0
        val stateful = PixelOracle.requireMaskedBy(
            input,
            masks,
            Fidelity.Exact,
            { raster ->
                if (calls++ ==
                    0
                ) {
                    masker { _, _, s -> (0xFF shl 24) or (s and 0xFFFFFF) }(raster)
                } else {
                    masker(null)(raster)
                }
            },
            libraryFeather = 0.10,
        )
        assertTrue(!stateful.passed, stateful.summary())
        // A masker that follows its argument is not reported.
        assertTrue(
            OracleCode.MASKER_IGNORES_ITS_ARGUMENT !in
                PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, masker(null), libraryFeather = 0.10).codes,
        )
    }

    @Test
    fun theFeatherIsMandatoryAndMustMatch() {
        val wide = masks.map { MaskEllipse(it.cx, it.cy, it.rx, it.ry, feather = 0.5) }
        val report = PixelOracle.requireMaskedBy(input, wide, Fidelity.Exact, masker(null), libraryFeather = 0.10)
        assertTrue(OracleCode.INVALID_EVIDENCE in report.codes, report.summary())
    }

    @Test
    fun theInternalLossyEntryHasItsOwnFloorsAndACeilingAndCatchesTheHonestDefects() {
        val opaque = PixelFixtures.noiseCanvas(160, 120, seed = 41, constantAlpha = 255)
        val reference = PixelDecoder.decode(PixelFixtures.jpeg(opaque))
        val blocks = PixelOracle.mcuSafeBlocks(160, 120, listOf(MaskEllipse.fromFaceBox(20.0, 10.0, 90.0, 100.0)))
        val big = listOf(MaskEllipse.fromFaceBox(20.0, 10.0, 90.0, 100.0))
        val changed = PixelFixtures.perturbBlocks(opaque, blocks, seed = 42)
        val lossy = Fidelity.Lossy(reference, 0)
        val maskedA = PixelDecoder.decode(PixelFixtures.jpeg(ReferenceMasker.apply(opaque, big, fill = grey)))
        val maskedB = PixelDecoder.decode(PixelFixtures.jpeg(ReferenceMasker.apply(changed, big, fill = grey)))
        assertTrue(blocks.isNotEmpty())
        val share = blocks.size * 256.0 / insideCount(big)
        val ok = PixelOracle.requireMaskedLossy(
            opaque,
            maskedA,
            big,
            lossy,
            changed,
            maskedB,
            0.10,
            minDifferingFraction = 0.10,
        )
        assertTrue(ok.passed, "block share $share: ${ok.summary()}")
        val unmasked = PixelOracle.requireMaskedLossy(
            opaque,
            reference,
            big,
            lossy,
            changed,
            maskedB,
            0.10,
            minDifferingFraction = 0.10,
        )
        assertTrue(!unmasked.passed)
        val lowered = PixelOracle.requireMaskedLossy(
            opaque,
            maskedA,
            big,
            lossy,
            changed,
            maskedB,
            0.10,
            minDifferingFraction = 0.01,
        )
        assertTrue(OracleCode.INVALID_EVIDENCE in lowered.codes)
        fun lossyWith(outA: Raster, outB: Raster, tolerance: Int = 0) = PixelOracle.requireMaskedLossy(
            opaque,
            outA,
            big,
            lossy,
            changed,
            outB,
            0.10,
            pairTolerance = tolerance,
            minDifferingFraction = 0.10,
        )
        // Honest pairs (each output is the masker's result for its own input) with the defects of the security probes.
        val defects = mapOf<String, (Int, Int, Int) -> Int>(
            "kept" to { _, _, source -> source },
            "mix50" to mixWith(0.5),
            "mix10" to mixWith(0.1),
            "hole" to { x, y, source -> if (abs(x - 65) <= 2 && abs(y - 60) <= 2) source else 0xFF808080.toInt() },
        )
        for ((name, inside) in defects) {
            val defectA = PixelDecoder.decode(PixelFixtures.jpeg(masked(opaque, big, inside)))
            val defectB = PixelDecoder.decode(PixelFixtures.jpeg(masked(changed, big, inside)))
            assertTrue(!lossyWith(defectA, defectB, tolerance = 8).passed, "honest pair, defect $name")
        }
        // The same object twice is rejected as evidence; a tolerance above the ceiling too.
        assertTrue(OracleCode.INVALID_EVIDENCE in lossyWith(maskedA, maskedA).codes)
        assertEquals(8, PixelOracle.LOSSY_MAX_PAIR_TOLERANCE)
        assertTrue(lossyWith(maskedA, maskedB, tolerance = 8).passed)
        assertTrue(OracleCode.INVALID_EVIDENCE in lossyWith(maskedA, maskedB, tolerance = 9).codes)
        // A wrong reference (the decode of B) with an honest pair still fails for a kept face.
        val keptA = PixelDecoder.decode(PixelFixtures.jpeg(masked(opaque, big) { _, _, source -> source }))
        val keptB = PixelDecoder.decode(PixelFixtures.jpeg(masked(changed, big) { _, _, source -> source }))
        val wrongReference = Fidelity.Lossy(PixelDecoder.decode(PixelFixtures.jpeg(changed)), 0)
        assertTrue(
            !PixelOracle.requireMaskedLossy(
                opaque,
                keptA,
                big,
                wrongReference,
                changed,
                keptB,
                0.10,
                minDifferingFraction = 0.10,
            ).passed,
        )
    }

    /** [source] masked like the reference masker, then every pixel inside the masks replaced by [inside]. */
    private fun masked(source: Raster, m: List<MaskEllipse>, inside: (Int, Int, Int) -> Int): Raster {
        val out = ReferenceMasker.apply(source, m, fill = grey)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                if (m.any { it.radius(x, y) <= 1.0 }) out.argb[y * source.width + x] = inside(x, y, source[x, y])
            }
        }
        return out
    }

    private fun insideCount(m: List<MaskEllipse>): Int =
        (0 until input.height).sumOf { y -> (0 until input.width).count { x -> m.any { it.radius(x, y) <= 1.0 } } }
}
