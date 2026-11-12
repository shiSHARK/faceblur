// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.MetadataPolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The EXIF writer and the typed metadata values (ADR 0005 decision 3; FR-FM-009, FR-FM-010, PRIV-019). */
class ExifWriterTest {
    private fun r(n: Long, d: Long = 1) = Rational(n, d)

    /** Every field at its maximum length (the ADR 0005 size budget). */
    private fun maximal() = PhotoMetadata.of(
        make = "M".repeat(64),
        model = "N".repeat(64),
        dateTimeOriginal = "2026:11:02 23:59:59",
        dateTimeDigitized = "2026:11:02 23:59:59",
        offsetTimeOriginal = "+14:00",
        offsetTimeDigitized = "-12:30",
        subSecTimeOriginal = "123456789",
        latitudeRef = 'S',
        latitude = listOf(r(89), r(59), r(5999, 100)),
        longitudeRef = 'W',
        longitude = listOf(r(179), r(59), r(5999, 100)),
        altitudeRef = 1,
        altitude = r(4294967295L, 1000),
        gpsTimeStamp = listOf(r(23), r(59), r(60)),
        gpsDateStamp = "2026:11:02",
        horizontalError = r(5, 1),
        imgDirectionRef = 'T',
        imgDirection = r(35999, 100),
    )

    private val all = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)

    @Test
    fun stripAllWritesExactlyOrientationOne() {
        val expected =
            byteArrayOf(
                0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22, // marker, length 34
                0x45, 0x78, 0x69, 0x66, 0x00, 0x00, // "Exif\0\0"
                0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // "MM", 42, IFD0 at 8
                0x00, 0x01, // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // Orientation SHORT 1 = 1
                0x00, 0x00, 0x00, 0x00, // no next IFD (no thumbnail)
            )
        assertContentEquals(expected, ExifWriter.app1(null))
        assertContentEquals(expected, ExifWriter.app1(maximal().restrictTo(MetadataPolicy.StripAll)))
    }

    @Test
    fun theLargestPossibleSegmentStaysWithinTheLimitAndValidates() {
        val m = maximal()
        assertNotNull(m.gps)
        assertNotNull(m.gps.altitude)
        assertNotNull(m.gps.imgDirection)
        val seg = ExifWriter.app1(m.restrictTo(all))
        println(
            "EXIF APP1 with every allowlisted field at maximum length: ${seg.size} bytes " +
                "(limit ${Exif.MAX_APP1_BYTES})",
        )
        assertTrue(seg.size <= Exif.MAX_APP1_BYTES, "${seg.size} bytes")
        val out = JpegRebuilder.rebuild(JpegTestData.encoderOutput(), seg)
        assertEquals(emptyList(), OutputValidator.findings(out, all))
        // The same file under narrower policies: the extra groups are not allowed there.
        assertTrue(Finding.EXIF_TAG_NOT_ALLOWED in OutputValidator.findings(out, MetadataPolicy.StripAll))
        assertTrue(
            Finding.EXIF_TAG_NOT_ALLOWED in OutputValidator.findings(
                out,
                MetadataPolicy.KeepAllowlist(dateTime = true),
            ),
        )
    }

    @Test
    fun eachGroupValidatesUnderItsOwnPolicyOnly() {
        val m = maximal()
        for (policy in listOf(
            MetadataPolicy.KeepAllowlist(gps = true),
            MetadataPolicy.KeepAllowlist(dateTime = true),
            MetadataPolicy.KeepAllowlist(camera = true),
        )) {
            val out = JpegRebuilder.rebuild(JpegTestData.encoderOutput(), ExifWriter.app1(m.restrictTo(policy)))
            assertEquals(emptyList(), OutputValidator.findings(out, policy), "$policy")
            assertTrue(
                Finding.EXIF_TAG_NOT_ALLOWED in OutputValidator.findings(out, MetadataPolicy.StripAll),
                "$policy under StripAll",
            )
        }
        val dateOnly = assertNotNull(m.restrictTo(MetadataPolicy.KeepAllowlist(dateTime = true)))
        assertNull(dateOnly.gps)
        assertNull(dateOnly.make)
        assertNull(
            PhotoMetadata.of(make = "Cam").restrictTo(MetadataPolicy.KeepAllowlist(gps = true)),
            "nothing left to keep",
        )
    }

    @Test
    fun valuesAreBoundedAndInvalidOnesAreDropped() {
        val huge = "A".repeat(5_000_000)
        val m =
            PhotoMetadata.of(
                make = huge,
                model = "Caméra",
                dateTimeOriginal = "2026:13:02 10:00:00",
                dateTimeDigitized = "2026:11:02 10:00:00 extra",
                offsetTimeOriginal = "+15:00",
                offsetTimeDigitized = "+01:60",
                subSecTimeOriginal = "1234567890",
            )
        assertNull(m.make, "5 MB make")
        assertNull(m.model, "non-ASCII model")
        assertNull(m.dateTimeOriginal)
        assertNull(m.dateTimeDigitized)
        assertNull(m.offsetTimeOriginal)
        assertNull(m.offsetTimeDigitized)
        assertNull(m.subSecTimeOriginal)
        assertNull(PhotoMetadata.of(make = "M".repeat(65)).make)
        assertEquals("M".repeat(64), PhotoMetadata.of(make = "M".repeat(64)).make, "control: 64 characters are kept")
        assertNull(PhotoMetadata.of(make = "").make)
        assertNull(PhotoMetadata.of(make = "a\u0000b").make, "embedded NUL")
        assertEquals(
            "2026:11:02 10:00:00",
            PhotoMetadata.of(dateTimeOriginal = "2026:11:02 10:00:00").dateTimeOriginal,
            "control",
        )
    }

    @Test
    fun gpsIsKeptExactlyOrNotAtAll() {
        fun gps(lat: List<Rational>, lon: List<Rational> = listOf(r(13), r(24), r(1234, 100)), latRef: Char = 'N') =
            PhotoMetadata.of(latitudeRef = latRef, latitude = lat, longitudeRef = 'E', longitude = lon).gps
        val exact = assertNotNull(gps(listOf(r(42), r(41), r(5678, 100))))
        assertEquals(listOf(r(42), r(41), r(5678, 100)), exact.latitude, "exact rationals, no rounding")
        assertNull(gps(listOf(r(91), r(0), r(0))), "latitude above 90")
        assertNull(gps(listOf(r(90), r(1), r(0))), "90 degrees plus a minute")
        assertNull(gps(listOf(r(42), r(60), r(0))), "60 minutes")
        assertNull(gps(listOf(r(42), r(1), r(1, 0))), "zero denominator")
        assertNull(gps(listOf(r(42), r(1), r(Rational.U32_MAX + 1))), "numerator above 32 bits")
        assertNull(gps(listOf(r(42), r(1))), "two values")
        assertNull(gps(listOf(r(42), r(1), r(1)), latRef = 'X'))
        assertNull(gps(listOf(r(42), r(1), r(1)), lon = listOf(r(181), r(0), r(0))), "longitude above 180")
        assertNull(
            PhotoMetadata.of(latitudeRef = 'N', latitude = listOf(r(1), r(1), r(1))).gps,
            "latitude without longitude",
        )
        val partial =
            assertNotNull(
                PhotoMetadata
                    .of(
                        latitudeRef = 'N',
                        latitude = listOf(r(1), r(1), r(1)),
                        longitudeRef = 'E',
                        longitude = listOf(r(1), r(1), r(1)),
                        altitudeRef = 2,
                        altitude = r(10),
                        gpsTimeStamp = listOf(r(24), r(0), r(0)),
                        gpsDateStamp = "2026-11-02",
                        horizontalError = r(1, 0),
                        imgDirectionRef = 'T',
                        imgDirection = r(360),
                    ).gps,
            )
        assertNull(partial.altitude, "altitude reference 2")
        assertNull(partial.timeStamp, "24 h")
        assertNull(partial.dateStamp)
        assertNull(partial.horizontalError)
        assertNull(partial.imgDirection, "360 degrees")
    }

    /**
     * Dates are real calendar days and every digit is an ASCII digit on every platform (this test also runs on
     * Kotlin/Native, where a `\d` pattern could match other scripts' digits).
     */
    @Test
    fun datesAreCalendarDaysWithAsciiDigitsOnly() {
        val accepted = listOf("2024:02:29", "2000:02:29", "2026:04:30", "2026:12:31", "2026:01:01")
        val refused =
            listOf("2026:02:29", "1900:02:29", "2026:02:31", "2026:04:31", "2026:00:10", "2026:13:01", "2026:01:00")
        for (d in accepted) {
            assertTrue(PhotoMetadata.validDate(d), d)
            assertTrue(PhotoMetadata.validDateTime("$d 10:11:12"), d)
        }
        for (d in refused) {
            assertFalse(PhotoMetadata.validDate(d), d)
            assertFalse(PhotoMetadata.validDateTime("$d 10:11:12"), d)
        }
        val arabicIndic = "\u0662\u0660\u0662\u0666" // 2026 in Arabic-Indic digits
        val fullWidth = "\uFF12\uFF10\uFF12\uFF16" // 2026 in full-width digits
        for (year in listOf(arabicIndic, fullWidth)) {
            assertFalse(PhotoMetadata.validDateTime("$year:11:02 10:11:12"))
            assertFalse(PhotoMetadata.validDate("$year:11:02"))
        }
        assertFalse(PhotoMetadata.validOffset("+\u0660\u0662:00"))
        assertNull(PhotoMetadata.of(subSecTimeOriginal = "\u0661\u0662\u0663").subSecTimeOriginal)
        assertEquals("123", PhotoMetadata.of(subSecTimeOriginal = "123").subSecTimeOriginal, "control")
        val gps =
            PhotoMetadata.of(
                latitudeRef = 'N',
                latitude = listOf(r(1), r(1), r(1)),
                longitudeRef = 'E',
                longitude = listOf(r(1), r(1), r(1)),
                gpsDateStamp = "2026:02:31",
            ).gps
        assertNull(assertNotNull(gps).dateStamp, "GPSDateStamp on 31 February")
    }

    /** Negative control (PRIV-019): a writer that copied a raw tag (ImageDescription) would fail validation. */
    @Test
    fun aRawCopiedTagFailsTheValidator() {
        val out = JpegRebuilder.rebuild(JpegTestData.encoderOutput(), JpegTestData.sourceExif())
        val f = OutputValidator.findings(out, all)
        assertTrue(Finding.EXIF_TAG_NOT_ALLOWED in f, "$f")
        assertTrue(Finding.ORIENTATION_NOT_1 in f, "$f")
    }

    @Test
    fun rationalAndMetadataBasics() {
        assertEquals(r(1, 2), r(1, 2))
        assertEquals(r(1, 2).hashCode(), r(1, 2).hashCode())
        assertEquals("1/2", r(1, 2).toString())
        assertTrue(maximal().hasCamera && maximal().hasDateTime)
    }
}
