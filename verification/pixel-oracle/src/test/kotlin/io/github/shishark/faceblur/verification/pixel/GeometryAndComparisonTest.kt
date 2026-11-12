// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Geometry of FR-004, boundary handling, the comparison checks, decoding, lossy pairs and scaled position. */
class GeometryAndComparisonTest {
    @Test
    fun theEllipseFollowsTheFormulaOfFr004() {
        val m = MaskEllipse.fromFaceBox(100.0, 50.0, 40.0, 50.0)
        assertEquals(120.0, m.cx, 1e-9)
        assertEquals(50.0 + 25.0 - 0.08 * 50.0, m.cy, 1e-9) // box centre minus 8 % of the height
        assertEquals(0.8 * 40.0, m.rx, 1e-9)
        assertEquals(0.9 * 50.0, m.ry, 1e-9)
        var inside = 0
        var boxPixelsInside = 0
        for (y in 0 until 200) {
            for (x in 0 until 240) {
                if (m.radius(x, y) <= 1.0) inside++
                if (x in 100 until 140 && y in 50 until 100 && m.radius(x, y) <= 1.0) boxPixelsInside++
            }
        }
        assertEquals(PI * m.rx * m.ry, inside.toDouble(), PI * m.rx * m.ry * 0.01)
        // FR-004: the r <= 1 region covers at least 99 % of the face box (the bottom corners lie in the soft edge).
        assertTrue(boxPixelsInside >= 0.99 * 40 * 50, "$boxPixelsInside of 2000 box pixels")
    }

    @Test
    fun theSoftEdgeIsTenPercentOfTheSmallerSemiAxisAndAtLeastOnePixel() {
        assertEquals(0.10, MaskEllipse.fromFaceBox(0.0, 0.0, 40.0, 50.0).softEdgeWidth, 1e-12)
        val small = MaskEllipse(cx = 20.0, cy = 20.0, rx = 4.0, ry = 8.0)
        assertEquals(1.0 / 4.0, small.softEdgeWidth, 1e-12) // 1 px of a 4 px semi-axis
        val scaled = MaskEllipse.fromFaceBox(10.0, 10.0, 40.0, 50.0).scaled(0.5)
        assertEquals(MaskEllipse.fromFaceBox(10.0, 10.0, 40.0, 50.0).rx / 2, scaled.rx, 1e-12)
        assertFailsWith<IllegalArgumentException> { MaskEllipse(1.0, 1.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { MaskEllipse(Double.NaN, 1.0, 1.0, 1.0) }
    }

    @Test
    fun theBandInPixelsIsFixedFromTheGeometryAndTheFeatherOnly() {
        // ADR 0002 example: default feather 0.10 and a 600 x 700 face box (rx = 480, ry = 630).
        val m = MaskEllipse.fromFaceBox(300.0, 200.0, 600.0, 700.0)
        assertEquals(63.0, m.maxBandWidthPx, 1e-9)
        assertEquals(48.0, m.softEdgeWidth * m.smallerSemiAxis, 1e-9)
        // A small mask gets a one-pixel band: max(feather, 1 / smaller semi-axis) x larger semi-axis.
        val small = MaskEllipse(50.0, 50.0, 4.0, 8.0)
        assertEquals(0.25 * 8.0, small.maxBandWidthPx, 1e-9)
        assertEquals(0.5 * 60.0, MaskEllipse(0.0, 0.0, 40.0, 60.0, feather = 0.5).maxBandWidthPx, 1e-9)
        assertFailsWith<IllegalArgumentException> { MaskEllipse(0.0, 0.0, 40.0, 60.0, feather = 0.51) }
        assertFailsWith<IllegalArgumentException> { MaskEllipse(0.0, 0.0, 40.0, 60.0, feather = -0.01) }
        assertEquals(0.5, MaskEllipse.fromFaceBox(0.0, 0.0, 40.0, 50.0, feather = 0.5).scaled(0.5).feather)
    }

    @Test
    fun aSemiAxisBelowOnePixelIsRaisedToOnePixelLikeTheRenderer() {
        // A face box clipped to a sliver. With both semi-axes >= 1 px the pixel nearest to the centre
        // is inside, so the mask can be judged; without the raise nothing would be inside (and nothing judged).
        val sliver = MaskEllipse.fromFaceBox(20.0, 20.0, 0.5, 30.0) // rx = 0.4 px before the raise
        assertEquals(1.0, sliver.rx, 0.0)
        assertEquals(0.9 * 30.0, sliver.ry, 1e-9)
        val dot = MaskEllipse(10.0, 10.0, 0.3, 0.6)
        assertEquals(1.0, dot.rx, 0.0)
        assertEquals(1.0, dot.ry, 0.0)
        assertTrue(dot.radius(9, 9) < 1.0, "the pixel containing the centre is inside")
        val canvas = PixelFixtures.noiseCanvas(30, 30, seed = 71, constantAlpha = 0)
        val out = ReferenceMasker.apply(canvas, listOf(dot))
        val report = PixelOracle.insideAlphaIs255(out, listOf(dot))
        assertTrue(report.passed && report.pixelsJudged > 0, report.summary())
        // A semi-axis of exactly the minimum or larger is not changed.
        assertEquals(2.5, MaskEllipse(5.0, 5.0, 2.5, 4.0).rx, 0.0)
    }

    @Test
    fun theDistanceToTheEllipseAgreesWithABruteForceSearch() {
        val random = java.util.Random(99)
        for (ellipse in listOf(
            MaskEllipse(100.0, 80.0, 27.0, 38.75),
            MaskEllipse(0.0, 0.0, 300.0, 20.0),
            MaskEllipse(5.0, 5.0, 30.0, 30.0),
        )) {
            repeat(300) {
                val px = ellipse.cx + (random.nextDouble() - 0.5) * 6 * ellipse.rx
                val py = ellipse.cy + (random.nextDouble() - 0.5) * 6 * ellipse.ry
                var best = Double.MAX_VALUE
                for (k in 0 until 20000) {
                    val a = 2 * PI * k / 20000
                    val d = Math.hypot(
                        ellipse.cx + ellipse.rx * Math.cos(a) - px,
                        ellipse.cy + ellipse.ry * Math.sin(a) - py,
                    )
                    best = minOf(best, d)
                }
                val inside = Math.hypot((px - ellipse.cx) / ellipse.rx, (py - ellipse.cy) / ellipse.ry) <= 1.0
                val expected = if (inside) 0.0 else best
                assertEquals(expected, ellipse.distanceTo(px, py), 0.05 + 0.002 * expected)
            }
        }
        // On the axes and at the centre.
        val e = MaskEllipse(0.0, 0.0, 10.0, 4.0)
        assertEquals(0.0, e.distanceTo(0.0, 0.0), 1e-12)
        assertEquals(5.0, e.distanceTo(15.0, 0.0), 1e-9)
        assertEquals(3.0, e.distanceTo(0.0, 7.0), 1e-9)
    }

    @Test
    fun aChangedPixelBeyondTheFixedBandIsReportedWithItsDistance() {
        val m = MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0)
        val canvas = PixelFixtures.noiseCanvas(160, 120, seed = 61, constantAlpha = 0)
        val good = ReferenceMasker.apply(canvas, listOf(m))
        val report = PixelOracle.softEdgeOutwardOnly(canvas, good, listOf(m))
        report.assertPassed()
        val farthest = report.maxChangedDistancePx!!
        assertTrue(
            farthest > 0.5 && farthest <= m.maxBandWidthPx + 0.5,
            "changed up to $farthest px, band ${m.maxBandWidthPx}",
        )
        // A pixel 12 px beyond the ellipse along the minor axis (band 3.875 px) is a defect in pixels.
        val x = Math.ceil(m.cx + m.rx + 12).toInt()
        val y = Math.round(m.cy).toInt()
        val bad = good.copy()
        bad.argb[y * 160 + x] = bad.argb[y * 160 + x] xor 0x00FFFFFF
        val badReport = PixelOracle.softEdgeOutwardOnly(canvas, bad, listOf(m))
        assertEquals(setOf(OracleCode.CHANGED_BEYOND_BAND), badReport.codes, badReport.summary())
        assertTrue(badReport.maxChangedDistancePx!! > 11.0)
    }

    @Test
    fun pixelsOnTheBoundaryAreAmbiguousAndNeverJudged() {
        // Centre (10.5, 10.5), radius 5: the centre of pixel (15, 10) is at (15.5, 10.5), exactly r = 1.
        val m = MaskEllipse(10.5, 10.5, 5.0, 5.0)
        val canvas = PixelFixtures.noiseCanvas(30, 30, seed = 3, constantAlpha = 0)
        for (opaqueAtBoundary in listOf(true, false)) {
            val out = ReferenceMasker.apply(canvas, listOf(m))
            val i = 10 * 30 + 15
            out.argb[i] = if (opaqueAtBoundary) ReferenceMasker.colourField(15, 10) else 0
            val report = PixelOracle.insideOpaque(canvas, out, listOf(m))
            assertTrue(report.passed, report.summary())
            assertTrue(report.ambiguousPixels > 0)
        }
    }

    @Test
    fun theCornersOfTheBoundingBoxBeyondTheSoftEdgeAreJudgedAsOutside() {
        // Placed so that the probed corner (cy - 1.05 ry) lies inside the 160 x 120 canvas at the FR-004 size.
        val m = MaskEllipse.fromFaceBox(30.0, 40.0, 40.0, 50.0)
        val canvas = PixelFixtures.noiseCanvas(160, 120, seed = 8)
        // r is about 1.48 at this pixel: inside the rectangle that holds the soft edge, but beyond it.
        val cornerX = Math.floor(m.cx + 1.05 * m.rx).toInt()
        val cornerY = Math.floor(m.cy - 1.05 * m.ry).toInt()
        assertTrue(m.radius(cornerX, cornerY) > 1.3)
        val changed = canvas.copy()
        changed.argb[cornerY * 160 + cornerX] = changed.argb[cornerY * 160 + cornerX] xor 0x00010101
        assertEquals(
            setOf(OracleCode.CHANGED_OUTSIDE_SOFT_EDGE),
            PixelOracle.unchangedOutside(canvas, changed, listOf(m)).codes,
        )
        // A change in the soft edge itself (r about 1.05) is allowed.
        val edgeX = Math.floor(m.cx + 1.05 * m.rx).toInt()
        val edgeY = Math.round(m.cy).toInt()
        assertTrue(m.radius(edgeX, edgeY) in 1.01..1.09)
        val edge = canvas.copy()
        edge.argb[edgeY * 160 + edgeX] = edge.argb[edgeY * 160 + edgeX] xor 0x00010101
        PixelOracle.unchangedOutside(canvas, edge, listOf(m)).assertPassed()
    }

    @Test
    fun maskPlansThatOverlapUseTheStrongestZone() {
        val a = MaskEllipse(20.0, 20.0, 10.0, 10.0)
        val b = MaskEllipse(34.0, 20.0, 10.0, 10.0)
        val canvas = PixelFixtures.noiseCanvas(60, 40, seed = 4, constantAlpha = 0)
        val out = ReferenceMasker.apply(canvas, listOf(a, b))
        PixelOracle.insideOpaque(canvas, out, listOf(a, b)).assertPassed()
        PixelOracle.unchangedOutside(canvas, out, listOf(a, b)).assertPassed()
    }

    @Test
    fun identicalBytesAndPixels() {
        PixelOracle.identicalBytes(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)).assertPassed()
        assertEquals(
            setOf(OracleCode.BYTES_DIFFER),
            PixelOracle.identicalBytes(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)).codes,
        )
        assertEquals(
            setOf(OracleCode.BYTES_DIFFER),
            PixelOracle.identicalBytes(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)).codes,
        )
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), PixelOracle.identicalBytes(ByteArray(0), ByteArray(0)).codes)
        val a = PixelFixtures.noiseCanvas(20, 20, seed = 1, constantAlpha = 255)
        val b = a.copy()
        b.argb[5] = b.argb[5] + 3 // blue channel + 3 (the noise leaves room: set explicitly below)
        val delta = PixelOracle.channelDelta(a.argb[5], b.argb[5])
        assertTrue(delta in 1..3)
        PixelOracle.identicalPixels(a, b, maxChannelDelta = 3).assertPassed()
        assertEquals(setOf(OracleCode.PIXELS_DIFFER), PixelOracle.identicalPixels(a, b, maxChannelDelta = 0).codes)
    }

    @Test
    fun decodingPngIsExactAndJpegIsLossy() {
        val canvas = PixelFixtures.noiseCanvas(40, 30, seed = 7)
        val decoded = PixelDecoder.decode(PixelFixtures.png(canvas))
        PixelOracle.identicalPixels(canvas, decoded).assertPassed()
        val jpeg = PixelDecoder.decode(PixelFixtures.jpeg(canvas))
        assertEquals(40, jpeg.width)
        assertTrue(PixelOracle.identicalPixels(canvas, jpeg).codes.contains(OracleCode.PIXELS_DIFFER))
    }

    /** A valid greyscale PNG of the given size whose pixels are all zero; a few hundred kilobytes for 144 megapixels. */
    private fun solidPng(width: Int, height: Int): ByteArray {
        fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            d.write(type.toByteArray())
            d.write(data)
            val crc = java.util.zip.CRC32()
            crc.update(type.toByteArray())
            crc.update(data)
            d.writeInt(crc.value.toInt())
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 0, 0, 0, 0))
        }
        chunk(out, "IHDR", header.toByteArray())
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { z ->
            val row = ByteArray(width + 1)
            repeat(height) { z.write(row) }
        }
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun aValidButHugeImageIsRefusedBeforeItsPixelsAreAllocated() {
        val small = PixelDecoder.decode(solidPng(64, 64))
        assertEquals(64, small.width)
        val huge = solidPng(12_000, 12_000) // 144 megapixels, above PixelDecoder.MAX_PIXELS
        assertTrue(huge.size < 1_000_000, "the test image is small on disk: ${huge.size} bytes")
        assertFailsWith<UndecodableImageException> { PixelDecoder.decode(huge) }
    }

    @Test
    fun undecodableAndOversizeInputIsRefusedNotCrashed() {
        assertFailsWith<UndecodableImageException> { PixelDecoder.decode(ByteArray(0)) }
        assertFailsWith<UndecodableImageException> { PixelDecoder.decode("not an image at all".toByteArray()) }
        val png = PixelFixtures.png(PixelFixtures.noiseCanvas(8, 8, seed = 1))
        assertFailsWith<UndecodableImageException> { PixelDecoder.decode(png.copyOf(png.size / 2)) }
        // Header announcing 60,000 x 60,000 pixels (3.6 billion): refused before any pixel buffer exists.
        val huge = png.copyOf()
        for (offset in intArrayOf(16, 20)) {
            huge[offset] = 0
            huge[offset + 1] = 0
            huge[offset + 2] = 0xEA.toByte()
            huge[offset + 3] = 0x60
        }
        assertFailsWith<UndecodableImageException> { PixelDecoder.decode(huge) }
    }

    @Test
    fun lossyDifferentialPairsNeedWholeBlocksWithOneBlockOfMargin() {
        val mask = MaskEllipse.fromFaceBox(96.0, 48.0, 64.0, 80.0)
        val masks = listOf(mask)
        val blocks = PixelOracle.mcuSafeBlocks(256, 192, masks)
        assertTrue(blocks.isNotEmpty())
        // Independent check of the helper: every pixel of a block and of its one-block margin has r <= 1.
        for (b in blocks) {
            for (y in b.y - 16 until b.y + 32) {
                for (x in b.x - 16 until b.x + 32) {
                    assertTrue(mask.radius(x, y) <= 1.0, "block at ${b.x},${b.y}")
                }
            }
        }
        val canvas = PixelFixtures.noiseCanvas(256, 192, seed = 11, constantAlpha = 255)
        val changed = PixelFixtures.perturbBlocks(canvas, blocks, seed = 12)
        val decodedA = PixelDecoder.decode(PixelFixtures.jpeg(canvas))
        val decodedB = PixelDecoder.decode(PixelFixtures.jpeg(changed))
        val outA = ReferenceMasker.apply(decodedA, masks)
        val outB = ReferenceMasker.apply(decodedB, masks)
        // The encoder-side pair differs in whole margin-safe blocks only; MEASURED with the JDK decoder: after the lossy
        // round trip the decoded pair still differs only where r <= 1 (the margin absorbs the chroma upsampling).
        PixelOracle.pairPrecondition(canvas, changed, masks, mcuSize = 16, minDifferingFraction = 0.1).assertPassed()
        PixelOracle.differentialPair(decodedA, decodedB, outA, outB, masks, minDifferingFraction = 0.1).assertPassed()
        // The same pair through a blur stub fails.
        val blurA = ReferenceMasker.apply(decodedA, masks, PlantedPixelDefect.BLUR_INSTEAD_OF_MASK)
        val blurB = ReferenceMasker.apply(decodedB, masks, PlantedPixelDefect.BLUR_INSTEAD_OF_MASK)
        assertEquals(
            setOf(OracleCode.DEPENDS_ON_MASKED_INPUT),
            PixelOracle.differentialPair(decodedA, decodedB, blurA, blurB, masks, minDifferingFraction = 0.1).codes,
        )
        // A pair that differs in a block at the rim (no margin) is refused instead of judged.
        val rim = PixelFixtures.perturbBlocks(canvas, listOf(Block(96, 48, 16)), seed = 13)
        val rimReport = PixelOracle.pairPrecondition(canvas, rim, masks, mcuSize = 16, minDifferingFraction = 0.0)
        assertEquals(setOf(OracleCode.INVALID_PAIR), rimReport.codes, rimReport.summary())
    }

    @Test
    fun aPairThatDiffersOutsideTheMasksOrNotAtAllIsNotJudged() {
        val masks = listOf(MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0))
        val a = PixelFixtures.noiseCanvas(120, 100, seed = 1)
        val outsideDiff = a.copy()
        outsideDiff.argb[0] = outsideDiff.argb[0] xor 0x00FFFFFF
        val out = ReferenceMasker.apply(a, masks)
        assertEquals(
            setOf(OracleCode.INVALID_PAIR),
            PixelOracle.differentialPair(a, outsideDiff, out, out, masks).codes,
        )
        assertEquals(setOf(OracleCode.PAIR_NOT_DIFFERENT), PixelOracle.differentialPair(a, a, out, out, masks).codes)
    }

    @Test
    fun theMaskedAreaMustLieAtTheScaledPositionAndSize() {
        val working = MaskEllipse.fromFaceBox(100.0, 60.0, 60.0, 80.0)
        val expected = working.scaled(0.5)
        val reference = PixelFixtures.noiseCanvas(160, 120, seed = 21, constantAlpha = 255)
        val tolerance = expected.smallerSemiAxis * MaskEllipse.SOFT_EDGE + 1.5
        fun check(output: Raster) = PixelOracle.maskedRegionMatches(
            reference,
            output,
            expected,
            changeThreshold = 8,
            positionTolerancePx = tolerance,
            semiAxisTolerancePx = tolerance,
            minCoverage = 0.9,
        )
        check(ReferenceMasker.apply(reference, listOf(expected))).assertPassed()
        val shifted = MaskEllipse(expected.cx + 8, expected.cy, expected.rx, expected.ry)
        assertTrue(OracleCode.GEOMETRY_POSITION in check(ReferenceMasker.apply(reference, listOf(shifted))).codes)
        val smaller = MaskEllipse(expected.cx, expected.cy, expected.rx * 0.7, expected.ry * 0.7)
        assertTrue(OracleCode.GEOMETRY_SIZE in check(ReferenceMasker.apply(reference, listOf(smaller))).codes)
        // Each axis on its own: a wrong width alone, a wrong height alone, a shift in y alone.
        val narrower = MaskEllipse(expected.cx, expected.cy, expected.rx * 0.5, expected.ry)
        // A half-width mask also covers only half of the expected ellipse.
        assertEquals(
            setOf(OracleCode.GEOMETRY_SIZE, OracleCode.GEOMETRY_COVERAGE),
            check(ReferenceMasker.apply(reference, listOf(narrower))).codes,
        )
        val lower = MaskEllipse(expected.cx, expected.cy, expected.rx, expected.ry * 0.5)
        assertEquals(
            setOf(OracleCode.GEOMETRY_SIZE, OracleCode.GEOMETRY_COVERAGE),
            check(ReferenceMasker.apply(reference, listOf(lower))).codes,
        )
        val movedDown = MaskEllipse(expected.cx, expected.cy + 8, expected.rx, expected.ry)
        assertTrue(OracleCode.GEOMETRY_POSITION in check(ReferenceMasker.apply(reference, listOf(movedDown))).codes)
        // Only a ring (0.9 < r <= 1) changed and the face inside stays: position and size fit, the coverage does not.
        val ring = reference.copy()
        for (y in 0 until ring.height) {
            for (x in 0 until ring.width) {
                val r = expected.radius(x, y)
                if (r > 0.9 && r <= 1.0) ring.argb[y * ring.width + x] = ReferenceMasker.colourField(x, y)
            }
        }
        assertEquals(setOf(OracleCode.GEOMETRY_COVERAGE), check(ring).codes)
        val atWorkingScale = ReferenceMasker.apply(reference, listOf(working))
        assertTrue(
            check(atWorkingScale).codes.isNotEmpty(),
            "a mask at the working-image coordinates is not at the output scale",
        )
        assertEquals(setOf(OracleCode.NOTHING_CHECKED), check(reference).codes)
    }

    @Test
    fun aFailedCallLeavesTheBufferExactlyAsItWas() {
        val masks =
            listOf(MaskEllipse.fromFaceBox(30.0, 20.0, 40.0, 50.0), MaskEllipse.fromFaceBox(100.0, 60.0, 30.0, 40.0))
        val before = PixelFixtures.noiseCanvas(160, 120, seed = 31)
        PixelOracle.unchangedAfterError(before, before.copy()).assertPassed()
        val onePixel = before.copy()
        onePixel.argb[17] = onePixel.argb[17] xor 1
        assertEquals(
            setOf(OracleCode.BUFFER_CHANGED_AFTER_ERROR),
            PixelOracle.unchangedAfterError(before, onePixel).codes,
        )
        // The first mask was applied before the second entry failed: partly masked buffer after the error.
        val partly = ReferenceMasker.apply(before, masks.take(1))
        assertEquals(
            setOf(OracleCode.BUFFER_CHANGED_AFTER_ERROR),
            PixelOracle.unchangedAfterError(before, partly).codes,
        )
        // On success all masks must be applied; a result with one mask skipped is reported by name.
        assertEquals(
            setOf(OracleCode.PARTLY_MASKED),
            PixelOracle.maskApplication(before, partly, masks, Expect.ALL_APPLIED, Fidelity.Exact).codes,
        )
        PixelOracle.maskApplication(
            before,
            ReferenceMasker.apply(before, masks),
            masks,
            Expect.ALL_APPLIED,
            Fidelity.Exact,
        ).assertPassed()
    }
}
