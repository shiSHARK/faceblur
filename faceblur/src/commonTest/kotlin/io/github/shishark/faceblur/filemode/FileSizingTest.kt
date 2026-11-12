// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OutputFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Exact-value tests of the size arithmetic and the options' ranges (ADR 0005 decision 1, FR-FM-003, FR-FM-018). */
class FileSizingTest {
    private fun plan(w: Int, h: Int, options: FileOptions = FileOptions(), orientation: Int = 1) =
        FileSizing.plan(w, h, orientation, options)

    @Test
    fun defaultsDetectAt3500AndNeverUpscale() {
        val p = plan(5000, 3750)
        assertEquals(Size(5000, 3750), p.source)
        assertEquals(Size(3500, 2625), p.working)
        assertEquals(Size(3500, 2625), p.output)
        assertTrue(p.decodeReduced)
        assertFalse(p.scaleAfterMasking)
        assertEquals(Size(2625, 3500), plan(3750, 5000).working, "portrait")
        val small = plan(2000, 1500)
        assertEquals(Size(2000, 1500), small.working, "a smaller source keeps its size")
        assertFalse(small.decodeReduced)
        assertEquals(Size(3500, 2917), plan(6000, 5000).working, "5000 * 3500 / 6000 = 2916.67 rounds to 2917")
    }

    @Test
    fun theOutputIsScaledAfterMaskingOnlyWhenSmaller() {
        val p = plan(5000, 3750, FileOptions(maxOutputLongSide = 1000))
        assertEquals(Size(3500, 2625), p.working, "detection still runs at 3,500 px")
        assertEquals(Size(1000, 750), p.output)
        assertTrue(p.scaleAfterMasking)
        assertEquals(
            Size(2000, 1500),
            plan(2000, 1500, FileOptions(maxOutputLongSide = 1000)).working,
            "detected at 2,000 px",
        )
        assertEquals(Size(1000, 750), plan(2000, 1500, FileOptions(maxOutputLongSide = 1000)).output)
        val same = plan(5000, 3750, FileOptions(maxOutputLongSide = 4000))
        assertEquals(same.working, same.output, "an output side at or above the working size never upscales")
        assertEquals(Size(64, 48), plan(4000, 3000, FileOptions(maxOutputLongSide = 64)).output)
    }

    @Test
    fun theWorkingSizeIsNeverReducedToFitThePixelLimit() {
        val tight = FileOptions(maxLongSide = 5000, maxDecodedPixels = 13_000_000)
        val e = assertFailsWith<FaceMaskException.ImageTooLarge> { plan(5000, 3750, tight) }
        assertEquals(5000, e.width)
        assertEquals(3750, e.height)
        assertEquals(13_000_000L, e.limit)
        assertTrue(
            e.message!!.contains("maxDecodedPixels = 13000000") && e.message!!.contains("maxLongSide"),
            e.message,
        )
        assertFailsWith<FaceMaskException.ImageTooLarge> {
            plan(5000, 3750, FileOptions(maxLongSide = 5000, maxOutputLongSide = 1000, maxDecodedPixels = 13_000_000))
        }
        assertEquals(Size(3500, 2625), plan(5000, 3750, FileOptions(maxDecodedPixels = 13_000_000)).working, "control")
        assertFailsWith<FaceMaskException.ImageTooLarge>("source size above the default limit") {
            plan(6000, 5000, FileOptions(maxLongSide = 0))
        }
        assertEquals(
            Size(5000, 5000),
            plan(5000, 5000, FileOptions(maxLongSide = 0)).working,
            "25 MP exactly is allowed",
        )
    }

    @Test
    fun theSourceCeilingIsFixedAt200Megapixels() {
        assertEquals(Size(3500, 2734), plan(16_000, 12_500).working, "200,000,000 px exactly is allowed")
        assertEquals(
            Size(3500, 2734),
            plan(16_000, 12_500, FileOptions(maxDecodedPixels = 13_000_000)).working,
            "a 200 MP source succeeds at the 13 MP limit (FR-016 e)",
        )
        val e = assertFailsWith<FaceMaskException.ImageTooLarge> { plan(16_001, 12_500) }
        assertEquals(200_000_000L, e.limit)
        assertTrue(e.message!!.contains("the fixed source ceiling of 200000000 pixels"), e.message)
        assertFailsWith<FaceMaskException.ImageTooLarge> { plan(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<FaceMaskException.UnsupportedImage> { plan(0, 100) }
        assertFailsWith<FaceMaskException.UnsupportedImage> { plan(100, -1) }
    }

    /** ADR 0005 decision 1 step 2: the per-side limit on the working size from the header, for any maxLongSide. */
    @Test
    fun aWorkingSideAbove65535IsRefusedFromTheHeader() {
        val e = assertFailsWith<FaceMaskException.ImageTooLarge> { plan(70_000, 100, FileOptions(maxLongSide = 0)) }
        assertEquals(70_000, e.width)
        assertEquals(100, e.height)
        assertTrue(e.message!!.contains("the limit of 65535 px per side"), e.message)
        assertFalse(e.message!!.contains("2147483647"), "the message names the side limit, not a pixel count")
        val unbounded = FileOptions(maxLongSide = 100_000) // maxLongSide has no upper bound
        assertFailsWith<FaceMaskException.ImageTooLarge> { plan(70_000, 100, unbounded) }
        assertFailsWith<FaceMaskException.ImageTooLarge> { plan(70_000, 100, FileOptions(maxLongSide = Int.MAX_VALUE)) }
        val source = FileOptions(maxLongSide = 0)
        for ((w, h, o) in listOf(
            Triple(65_536, 1, 1),
            Triple(1, 65_536, 1),
            Triple(1, 65_536, 6),
            Triple(65_536, 1, 8),
        )) {
            assertFailsWith<FaceMaskException.ImageTooLarge>("$w x $h, orientation $o") { plan(w, h, source, o) }
        }
        assertEquals(Size(65_535, 1), plan(65_535, 1, source).working, "control: 65,535 x 1")
        assertEquals(
            Size(65_535, 1),
            plan(1, 65_535, source, orientation = 6).working,
            "control: swapped by orientation 6",
        )
        assertEquals(
            Size(65_535, 100),
            plan(65_535, 100, unbounded).working,
            "control: 65,535 with maxLongSide 100,000",
        )
        assertEquals(
            Size(65_535, 100),
            plan(65_535, 100, FileOptions(maxLongSide = 0)).working,
            "control: the limit itself",
        )
        assertEquals(Size(3500, 5), plan(70_000, 100).working, "control: the default working size reduces it")
    }

    @Test
    fun orientationsFiveToEightSwapTheAxes() {
        for (o in 1..8) {
            val p = plan(4000, 3000, orientation = o)
            if (o >=
                5
            ) {
                assertEquals(Size(2625, 3500), p.working, "orientation $o")
            } else {
                assertEquals(Size(3500, 2625), p.working, "orientation $o")
            }
        }
        assertEquals(Size(4000, 3000), FileSizing.oriented(4000, 3000, 9), "invalid orientation is treated as 1")
    }

    @Test
    fun roundingIsHalfUpAndTheShortSideIsAtLeastOnePixel() {
        assertEquals(Size(3500, 1), FileSizing.reduce(Size(10_000, 1), 3500))
        assertEquals(Size(3500, 1), FileSizing.reduce(Size(7000, 1), 3500), "0.5 rounds up to 1")
        assertEquals(Size(3500, 2), FileSizing.reduce(Size(7000, 3), 3500), "1.5 rounds up to 2")
        assertEquals(Size(3500, 1750), FileSizing.reduce(Size(3501, 1751), 3500), "1750.49 rounds down")
        assertEquals(Size(1, 3500), FileSizing.reduce(Size(1, 10_000), 3500))
        assertEquals(Size(10, 10), FileSizing.reduce(Size(10, 10), 0))
        assertEquals(25_000_000L, Size(5000, 5000).pixels)
    }

    @Test
    fun fileOptionDefaultsAndRanges() {
        val d = FileOptions()
        assertEquals(3500, d.maxLongSide)
        assertEquals(0, d.maxOutputLongSide)
        assertEquals(25_000_000, d.maxDecodedPixels)
        assertEquals(OutputFormat.Jpeg(90), d.format)
        assertEquals(MetadataPolicy.StripAll, d.metadata)
        val bad =
            listOf<Pair<String, () -> Any>>(
                "maxLongSide" to { FileOptions(maxLongSide = 3499) },
                "maxLongSide" to { FileOptions(maxLongSide = -1) },
                "maxOutputLongSide" to { FileOptions(maxOutputLongSide = 63) },
                "maxOutputLongSide" to { FileOptions(maxOutputLongSide = -5) },
                "maxDecodedPixels" to { FileOptions(maxDecodedPixels = 12_999_999) },
                "maxDecodedPixels" to { FileOptions(maxDecodedPixels = 100_000_001) },
                "quality" to { OutputFormat.Jpeg(0) },
                "quality" to { OutputFormat.Jpeg(101) },
                "metadata" to { MetadataPolicy.KeepAllowlist() },
            )
        for ((parameter, make) in bad) {
            assertEquals(
                parameter,
                assertFailsWith<FaceMaskException.InvalidConfiguration>(parameter) {
                    make()
                }.parameter,
            )
        }
        for (bad in listOf<() -> Any>({ FileOptions(maxLongSide = 1) }, { FileOptions(maxOutputLongSide = 1) })) {
            assertFailsWith<FaceMaskException.InvalidConfiguration> { bad() }
        }
        // Controls: every bound itself is accepted.
        FileOptions(maxOutputLongSide = 3_499)
        FileOptions(maxLongSide = 0, maxOutputLongSide = 64, maxDecodedPixels = 13_000_000)
        FileOptions(maxLongSide = 3500, maxDecodedPixels = 100_000_000, format = OutputFormat.Jpeg(1))
        OutputFormat.Jpeg(100)
        val built =
            FileOptions
                .Builder()
                .maxLongSide(5000)
                .maxOutputLongSide(1000)
                .maxDecodedPixels(30_000_000)
                .format(OutputFormat.Jpeg(80))
                .metadata(MetadataPolicy.KeepAllowlist(gps = true))
                .build()
        assertEquals(
            FileOptions(5000, 1000, 30_000_000, OutputFormat.Jpeg(80), MetadataPolicy.KeepAllowlist(gps = true)),
            built,
        )
        assertEquals(
            built.hashCode(),
            FileOptions(
                5000,
                1000,
                30_000_000,
                OutputFormat.Jpeg(80),
                MetadataPolicy.KeepAllowlist(gps = true),
            ).hashCode(),
        )
        assertNotEquals(FileOptions(), built)
        assertTrue(built.toString().contains("KeepAllowlist(gps=true, dateTime=false, camera=false)"))
        assertEquals("StripAll", MetadataPolicy.StripAll.toString())
        assertNotEquals<MetadataPolicy>(
            MetadataPolicy.KeepAllowlist(gps = true),
            MetadataPolicy.KeepAllowlist(camera = true),
        )
        assertEquals(OutputFormat.Jpeg(90).hashCode(), OutputFormat.Jpeg().hashCode())
        assertEquals("Jpeg(quality=90)", OutputFormat.Jpeg().toString())
    }
}
