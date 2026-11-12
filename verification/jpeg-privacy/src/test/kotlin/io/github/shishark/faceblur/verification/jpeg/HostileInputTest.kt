// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.nio.file.Files
import java.time.Duration
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST-042 AC4 and PRIV-020: truncated, looping and oversize-count inputs fed to the checker itself end with an
 * 'unparseable' failure within 30 s, never a crash or a hang; unparseable counts as not private.
 */
class HostileInputTest {
    private val limit = Duration.ofSeconds(30)

    /** Runs [block] on a separate thread and fails the test if it takes longer than 30 s. */
    private fun <T> within30s(block: () -> T): T = assertTimeoutPreemptively(limit, ThrowingSupplier { block() })

    /** A strip-all file whose EXIF is built by [configure]; returns the file and the offset of the TIFF header. */
    private fun withTiff(configure: (TiffBuilder) -> Unit): Pair<ByteArray, Int> {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        configure(t)
        val bytes = JpegBuilder().exif(t.build()).build()
        return bytes to indexOfExif(bytes) + 6
    }

    private fun indexOfExif(bytes: ByteArray): Int {
        val needle = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        for (i in 0..bytes.size - needle.size) if (needle.indices.all { bytes[i + it] == needle[it] }) return i
        error("no EXIF segment in fixture")
    }

    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte() // little endian fixture
    }

    private fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = (value shr 8).toByte()
    }

    private fun assertUnparseable(bytes: ByteArray, name: String) {
        val report = within30s { PrivacyChecker.check(bytes) }
        assertFalse(report.passed, "$name must not pass")
        assertTrue(report.isUnparseable, "$name must be unparseable: ${report.summary()}")
        assertFalse(FindingCode.CHECKER_INTERNAL_ERROR in report.failureCodes, "$name hit an internal checker error")
    }

    @Test
    fun everyTruncationOfACleanFileFails() {
        val clean = JpegBuilder(JpegFixtures.syntheticJpeg(16, 16)).strippedExif().build()
        assertTrue(PrivacyChecker.check(clean).passed)
        within30s {
            for (cut in 0 until clean.size) {
                val report = PrivacyChecker.check(clean.copyOf(cut))
                assertFalse(report.passed, "a file cut at $cut of ${clean.size} bytes must not pass")
                assertTrue(report.isUnparseable, "cut at $cut: ${report.summary()}")
                assertFalse(FindingCode.CHECKER_INTERNAL_ERROR in report.failureCodes, "cut at $cut")
            }
        }
    }

    @Test
    fun emptyAndNonJpegInputs() {
        assertEquals(setOf(FindingCode.NOT_JPEG), PrivacyChecker.check(ByteArray(0)).failureCodes)
        assertEquals(
            setOf(FindingCode.NOT_JPEG),
            PrivacyChecker.check(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)).failureCodes,
        )
        assertUnparseable(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x00), "garbage after SOI")
    }

    @Test
    fun ifdLoopAndOverlap() {
        val (selfLoop, tiff) = withTiff { }
        // IFD0 lives at TIFF offset 8: 2 (count) + 12 (one entry), then the 4-byte next-IFD offset.
        put32(selfLoop, tiff + 8 + 2 + 12, 8)
        assertUnparseable(selfLoop, "IFD0 linking to itself")

        val (pointerToIfd0, tiff2) = withTiff { t -> t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05")) }
        // Entries are sorted by tag: Orientation (0x0112), then ExifIFD pointer (0x8769) at entry index 1.
        put32(pointerToIfd0, tiff2 + 8 + 2 + 12 + 8, 8)
        assertUnparseable(pointerToIfd0, "Exif pointer back to IFD0")

        val (sharedTarget, tiff3) = withTiff { t ->
            t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05"))
            t.gps = mutableListOf(t.ascii(0x01, "N"))
        }
        val exifPointerValue = tiff3 + 8 + 2 + 12 + 8
        val gpsPointerValue = exifPointerValue + 12
        val target = (sharedTarget[exifPointerValue].toLong() and 0xFF)
        put32(sharedTarget, gpsPointerValue, target)
        assertUnparseable(sharedTarget, "two pointers to the same IFD")

        val (ifd1Loop, tiff4) = withTiff { t -> t.ifd1 = mutableListOf(t.short(0x0103, 6)) }
        val ifd1Table = tiff4 + 8 + 2 + 12 + 4
        put32(ifd1Loop, ifd1Table + 2 + 12, 8)
        assertUnparseable(ifd1Loop, "IFD1 linking back to IFD0")
    }

    @Test
    fun oversizeCountsAndOffsetsOutsideTheSegment() {
        val (hugeEntryCount, tiff) = withTiff { }
        put16(hugeEntryCount, tiff + 8, 0xFFFF)
        assertUnparseable(hugeEntryCount, "entry count 65535 in a 36-byte segment")

        val (hugeValueCount, tiff2) = withTiff { t -> t.ifd0.add(t.ascii(0x010F, "ACME")) }
        // Entries sorted by tag: Make (0x010F) is entry 0, Orientation entry 1; count field at entry + 4.
        put32(hugeValueCount, tiff2 + 8 + 2 + 4, 0xFFFFFFFFL)
        assertUnparseable(hugeValueCount, "value count 4294967295")

        val (overflow, tiff3) = withTiff { t -> t.ifd0.add(t.long(0x0100, 1)) }
        put32(overflow, tiff3 + 8 + 2 + 4, 0x40000001L)
        assertUnparseable(overflow, "LONG count whose byte size overflows 32 bits")

        val (offsetOutside, tiff4) = withTiff { t -> t.ifd0.add(t.ascii(0x010F, "ACME-LONGER")) }
        put32(offsetOutside, tiff4 + 8 + 2 + 8, 0x7FFFFFFFL)
        assertUnparseable(offsetOutside, "value offset 0x7FFFFFFF")

        val (ifdOutside, tiff5) = withTiff { t -> t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05")) }
        put32(ifdOutside, tiff5 + 8 + 2 + 12 + 8, 0x00FFFFFFL)
        assertUnparseable(ifdOutside, "Exif IFD offset outside the segment")

        val (belowHeader, tiff6) = withTiff { }
        put32(belowHeader, tiff6 + 4, 2)
        assertUnparseable(belowHeader, "IFD0 offset inside the TIFF header")
    }

    @Test
    fun truncatedExifStructureInsideAnIntactSegment() {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        t.ifd0.add(t.ascii(0x010F, "ACME-ACME-ACME"))
        val full = t.build()
        val cut = JpegBuilder().exif(full.copyOf(full.size - 6)).build()
        assertUnparseable(cut, "EXIF value area cut short")
        assertUnparseable(JpegBuilder().exif(full.copyOf(5)).build(), "EXIF shorter than a TIFF header")
    }

    @Test
    fun badTiffHeader() {
        val (badOrder, tiff) = withTiff { }
        badOrder[tiff] = 'X'.code.toByte()
        val order = PrivacyChecker.check(badOrder)
        assertEquals(setOf(FindingCode.EXIF_BAD_HEADER), order.failureCodes)
        val (badMagic, tiff2) = withTiff { }
        badMagic[tiff2 + 2] = 43
        assertEquals(setOf(FindingCode.EXIF_BAD_HEADER), PrivacyChecker.check(badMagic).failureCodes)
    }

    @Test
    fun segmentLengthsThatLieAreUnparseable() {
        val clean = JpegBuilder().strippedExif().build()
        val exif = indexOfExif(clean) - 4
        val tooLong = clean.copyOf()
        tooLong[exif + 2] = 0xFF.toByte()
        tooLong[exif + 3] = 0xFF.toByte()
        assertUnparseable(tooLong, "segment length beyond the file")
        val tooShort = clean.copyOf()
        tooShort[exif + 2] = 0
        tooShort[exif + 3] = 1
        assertUnparseable(tooShort, "segment length 1")
        val zeroSize = JpegBuilder().strippedExif().build().also {
            // SOF0 starts the frame header: patch the height to zero.
            val sof = it.indices.first { i -> it[i] == 0xFF.toByte() && it[i + 1] == 0xC0.toByte() }
            it[sof + 5] = 0
            it[sof + 6] = 0
        }
        assertUnparseable(zeroSize, "image height zero")
    }

    @Test
    fun theSizeCapStopsTheCheckerBeforeReading() {
        val file = Files.createTempFile("fb-checker", ".jpg")
        try {
            Files.write(file, JpegBuilder().strippedExif().build())
            val report = PrivacyChecker.checkFile(file, CheckOptions(maxFileBytes = 10))
            assertTrue(report.isUnparseable && FindingCode.FILE_TOO_LARGE in report.failureCodes)
            assertTrue(PrivacyChecker.checkFile(file).passed)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun seededMutationFuzzNeverCrashesOrHangs() {
        val seeds = listOf(1L, 2L, 3L)
        val bases = (PlantedDefects.clean() + PlantedDefects.defects()).map { it.bytes }
        var passedAfterMutation = 0
        var checked = 0
        within30s {
            for (seed in seeds) {
                val random = Random(seed)
                for (base in bases) {
                    repeat(150) {
                        val mutated = base.copyOf()
                        // Mutate inside the metadata region (the first 400 bytes hold JFIF and EXIF) and anywhere.
                        repeat(1 + random.nextInt(4)) {
                            val range = if (random.nextBoolean()) minOf(400, mutated.size) else mutated.size
                            mutated[random.nextInt(range)] = random.nextInt(256).toByte()
                        }
                        val report = PrivacyChecker.check(mutated)
                        checked++
                        assertFalse(
                            FindingCode.CHECKER_INTERNAL_ERROR in report.failureCodes,
                            "internal checker error with seed $seed: ${report.summary()}",
                        )
                        if (report.passed) passedAfterMutation++
                    }
                }
            }
        }
        // Sanity of the experiment itself: some mutations are harmless (pixel data), most are not.
        assertTrue(
            checked > 10000 && passedAfterMutation in 1 until checked,
            "checked=$checked passed=$passedAfterMutation",
        )
    }

    private fun assertBoundedAndUnparseable(bytes: ByteArray, name: String) {
        val report = within30s { PrivacyChecker.check(bytes) }
        assertTrue(report.isUnparseable && !report.passed, "$name: ${report.findings.size} findings")
        assertTrue(
            report.findings.size <= PrivacyChecker.MAX_FINDINGS + 1,
            "$name kept ${report.findings.size} findings",
        )
        assertTrue(
            report.segments.size <= PrivacyChecker.MAX_MARKERS + 1,
            "$name kept ${report.segments.size} segments",
        )
        assertTrue(report.summary().length < 100_000, "$name: summary of ${report.summary().length} characters")
        assertFalse(FindingCode.CHECKER_INTERNAL_ERROR in report.failureCodes, name)
    }

    @Test
    fun markerFloodsEndAsUnparseableWithBoundedMemory() {
        // Found in review: 2 MB of stray restart markers made 1,048,580 findings and 8 MB ended in OutOfMemoryError.
        for (megabytes in listOf(2, 8, 32)) {
            val flood = ByteArray(megabytes * 1024 * 1024)
            for (i in flood.indices step 2) {
                flood[i] = 0xFF.toByte()
                flood[i + 1] = 0xD0.toByte()
            }
            flood[0] = 0xFF.toByte()
            flood[1] = 0xD8.toByte()
            assertBoundedAndUnparseable(flood, "restart-marker flood of $megabytes MB")
        }
    }

    @Test
    fun segmentFloodsEndAsUnparseableWithBoundedMemory() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        // A comment segment is 4 bytes: marker, length 2. 8 MB of them is 2 million segments.
        val comments = ByteArray(8 * 1024 * 1024)
        for (i in 0 until comments.size step 4) {
            comments[i] = 0xFF.toByte()
            comments[i + 1] = 0xFE.toByte()
            comments[i + 3] = 2
        }
        assertBoundedAndUnparseable(header + comments, "comment-segment flood")
        // Many valid, harmless-looking segments: the marker cap, not the findings cap, ends it.
        val restarts = byteArrayOf(0xFF.toByte(), 0xDD.toByte(), 0, 4, 0, 8)
        val intervals = ByteArray(restarts.size * 100_000) { restarts[it % restarts.size] }
        assertBoundedAndUnparseable(header + intervals, "restart-interval segment flood")
        // Many EXIF segments, each valid.
        val exif = JpegFixtures.app1Exif(TiffBuilder().also { it.ifd0.add(it.orientation(1)) }.build())
        val many = ByteArray(exif.size * 20_000) { exif[it % exif.size] }
        assertBoundedAndUnparseable(header + many, "EXIF segment flood")
    }

    @Test
    fun aLargeRealisticImageIsCheckedQuickly() {
        val big = JpegBuilder(JpegFixtures.syntheticJpeg(2400, 1800)).strippedExif().build()
        val report = within30s { PrivacyChecker.check(big) }
        assertTrue(report.passed, report.summary())
        assertEquals(2400, report.width)
    }
}
