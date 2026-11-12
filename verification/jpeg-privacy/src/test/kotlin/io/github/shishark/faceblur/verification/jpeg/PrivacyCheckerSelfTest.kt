// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Self-test of the checker (ST-042 AC1, PRIV-007, PRIV-001): every planted defect must make it fail with the
 * expected finding names, and the correct strip-all shape must produce no finding at all (negative controls).
 */
class PrivacyCheckerSelfTest {
    @TestFactory
    fun correctStripAllShapeProducesNoFindings(): List<DynamicTest> = PlantedDefects.clean().map { clean ->
        DynamicTest.dynamicTest(clean.name) {
            val report = PrivacyChecker.check(clean.bytes, clean.options)
            assertTrue(report.passed, report.summary())
            assertEquals(
                emptyList(),
                report.findings,
                "a correct strip-all file must produce no finding, not even information",
            )
            assertEquals(1, report.orientation)
        }
    }

    @TestFactory
    fun everyPlantedDefectIsReportedByNameAndFails(): List<DynamicTest> = PlantedDefects.defects().map { defect ->
        DynamicTest.dynamicTest(defect.name) {
            val report = PrivacyChecker.check(defect.bytes, defect.options)
            assertFalse(report.passed, "${defect.name} must fail")
            assertEquals(defect.expectedFailures, report.failureCodes, report.summary())
        }
    }

    @Test
    fun theCatalogueCoversEveryDefectNamedInTheStory() {
        val names = PlantedDefects.defects().map { it.name }.toSet()
        val storyItems = listOf(
            "gps-block", "exif-thumbnail-ifd1-with-embedded-jpeg", "xmp-packet", "iptc-app13", "comment",
            "makernote-canary-in-exif-subifd", "icc-canary-description", "mpf-segment-and-second-image-after-eoi",
            "byte-after-eoi", "second-soi", "second-orientation-tag", "extra-exif-tags",
            "exif-segment-above-size-limit",
            "no-app1-at-all", "orientation-6", "exif-ifd1-without-thumbnail-data", "exif-sub-ifd",
        )
        assertTrue(names.containsAll(storyItems), "missing: ${storyItems - names}")
    }

    @Test
    fun everyFailureCodeIsPlantedAndAssertedByName() {
        // Codes asserted by name in other tests, with the reason they are not in the catalogue:
        val elsewhere = mapOf(
            FindingCode.FILE_TOO_LARGE to "needs a file path: HostileInputTest.theSizeCapStopsTheCheckerBeforeReading",
            FindingCode.REQUIRED_TAG_MISSING to "needs the input: AllowlistPolicyTest",
            FindingCode.ALLOWLISTED_TAG_ABSENT to "information, not a failure: AllowlistPolicyTest",
            FindingCode.CHECKER_INTERNAL_ERROR to "unreachable by design; every hostile test asserts it never occurs",
            FindingCode.DECODE_SKIPPED to
                "information, not a failure: imagesAboveTheDecodeLimitAreSkippedWithInformation",
        )
        val planted = PlantedDefects.defects().flatMap { it.expectedFailures }.toSet()
        val missing = FindingCode.values().filter { it !in planted && it !in elsewhere }
        assertTrue(missing.isEmpty(), "finding codes without a planted fixture: $missing")
    }

    @Test
    fun largeImagesAreDecodedSubSampledAndTheDecodeCheckCanBeSwitchedOffOnlyExplicitly() {
        val clean = PlantedDefects.clean().first()
        // Above the full-decode limit the image is still decoded (sub-sampled) and passes silently when it is clean.
        assertEquals(emptyList(), PrivacyChecker.check(clean.bytes, CheckOptions(maxDecodePixels = 100)).findings)
        // Switched off explicitly: no decode, no finding.
        assertEquals(emptyList(), PrivacyChecker.check(clean.bytes, CheckOptions(decodeCheck = false)).findings)
        // Too large even for the sub-sampled decode: a failure, never a silent pass (planted fixture too).
        val report = PrivacyChecker.check(
            clean.bytes,
            CheckOptions(maxDecodePixels = 10, maxSubsampledDecodePixels = 100),
        )
        assertEquals(setOf(FindingCode.DECODE_SKIPPED), report.failureCodes)
    }

    @Test
    fun segmentWalkOfTheCleanFileIsExact() {
        val clean = PlantedDefects.clean().first()
        val report = PrivacyChecker.check(clean.bytes)
        val kinds = report.segments.map { it.kind }
        assertEquals(listOf(SegmentKind.SOI, SegmentKind.JFIF, SegmentKind.EXIF), kinds.take(3))
        assertEquals(listOf(SegmentKind.SOS, SegmentKind.EOI), kinds.takeLast(2))
        assertEquals(1, kinds.count { it == SegmentKind.SOI })
        assertEquals(1, kinds.count { it == SegmentKind.EOI })
        assertEquals(1, report.orientation)
        // 2 marker + 2 length + 6 "Exif\0\0" + 8 TIFF header + 2 count + 12 entry + 4 next-IFD offset.
        assertEquals(36, report.exifSegmentBytes)
        assertEquals(64, report.width)
        assertEquals(48, report.height)
    }

    @Test
    fun theGeneratedFixturesAreRealDecodableJpegs() {
        // Independent confirmation (the JDK decoder, not the checker) that the clean and the metadata-defect
        // fixtures are valid images, so a planted defect is a defect of metadata and not of the container.
        val names = setOf(
            "gps-block", "xmp-packet", "iptc-app13", "comment", "exif-sub-ifd", "orientation-6",
            "second-orientation-tag",
            "extra-exif-tags", "icc-canary-description", "byte-after-eoi",
        )
        val files = PlantedDefects.clean() + PlantedDefects.defects().filter { it.name in names }
        for (f in files) {
            val image = ImageIO.read(f.bytes.inputStream())
            assertNotNull(image, "${f.name} does not decode")
            assertEquals(64, image.width, f.name)
        }
    }

    @Test
    fun fixtureGenerationAndCheckingAreDeterministic() {
        assertTrue(
            JpegFixtures.syntheticJpeg(40, 30, seed = 5).contentEquals(JpegFixtures.syntheticJpeg(40, 30, seed = 5)),
        )
        val first = PlantedDefects.defects().map { PrivacyChecker.check(it.bytes, it.options).findings }
        val second = PlantedDefects.defects().map { PrivacyChecker.check(it.bytes, it.options).findings }
        assertEquals(first, second)
    }

    @Test
    fun assertPrivateOutputThrowsAndNeverEchoesFileContent() {
        val defect = PlantedDefects.defects().first { it.name == "makernote-canary-in-ifd0" }
        val e = assertFailsWith<AssertionError> { PrivacyChecker.assertPrivateOutput(defect.bytes, defect.options) }
        val message = e.message.orEmpty()
        assertTrue("MAKERNOTE_PRESENT" in message && "CANARY_FOUND" in message, message)
        assertFalse(PlantedDefects.CANARY in message, "findings must not contain metadata values or canaries")
        val clean = PlantedDefects.clean().first()
        assertTrue(PrivacyChecker.assertPrivateOutput(clean.bytes).passed)
    }

    @Test
    fun canariesThatAreNotPresentDoNotFailACleanFile() {
        val clean = PlantedDefects.clean().first()
        val options = CheckOptions(canaries = listOf(PlantedDefects.CANARY.toByteArray(), ByteArray(0)))
        assertTrue(PrivacyChecker.check(clean.bytes, options).passed)
    }

    @Test
    fun theInteroperabilityIfdIsNotAllowed() {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05"))
        t.interop = mutableListOf(t.ascii(0x0001, "R98"))
        val file = JpegBuilder().exif(t.build()).build()
        val strip = PrivacyChecker.check(file).failureCodes
        assertEquals(setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED, FindingCode.EXIF_INTEROP_NOT_ALLOWED), strip)
        val allowlist = PrivacyChecker.check(file, CheckOptions(policy = MetadataPolicy.KeepAllowlist(dateTime = true)))
        assertEquals(setOf(FindingCode.EXIF_INTEROP_NOT_ALLOWED), allowlist.failureCodes)
    }

    @Test
    fun optionalSegmentsMayBeSwitchedOffAndOrderCheckingMayBeSwitchedOff() {
        val noExif = JpegBuilder().build()
        assertTrue(PrivacyChecker.check(noExif, CheckOptions(requireExif = false)).passed)
        val late = PlantedDefects.defects().first { it.name == "exif-after-coding-segments" }
        assertTrue(PrivacyChecker.check(late.bytes, CheckOptions(enforceSegmentOrder = false)).passed)
        val jfif = JpegBuilder().strippedExif().build()
        assertEquals(
            setOf(FindingCode.APP_NOT_ALLOWED),
            PrivacyChecker.check(jfif, CheckOptions(allowJfif = false)).failureCodes,
        )
    }

    @Test
    fun segmentFollowedByXmpInAnyAppSegmentIsDetectedByContent() {
        val disguised = JpegFixtures.segment(0xE1, "<x:xmpmeta>${PlantedDefects.CANARY}</x:xmpmeta>".toByteArray())
        val bytes = JpegBuilder().strippedExif().apply { afterJfif.add(disguised) }.build()
        assertEquals(setOf(FindingCode.XMP_PRESENT), PrivacyChecker.check(bytes).failureCodes)
    }
}
