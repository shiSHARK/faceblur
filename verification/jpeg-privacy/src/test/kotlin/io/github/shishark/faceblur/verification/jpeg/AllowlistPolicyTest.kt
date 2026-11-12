// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ST-042 AC2 and PRIV-010, PRIV-019: the allowlist policy, required tags from the input, and value bounds. */
class AllowlistPolicyTest {
    private val gpsOnly = MetadataPolicy.KeepAllowlist(gps = true)

    private fun jpeg(configure: (TiffBuilder) -> Unit): ByteArray {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        configure(t)
        return JpegBuilder().exif(t.build()).build()
    }

    private fun gps(t: TiffBuilder, latDegrees: Long = 48): MutableList<Entry> = mutableListOf(
        t.ascii(0x01, "N"),
        t.rational(0x02, latDegrees to 1L, 8L to 1L, 3000L to 100L),
        t.ascii(0x03, "E"),
        t.rational(0x04, 11L to 1L, 34L to 1L, 1500L to 100L),
    )

    private val withGps get() = jpeg { t -> t.gps = gps(t) }
    private val withoutGps get() = jpeg { }

    @Test
    fun gpsIsAcceptedWhenThePolicyNamesIt() {
        val report = PrivacyChecker.check(withGps, CheckOptions(policy = gpsOnly))
        assertTrue(report.passed, report.summary())
        assertTrue(ExifTag(ExifIfd.GPS, 0x02) in report.exifTags)
    }

    @Test
    fun gpsIsAFailureUnderStripAll() {
        assertEquals(setOf(FindingCode.GPS_NOT_ALLOWED), PrivacyChecker.check(withGps).failureCodes)
    }

    @Test
    fun aMissingNamedTagIsOnlyInformationWhenTheInputDidNotCarryIt() {
        val policy = gpsOnly.withRequiredFrom(withoutGps)
        assertEquals(emptySet(), policy.requiredTags)
        val report = PrivacyChecker.check(withoutGps, CheckOptions(policy = policy))
        assertTrue(report.passed, report.summary())
        assertEquals(setOf(FindingCode.ALLOWLISTED_TAG_ABSENT), report.informationCodes)
    }

    @Test
    fun aMissingNamedTagIsAFailureWhenTheInputCarriedIt() {
        val policy = gpsOnly.withRequiredFrom(withGps)
        assertEquals(4, policy.requiredTags.size)
        val report = PrivacyChecker.check(withoutGps, CheckOptions(policy = policy))
        assertEquals(setOf(FindingCode.REQUIRED_TAG_MISSING), report.failureCodes)
        // Required tags that are present satisfy the policy, the other GPS tags stay information.
        val ok = PrivacyChecker.check(withGps, CheckOptions(policy = policy))
        assertTrue(ok.passed, ok.summary())
    }

    @Test
    fun anyOtherTagIsFlagged() {
        val extraCamera = jpeg { t ->
            t.gps = gps(t)
            t.ifd0.add(t.ascii(0x010F, "ACME"))
        }
        assertEquals(
            setOf(FindingCode.EXIF_TAG_NOT_ALLOWED),
            PrivacyChecker.check(extraCamera, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val blob = jpeg { t ->
            t.gps = gps(t)
            t.gps!!.add(t.undefined(0x1B, "ASCII\u0000\u0000\u0000${PlantedDefects.CANARY}".toByteArray()))
        }
        val options = CheckOptions(policy = gpsOnly, canaries = listOf(PlantedDefects.CANARY.toByteArray()))
        assertEquals(
            setOf(FindingCode.EXIF_TAG_NOT_ALLOWED, FindingCode.CANARY_FOUND),
            PrivacyChecker.check(blob, options).failureCodes,
        )
        val subIfd = jpeg { t ->
            t.gps = gps(t)
            t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05"))
        }
        assertEquals(
            setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED),
            PrivacyChecker.check(subIfd, CheckOptions(policy = gpsOnly)).failureCodes,
        )
    }

    @Test
    fun anAllowlistMustNameAGroup() {
        assertFailsWith<IllegalArgumentException> { MetadataPolicy.KeepAllowlist() }
    }

    @Test
    fun gpsValuesAreRangeChecked() {
        val badLatitude = jpeg { t -> t.gps = gps(t, latDegrees = 91) }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(badLatitude, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val zeroDenominator = jpeg { t ->
            t.gps = gps(t)
            t.gps!![1] = t.rational(0x02, 48L to 0L, 8L to 1L, 3L to 1L)
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(zeroDenominator, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val badRef = jpeg { t ->
            t.gps = gps(t)
            t.gps!![0] = t.ascii(0x01, "X")
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(badRef, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val wrongType = jpeg { t ->
            t.gps = gps(t)
            t.gps!![1] = t.short(0x02, 1, 2, 3)
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(wrongType, CheckOptions(policy = gpsOnly)).failureCodes,
        )
    }

    @Test
    fun otherGpsValuesAreChecked() {
        val badDate = jpeg { t ->
            t.gps = gps(t)
            t.gps!!.add(t.ascii(0x1D, "2026:13:40"))
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(badDate, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val badTime = jpeg { t ->
            t.gps = gps(t)
            t.gps!!.add(t.rational(0x07, 25L to 1L, 0L to 1L, 0L to 1L))
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(badTime, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val badDirection = jpeg { t ->
            t.gps = gps(t)
            t.gps!!.add(t.rational(0x11, 360L to 1L))
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(badDirection, CheckOptions(policy = gpsOnly)).failureCodes,
        )
    }

    @Test
    fun withRequiredFromRefusesAnInputWhoseExifStopsParsingEarly() {
        // IFD0 -> IFD1 is walked before the GPS IFD; IFD1 points its thumbnail outside the segment, so the strict
        // reader stops before it has seen the GPS tags. Deriving "no GPS expected" from that would be a vacuous pass.
        val input = jpeg { t ->
            t.gps = gps(t)
            t.ifd1 = mutableListOf(t.long(0x0201, 1_000_000L), t.long(0x0202, 10L))
        }
        val everything = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        val inputReport = PrivacyChecker.check(input, CheckOptions(policy = everything))
        assertTrue(FindingCode.EXIF_UNPARSEABLE in inputReport.failureCodes, "premise: the strict reader stops")
        val e = assertFailsWith<IllegalArgumentException> { gpsOnly.withRequiredFrom(input) }
        assertTrue("EXIF_UNPARSEABLE" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalArgumentException> { gpsOnly.withRequiredFrom(ByteArray(10)) }
    }

    @Test
    fun otherValueBoundsAreChecked() {
        fun reportOf(configure: (TiffBuilder) -> Unit) = PrivacyChecker.check(
            jpeg { t ->
                t.gps = gps(t)
                configure(t)
            },
            CheckOptions(policy = gpsOnly),
        )
        val minutes = jpeg { t ->
            t.gps = gps(t)
            t.gps!![1] = t.rational(0x02, 48L to 1L, 60L to 1L, 0L to 1L)
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(minutes, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        val seconds = jpeg { t ->
            t.gps = gps(t)
            t.gps!![1] = t.rational(0x02, 48L to 1L, 8L to 1L, 60L to 1L)
        }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(seconds, CheckOptions(policy = gpsOnly)).failureCodes,
        )
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            reportOf { t -> t.gps!!.add(t.ascii(0x1D, "2026:02:30")) }.failureCodes,
        )
        assertTrue(reportOf { t -> t.gps!!.add(t.ascii(0x1D, "2028:02:29")) }.passed, "2028 is a leap year")
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            reportOf { t -> t.gps!!.add(t.rational(0x06, 5_000_000L to 1L)) }.failureCodes,
        )
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            reportOf { t -> t.gps!!.add(t.rational(0x1F, 5_000_000L to 1L)) }.failureCodes,
        )
        assertTrue(reportOf { t -> t.gps!!.add(t.rational(0x1F, 5L to 1L)) }.passed)
        val dateTime = MetadataPolicy.KeepAllowlist(dateTime = true)
        for ((value, valid) in listOf(
            "2026:02:30 10:00:00" to false,
            "2028:02:29 10:00:00" to true,
            "0000:01:01 10:00:00" to false,
        )) {
            val file = jpeg { t -> t.exif = mutableListOf(t.ascii(0x9003, value)) }
            assertEquals(valid, PrivacyChecker.check(file, CheckOptions(policy = dateTime)).passed, value)
        }
    }

    @Test
    fun cameraValuesAreBoundedPrintableAscii() {
        val camera = MetadataPolicy.KeepAllowlist(camera = true)
        val ok = jpeg { t ->
            t.ifd0.add(t.ascii(0x010F, "A".repeat(64)))
            t.ifd0.add(t.ascii(0x0110, "Model X1"))
        }
        assertTrue(PrivacyChecker.check(ok, CheckOptions(policy = camera)).passed)
        val tooLong = jpeg { t -> t.ifd0.add(t.ascii(0x010F, "A".repeat(65))) }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(tooLong, CheckOptions(policy = camera)).failureCodes,
        )
        val nonAscii = jpeg { t -> t.ifd0.add(Entry(0x0110, 2, 5, byteArrayOf(0x4D, 0xE9.toByte(), 0x78, 0x69, 0))) }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(nonAscii, CheckOptions(policy = camera)).failureCodes,
        )
        val huge = jpeg { t -> t.ifd0.add(t.ascii(0x010F, "B".repeat(60000))) }
        val codes = PrivacyChecker.check(huge, CheckOptions(policy = camera)).failureCodes
        assertEquals(setOf(FindingCode.EXIF_VALUE_INVALID, FindingCode.EXIF_TOO_LARGE), codes)
        val rawMakerNoteLookalike = jpeg { t -> t.ifd0.add(t.undefined(0x010F, "ACME".toByteArray())) }
        assertEquals(
            setOf(FindingCode.EXIF_VALUE_INVALID),
            PrivacyChecker.check(rawMakerNoteLookalike, CheckOptions(policy = camera)).failureCodes,
        )
    }

    @Test
    fun dateTimeValuesAreRangeChecked() {
        val dateTime = MetadataPolicy.KeepAllowlist(dateTime = true)
        val ok = jpeg { t ->
            t.exif = mutableListOf(
                t.ascii(0x9003, "2026:11:01 20:30:00"),
                t.ascii(0x9004, "2026:11:01 20:30:01"),
                t.ascii(0x9011, "+01:00"),
                t.ascii(0x9291, "123456789"),
            )
        }
        assertTrue(PrivacyChecker.check(ok, CheckOptions(policy = dateTime)).passed)
        for (bad in listOf(
            Triple(0x9003, "2026:13:01 20:30:00", "month"),
            Triple(0x9003, "2026-11-01 20:30:00", "format"),
            Triple(0x9011, "+99:00", "offset"),
            Triple(0x9291, "1234567890", "subsec length"),
        )) {
            val file = jpeg { t -> t.exif = mutableListOf(t.ascii(bad.first, bad.second)) }
            val report = PrivacyChecker.check(file, CheckOptions(policy = dateTime))
            assertEquals(
                setOf(FindingCode.EXIF_VALUE_INVALID),
                report.failureCodes,
                "${bad.third}: ${report.summary()}",
            )
        }
    }

    @Test
    fun theLargestAllowlistedExifFitsTheSegmentLimit() {
        // Cross-check of ADR 0005 decision 6 with this checker's own writer: all fields at maximum length.
        val all = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        val file = jpeg { t ->
            t.ifd0.add(t.ascii(0x010F, "M".repeat(64)))
            t.ifd0.add(t.ascii(0x0110, "N".repeat(64)))
            t.exif = mutableListOf(
                t.ascii(0x9003, "2026:11:01 20:30:00"),
                t.ascii(0x9004, "2026:11:01 20:30:00"),
                t.ascii(0x9011, "+01:00"),
                t.ascii(0x9012, "+01:00"),
                t.ascii(0x9291, "123456789"),
            )
            t.gps = mutableListOf(
                t.byte(0x00, 2, 3, 0, 0),
                t.ascii(0x01, "N"),
                t.rational(0x02, 48L to 1L, 8L to 1L, 3000L to 100L),
                t.ascii(0x03, "E"),
                t.rational(0x04, 11L to 1L, 34L to 1L, 1500L to 100L),
                t.byte(0x05, 0),
                t.rational(0x06, 520L to 1L),
                t.rational(0x07, 12L to 1L, 30L to 1L, 5L to 1L),
                t.ascii(0x10, "T"),
                t.rational(0x11, 90L to 1L),
                t.ascii(0x1D, "2026:11:01"),
                t.rational(0x1F, 5L to 1L),
            )
        }
        val report = PrivacyChecker.check(file, CheckOptions(policy = all))
        assertTrue(report.passed, report.summary())
        println("MEASURED all-fields-at-maximum EXIF segment: ${report.exifSegmentBytes} bytes (limit 1024)")
        assertTrue(report.exifSegmentBytes!! <= 1024, "segment is ${report.exifSegmentBytes} bytes")
        assertTrue(report.informationCodes.isEmpty(), "every allowlisted field is present")
    }
}
