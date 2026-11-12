// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IccAndCliTest {
    private fun withIcc(profile: ByteArray?, seed: Long = 1) = JpegBuilder(JpegFixtures.syntheticJpeg(seed = seed))
        .strippedExif()
        .apply { if (profile != null) afterJfif.add(JpegFixtures.icc(profile)) }
        .build()

    /** Options that name [profiles] as reviewed. The synthetic test profiles are not decodable colour profiles. */
    private fun reviewed(vararg profiles: ByteArray, source: ByteArray? = null, decode: Boolean = false) = CheckOptions(
        allowedIccSha256 = profiles.map { IccFixtures.sha256(it) }.toSet(),
        sourceIccProfile = source,
        decodeCheck = decode,
    )

    @Test
    fun descriptionAndHashAreReported() {
        val profile = IccFixtures.profile("test-description")
        val report = PrivacyChecker.check(withIcc(profile), reviewed(profile))
        assertTrue(report.passed, report.summary())
        assertEquals("test-description", report.iccDescription)
        assertEquals(IccFixtures.sha256(profile), report.iccSha256)
        assertNull(PrivacyChecker.check(withIcc(null)).iccSha256)
    }

    @Test
    fun anIccProfileFailsUnlessItsHashIsReviewed() {
        val profile = IccFixtures.profile("any-description")
        assertEquals(setOf(FindingCode.ICC_NOT_ON_ALLOWLIST), PrivacyChecker.check(withIcc(profile)).failureCodes)
        // Measuring mode (only to obtain the hash of a platform encoder's profile) skips the list, not the structure.
        val measured = PrivacyChecker.check(withIcc(profile), CheckOptions(measureIccOnly = true, decodeCheck = false))
        assertTrue(measured.passed, measured.summary())
        assertEquals(IccFixtures.sha256(profile), measured.iccSha256)
    }

    @Test
    fun aReviewedProfileTheDecoderCannotUseIsStillReported() {
        val synthetic = IccFixtures.profile("not-a-real-profile")
        val report = PrivacyChecker.check(withIcc(synthetic), reviewed(synthetic, decode = true))
        assertEquals(setOf(FindingCode.DECODER_WARNING), report.failureCodes, report.summary())
        val real = IccFixtures.validSrgb()
        assertTrue(PrivacyChecker.check(withIcc(real), reviewed(real, decode = true)).passed)
    }

    @Test
    fun aVersion4DescriptionIsRead() {
        val profile = IccFixtures.profileWithMultiLocalizedDescription("sRGB v4")
        val report = PrivacyChecker.check(withIcc(profile), reviewed(profile))
        assertTrue(report.passed, report.summary())
        assertEquals("sRGB v4", report.iccDescription)
    }

    @Test
    fun brokenProfilesAreReportedAsMalformed() {
        val tooShort = ByteArray(100)
        assertEquals(
            setOf(FindingCode.ICC_MALFORMED),
            PrivacyChecker.check(withIcc(tooShort), reviewed(tooShort)).failureCodes,
        )
        val noSignature = IccFixtures.profile("x").also { it[36] = 'x'.code.toByte() }
        assertEquals(
            setOf(FindingCode.ICC_MALFORMED),
            PrivacyChecker.check(withIcc(noSignature), reviewed(noSignature)).failureCodes,
        )
        val hugeTagCount = IccFixtures.profile("x").also { it[128] = 0x7F }
        val report = PrivacyChecker.check(withIcc(hugeTagCount), reviewed(hugeTagCount))
        assertEquals(setOf(FindingCode.ICC_MALFORMED), report.failureCodes)
        assertNull(report.iccDescription, "an absurd tag count yields no description and no crash")
        val tagOutside = IccFixtures.profile("x").also { it[140] = 0x7F } // tag size far beyond the profile
        assertEquals(
            setOf(FindingCode.ICC_MALFORMED),
            PrivacyChecker.check(withIcc(tagOutside), reviewed(tagOutside)).failureCodes,
        )
    }

    @Test
    fun theSourceProfileCanBeExtractedFromAnInputFile() {
        val profile = IccFixtures.profile("source-profile")
        assertContentEquals(profile, PrivacyChecker.extractIccProfile(withIcc(profile)))
        assertNull(PrivacyChecker.extractIccProfile(withIcc(null)))
    }

    @Test
    fun anIncompleteSourceProfileIsRefusedInsteadOfBeingHalfRead() {
        val oneOfTwo = JpegBuilder().strippedExif().apply {
            afterJfif.add(JpegFixtures.icc(IccFixtures.profile("a"), chunk = 1, chunks = 2))
        }.build()
        assertFailsWith<IllegalArgumentException> { PrivacyChecker.extractIccProfile(oneOfTwo) }
        val twoChunks = JpegBuilder().strippedExif().apply {
            afterJfif.add(JpegFixtures.icc(ByteArray(10) { 1 }, chunk = 2, chunks = 2))
            afterJfif.add(JpegFixtures.icc(ByteArray(10) { 2 }, chunk = 1, chunks = 2))
        }.build()
        assertContentEquals(ByteArray(10) { 2 } + ByteArray(10) { 1 }, PrivacyChecker.extractIccProfile(twoChunks))
    }

    @Test
    fun sourceChunksThatDisagreeAboutTheirCountAreRefused() {
        val inconsistent = JpegBuilder().strippedExif().apply {
            afterJfif.add(JpegFixtures.icc(ByteArray(10) { 1 }, chunk = 1, chunks = 2))
            afterJfif.add(JpegFixtures.icc(ByteArray(10) { 2 }, chunk = 2, chunks = 3))
        }.build()
        assertFailsWith<IllegalArgumentException> { PrivacyChecker.extractIccProfile(inconsistent) }
    }

    @Test
    fun theOutputProfileMustNotDependOnTheInput() {
        val srgb = IccFixtures.profile("sRGB-platform")
        val other = IccFixtures.profile("camera-specific-profile")
        val options = reviewed(srgb, other)
        val a = PrivacyChecker.check(withIcc(srgb, seed = 1), options)
        val b = PrivacyChecker.check(withIcc(srgb, seed = 2), options)
        PrivacyChecker.assertIccIdenticalAcrossOutputs(listOf(a, b))
        // All absent is fine.
        PrivacyChecker.assertIccIdenticalAcrossOutputs(
            listOf(PrivacyChecker.check(withIcc(null)), PrivacyChecker.check(withIcc(null, 2))),
        )
        // Negative controls: a profile derived from the input, a profile present for one input only, too few reports.
        val derived = PrivacyChecker.check(withIcc(other), options)
        assertFailsWith<AssertionError> { PrivacyChecker.assertIccIdenticalAcrossOutputs(listOf(a, derived)) }
        assertFailsWith<AssertionError> {
            PrivacyChecker.assertIccIdenticalAcrossOutputs(listOf(a, PrivacyChecker.check(withIcc(null))))
        }
        assertFailsWith<IllegalArgumentException> { PrivacyChecker.assertIccIdenticalAcrossOutputs(emptyList()) }
        assertFailsWith<IllegalArgumentException> { PrivacyChecker.assertIccIdenticalAcrossOutputs(listOf(a)) }
    }

    @Test
    fun aDifferentProfileThanTheSourceDoesNotTriggerTheCopyCheck() {
        val source = IccFixtures.profile("display-p3-source")
        val output = IccFixtures.profile("sRGB-platform")
        assertTrue(PrivacyChecker.check(withIcc(output), reviewed(output, source = source)).passed)
    }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun commandLineExitCodesAndOutputNeverShowNamesOrPaths() {
        val dir = Files.createTempDirectory("fb-checker-cli")
        val good = dir.resolve("good-CONFIDENTIAL-NAME.jpg")
        val bad = dir.resolve("bad-CONFIDENTIAL-NAME.jpg")
        val gps = dir.resolve("gps.jpg")
        val withProfile = dir.resolve("icc.jpg")
        val none = dir.resolve("none.jpg")
        val missing = dir.resolve("missing-CONFIDENTIAL-NAME.jpg")
        val profile = IccFixtures.validSrgb()
        try {
            good.writeBytes(JpegBuilder().strippedExif().build())
            bad.writeBytes(PlantedDefects.defects().first { it.name == "makernote-canary-in-ifd0" }.bytes)
            val t = TiffBuilder()
            t.ifd0.add(t.orientation(1))
            t.gps = mutableListOf(t.ascii(0x01, "N"), t.rational(0x02, 1L to 1L, 2L to 1L, 3L to 1L))
            gps.writeBytes(JpegBuilder().exif(t.build()).build())
            withProfile.writeBytes(withIcc(profile))
            none.writeBytes(JpegBuilder().build())

            val ok = cli(good.toString())
            assertEquals(0, ok.first, ok.second)
            assertTrue(ok.second.startsWith("PASS file #1 segments="), ok.second)

            val failed = cli(good.toString(), bad.toString(), missing.toString())
            assertEquals(1, failed.first)
            assertTrue("FAIL file #2 failures=1 codes=MAKERNOTE_PRESENT" in failed.second, failed.second)
            assertTrue("FAIL file #3 IO_ERROR (NoSuchFileException)" in failed.second, failed.second)
            val everything = failed.second + failed.third + ok.second + ok.third
            assertFalse("CONFIDENTIAL" in everything || dir.toString() in everything || "jpg" in everything, everything)
            assertFalse(PlantedDefects.CANARY in everything)

            assertEquals(1, cli(gps.toString()).first, "GPS fails under the default strip-all policy")
            assertEquals(0, cli("--policy=allowlist:gps", gps.toString()).first)
            assertEquals(1, cli(withProfile.toString()).first, "an unreviewed ICC profile fails")
            assertEquals(0, cli("--icc-sha256=" + IccFixtures.sha256(profile), withProfile.toString()).first)
            // Measuring only adds the hash; an unreviewed profile is still FAIL and the exit code is not 0.
            val measured = cli("--measure-icc", withProfile.toString())
            assertEquals(1, measured.first, measured.second)
            assertTrue(measured.second.startsWith("FAIL file #1"), measured.second)
            assertTrue("icc_sha256=" + IccFixtures.sha256(profile) in measured.second, measured.second)
            val reviewedAndMeasured =
                cli("--measure-icc", "--icc-sha256=" + IccFixtures.sha256(profile), withProfile.toString())
            assertEquals(0, reviewedAndMeasured.first)
            assertTrue(
                reviewedAndMeasured.second.startsWith("PASS file #1") && "icc_sha256=" in reviewedAndMeasured.second,
            )
            assertEquals(0, cli("--no-exif-required", none.toString()).first)
            assertEquals(1, cli(none.toString()).first)
            assertEquals(2, cli().first)
            assertEquals(2, cli("--policy=allowlist:nonsense", good.toString()).first)
            assertEquals(2, cli("--icc-sha256=abc", good.toString()).first)
            assertEquals(2, cli("--unknown", good.toString()).first)
            assertTrue("IO_ERROR" in cli("bad\u0000path").second)
        } finally {
            listOf(good, bad, gps, withProfile, none).forEach { it.deleteIfExists() }
            dir.deleteIfExists()
        }
    }
}
