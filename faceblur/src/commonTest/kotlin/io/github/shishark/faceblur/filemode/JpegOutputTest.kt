// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.filemode.JpegTestData.CANARY
import io.github.shishark.faceblur.filemode.JpegTestData.EOI
import io.github.shishark.faceblur.filemode.JpegTestData.SOI
import io.github.shishark.faceblur.filemode.JpegTestData.seg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rebuild step and runtime output validator (ADR 0005 decisions 1 step 7, 5 and 6; PRIV-001, PRIV-007, PRIV-020). */
class JpegOutputTest {
    private val strip = MetadataPolicy.StripAll
    private val exif = ExifWriter.app1(null)

    private fun markers(b: ByteArray) = JpegParser.parse(b).segments.map { it.marker }

    private fun valid(): ByteArray = JpegRebuilder.rebuild(JpegTestData.encoderOutput(), exif)

    private fun app0Of(b: ByteArray): ByteArray = JpegParser.parse(b).segments.first().let {
        b.copyOfRange(it.start, it.end)
    }

    @Test
    fun theFixedJfifSegmentIsExactlyTheAdrBytes() {
        val expected =
            byteArrayOf(
                0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x00, 0x00,
            )
        assertContentEquals(expected, JpegIds.FIXED_JFIF_APP0)
        assertEquals(16, JpegParser.u16(JpegIds.FIXED_JFIF_APP0, 2), "length field 16 (18 bytes with the marker)")
    }

    @Test
    fun theRebuildWritesTheFixedJfifOurExifAndOnlyTheCodingSegments() {
        val encoded =
            JpegTestData.encoderOutput(
                JpegTestData.jfxx(),
                JpegTestData.sourceExif(),
                JpegTestData.xmp(),
                JpegTestData.icc(),
                JpegTestData.mpf(),
                JpegTestData.iptc(),
                JpegTestData.com(),
            )
        assertTrue(JpegTestData.contains(encoded, CANARY), "the input carries the canary")
        val out = JpegRebuilder.rebuild(encoded, exif)
        assertFalse(JpegTestData.contains(out, CANARY), "no canary survives the rebuild")
        assertEquals(listOf(0xE0, 0xE1, 0xDB, 0xC0, 0xC4, 0xDD, 0xDA), markers(out), "no APP2 (ICC) either")
        assertContentEquals(JpegIds.FIXED_JFIF_APP0, app0Of(out), "the encoder's APP0 is replaced, never copied")
        assertContentEquals(SOI, out.copyOfRange(0, 2))
        assertContentEquals(EOI, out.copyOfRange(out.size - 2, out.size))
        assertEquals(emptyList(), OutputValidator.findings(out, strip))
        OutputValidator.requireValid(out, strip)
        // The coding segments and the entropy data (stuffed byte, restart marker) are copied unchanged.
        val coding = JpegTestData.coding()
        assertContentEquals(coding, out.copyOfRange(out.size - 2 - coding.size, out.size - 2))
    }

    /** An encoder APP0 with a thumbnail and an encoder ICC profile never reach the output. */
    @Test
    fun anEncoderJfifThumbnailAndAnyIccProfileAreDropped() {
        val thumbnail = SOI + JpegTestData.jfifWithThumbnail() + JpegTestData.coding() + EOI
        val withIcc = JpegTestData.encoderOutput(JpegTestData.icc())
        val cmyk = JpegTestData.encoderOutput(JpegTestData.icc(space = "CMYK"))
        val noApp0 = SOI + JpegTestData.coding() + EOI
        for ((name, encoded) in listOf(
            "thumbnail" to thumbnail,
            "RGB ICC" to withIcc,
            "CMYK ICC" to cmyk,
            "no APP0" to noApp0,
        )) {
            val out = JpegRebuilder.rebuild(encoded, exif)
            assertEquals(listOf(0xE0, 0xE1, 0xDB, 0xC0, 0xC4, 0xDD, 0xDA), markers(out), name)
            assertContentEquals(JpegIds.FIXED_JFIF_APP0, app0Of(out), name)
            assertFalse(JpegTestData.contains(out, CANARY), name)
            assertEquals(emptyList(), OutputValidator.findings(out, strip), name)
        }
    }

    /** One entry point: restricts the metadata to the policy, rebuilds and validates. */
    @Test
    fun finishRestrictsRebuildsAndValidatesInOneCall() {
        val metadata = PhotoMetadata.of(make = "Cam", dateTimeOriginal = "2026:11:02 10:11:12")
        val frame = Size(16, 16)
        val finished = JpegOutput.finish(JpegTestData.encoderOutput(JpegTestData.xmp()), metadata, strip, frame)
        assertContentEquals(valid(), finished.bytes, "StripAll writes Orientation only")
        assertEquals(frame, finished.size, "the frame size of the checked output")
        val dateOnly = MetadataPolicy.KeepAllowlist(dateTime = true)
        val dated = JpegOutput.finish(JpegTestData.encoderOutput(), metadata, dateOnly, frame).bytes
        assertTrue(JpegTestData.contains(dated, "2026:11:02 10:11:12"))
        assertFalse(JpegTestData.contains(dated, "Cam"), "camera group not allowed, not written")
        assertEquals(emptyList(), OutputValidator.findings(dated, dateOnly))
        val e =
            assertFailsWith<FaceMaskException.OutputValidationFailed> {
                JpegOutput.finish(JpegTestData.encoderOutput().let { it.copyOf(it.size - 2) }, null, strip, frame)
            }
        assertEquals(listOf(Finding.MISSING_EOI), e.findings)
    }

    /**
     * The encoder is a platform part: a valid JPEG whose frame is not the planned output size fails closed, in
     * either side and also when the sides are swapped (an unrotated image).
     */
    @Test
    fun finishRefusesAFrameOfAnotherSizeThanPlanned() {
        val planned = Size(800, 600)
        assertEquals(planned, JpegOutput.finish(JpegTestData.encoderOutput(planned), null, strip, planned).size)
        for (other in listOf(Size(801, 600), Size(800, 599), Size(600, 800), Size(16, 16))) {
            val encoded = JpegTestData.encoderOutput(other)
            assertTrue(OutputValidator.findings(JpegRebuilder.rebuild(encoded, ExifWriter.app1(null)), strip).isEmpty())
            val e = assertFailsWith<FaceMaskException.Internal>("$other") {
                JpegOutput.finish(encoded, null, strip, planned)
            }
            assertEquals("Internal error: the encoder returned another size than planned", e.message)
            assertEquals(null, e.cause)
        }
        // The validator reports the size of the one frame of a valid output.
        val out = JpegRebuilder.rebuild(JpegTestData.encoderOutput(Size(65_535, 1)), ExifWriter.app1(null))
        assertEquals(Size(65_535, 1), OutputValidator.requireValid(out, strip))
    }

    /**
     * Encoder output the parser cannot read throws with the parser's code: a cut inside a segment header is
     * TRUNCATED, a cut inside the entropy-coded data is MISSING_EOI (the data simply runs to the end).
     */
    @Test
    fun malformedEncoderOutputIsRefusedByTheRebuild() {
        val good = JpegTestData.encoderOutput()
        val dqtAt = JpegTestData.indexOf(good, JpegTestData.dqt())
        val sosEnd = good.size - 2
        val cases =
            listOf(
                Finding.TRUNCATED to good.copyOf(dqtAt + 10), // inside the DQT segment
                Finding.TRUNCATED to good.copyOf(dqtAt + 3), // inside the DQT length field
                Finding.MISSING_EOI to good.copyOf(sosEnd - 3), // inside the entropy-coded data
                Finding.MISSING_EOI to good.copyOf(good.size - 2), // EOI missing
                Finding.TRAILING_BYTES to good + byteArrayOf(0, 1),
                Finding.NOT_JPEG to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0),
                Finding.TRUNCATED to SOI + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x7F, 0x7F, 1, 2),
            )
        for ((code, bytes) in cases) {
            val e =
                assertFailsWith<FaceMaskException.OutputValidationFailed>(code) { JpegRebuilder.rebuild(bytes, exif) }
            assertEquals(listOf(code), e.findings, code)
            assertEquals("Output validation failed: $code", e.message)
        }
    }

    /**
     * ADR 0002 decision 9: the finding codes are a public contract. The set is pinned here, so adding, removing or
     * renaming a code fails until the ADR and the changelog are updated; each code is produced, alone, by a planted
     * output and carried unchanged by the exception's property and message.
     */
    @Test
    fun everyPublicFindingCodeIsPinnedAndProducedAlone() {
        assertEquals(
            setOf(
                "NOT_JPEG", "STRUCTURE", "TRUNCATED", "MISSING_EOI", "TRAILING_BYTES", "UNEXPECTED_MARKER",
                "FORBIDDEN_SEGMENT", "SEGMENT_ORDER", "MISSING_EXIF", "DUPLICATE_EXIF", "EXIF_TOO_LARGE",
                "EXIF_INVALID", "EXIF_TAG_NOT_ALLOWED", "ORIENTATION_NOT_1", "FRAME", "NO_SCAN",
            ),
            Finding.ALL,
        )
        val jfif = JpegTestData.jfif()
        val coding = JpegTestData.coding()
        val ok = valid()
        val camera = ExifWriter.app1(PhotoMetadata.of(make = "Cam"))
        val orientation6 =
            JpegTestData.replaceOnce(exif, byteArrayOf(0, 3, 0, 0, 0, 1, 0, 1), byteArrayOf(0, 3, 0, 0, 0, 1, 0, 6))
        val slack = seg(0xE1, exif.copyOfRange(4, exif.size) + byteArrayOf(0x41, 0x42))
        val tooLarge = seg(0xE1, exif.copyOfRange(4, exif.size) + ByteArray(1_025 - exif.size))
        val planted =
            mapOf(
                Finding.NOT_JPEG to coding + EOI,
                Finding.STRUCTURE to SOI + jfif + exif + coding + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + EOI,
                Finding.TRUNCATED to SOI + jfif + exif + coding + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x7F, 0x7F),
                Finding.MISSING_EOI to SOI + jfif + exif + coding + JpegTestData.dri(), // ends after a complete segment
                Finding.TRAILING_BYTES to ok + byteArrayOf(0, 1),
                Finding.UNEXPECTED_MARKER to SOI + jfif + exif + coding + SOI,
                Finding.FORBIDDEN_SEGMENT to SOI + jfif + exif + JpegTestData.com() + coding + EOI,
                Finding.SEGMENT_ORDER to SOI + exif + coding + EOI,
                Finding.MISSING_EXIF to SOI + jfif + coding + EOI,
                Finding.DUPLICATE_EXIF to SOI + jfif + exif + exif + coding + EOI,
                Finding.EXIF_TOO_LARGE to SOI + jfif + tooLarge + coding + EOI,
                Finding.EXIF_INVALID to SOI + jfif + slack + coding + EOI,
                Finding.EXIF_TAG_NOT_ALLOWED to SOI + jfif + camera + coding + EOI,
                Finding.ORIENTATION_NOT_1 to SOI + jfif + orientation6 + coding + EOI,
                Finding.FRAME to
                    SOI + jfif + exif + JpegTestData.dqt() + JpegTestData.sof0() + JpegTestData.sof0() +
                    JpegTestData.dht() + JpegTestData.sos() + EOI,
                Finding.NO_SCAN to
                    SOI + jfif + exif + JpegTestData.dqt() + JpegTestData.sof0() + JpegTestData.dht() + EOI,
            )
        assertEquals(Finding.ALL, planted.keys, "one planted output per public code")
        for ((code, bytes) in planted) {
            assertEquals(listOf(code), OutputValidator.findings(bytes, strip), code)
            val e =
                assertFailsWith<FaceMaskException.OutputValidationFailed>(code) {
                    OutputValidator.requireValid(bytes, strip)
                }
            assertEquals(listOf(code), e.findings, code)
            assertEquals("Output validation failed: $code", e.message, code)
        }
        val e = FaceMaskException.OutputValidationFailed(listOf(Finding.FRAME, Finding.NO_SCAN, Finding.FRAME))
        assertEquals(listOf(Finding.FRAME, Finding.NO_SCAN), e.findings, "each code once, in the order found")
        assertEquals("Output validation failed: FRAME,NO_SCAN", e.message)
    }

    /** Every planted defect gives its finding; the valid file gives none (the control). */
    @Test
    fun theValidatorFindsEveryPlantedDefect() {
        val ok = valid()
        assertEquals(emptyList(), OutputValidator.findings(ok, strip))
        val body = ok.copyOfRange(2, ok.size - 2) // without SOI and EOI
        val jfif = JpegTestData.jfif()
        val coding = JpegTestData.coding()
        val dqt = JpegTestData.dqt()
        val sof0 = JpegTestData.sof0()
        val dht = JpegTestData.dht()
        val sos = JpegTestData.sos()
        val dri = JpegTestData.dri()
        fun withCoding(vararg segments: ByteArray) =
            SOI + jfif + exif + segments.fold(ByteArray(0)) { a, s -> a + s } + EOI
        fun payload(s: ByteArray) = s.copyOfRange(4, s.size)
        val planted =
            listOf(
                Triple("gain map appended after EOI", Finding.TRAILING_BYTES, ok + ok),
                Triple(
                    "XMP after EXIF",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + jfif + exif + JpegTestData.xmp() + coding + EOI,
                ),
                Triple("comment", Finding.FORBIDDEN_SEGMENT, SOI + jfif + exif + JpegTestData.com() + coding + EOI),
                Triple("MPF APP2", Finding.FORBIDDEN_SEGMENT, SOI + jfif + exif + JpegTestData.mpf() + coding + EOI),
                Triple("IPTC APP13", Finding.FORBIDDEN_SEGMENT, SOI + jfif + exif + JpegTestData.iptc() + coding + EOI),
                Triple(
                    "JFXX thumbnail",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + jfif + exif + JpegTestData.jfxx() + coding + EOI,
                ),
                // A well-formed RGB profile with a canary, and any other APP2/ICC.
                Triple(
                    "RGB ICC profile",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + jfif + exif + JpegTestData.icc() + coding + EOI,
                ),
                Triple(
                    "CMYK ICC",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + jfif + exif + JpegTestData.icc(space = "CMYK") + coding + EOI,
                ),
                // A JFIF APP0 with an 8 x 8 thumbnail carrying a canary.
                Triple(
                    "JFIF with a thumbnail",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + JpegTestData.jfifWithThumbnail() + exif + coding + EOI,
                ),
                Triple(
                    "JFIF with a 1 x 1 thumbnail",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + JpegTestData.jfifWithThumbnail(1, 1) + exif + coding + EOI,
                ),
                Triple(
                    "the encoder's JFIF (other version, density)",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + JpegTestData.encoderJfif() + exif + coding + EOI,
                ),
                Triple(
                    "APP0 with another identifier",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + seg(0xE0, CANARY.encodeToByteArray()) + exif + coding + EOI,
                ),
                Triple("second APP0", Finding.FORBIDDEN_SEGMENT, SOI + jfif + exif + jfif + coding + EOI),
                Triple("missing APP0", Finding.SEGMENT_ORDER, SOI + exif + coding + EOI),
                Triple("APP1 before the APP0", Finding.SEGMENT_ORDER, SOI + exif + jfif + coding + EOI),
                Triple(
                    "ICC between APP0 and APP1",
                    Finding.SEGMENT_ORDER,
                    SOI + jfif + JpegTestData.icc() + exif + coding + EOI,
                ),
                Triple("second EXIF", Finding.DUPLICATE_EXIF, SOI + jfif + exif + exif + coding + EOI),
                Triple("no EXIF", Finding.MISSING_EXIF, SOI + jfif + coding + EOI),
                Triple("two frames", Finding.FRAME, SOI + jfif + exif + sof0 + coding + EOI),
                Triple("no frame", Finding.FRAME, withCoding(dqt, dht, sos)),
                Triple("no scan", Finding.NO_SCAN, withCoding(dqt, sof0)),
                Triple("frame after scan", Finding.SEGMENT_ORDER, withCoding(dqt, dht, sos, sof0)),
                Triple(
                    "arithmetic-coded frame",
                    Finding.FORBIDDEN_SEGMENT,
                    SOI + jfif + exif + seg(0xC9, ByteArray(9)) + coding + EOI,
                ),
                Triple("second image inside", Finding.UNEXPECTED_MARKER, SOI + body + SOI + body + EOI),
                Triple("no SOI", Finding.NOT_JPEG, body + EOI),
                Triple(
                    "lying length",
                    Finding.TRUNCATED,
                    SOI + jfif + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00) + EOI,
                ),
                Triple(
                    "length below 2",
                    Finding.TRUNCATED,
                    SOI + jfif + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 1) + EOI,
                ),
                Triple("fill bytes", Finding.STRUCTURE, SOI + byteArrayOf(0xFF.toByte()) + jfif + exif + coding + EOI),
                Triple(
                    "garbage between segments",
                    Finding.STRUCTURE,
                    SOI + jfif + byteArrayOf(0x42) + exif + coding + EOI,
                ),
                Triple("unterminated scan", Finding.MISSING_EOI, SOI + jfif + exif + coding),
                // Coding segments: no segment may carry bytes beyond its tables.
                Triple(
                    "DQT with appended bytes",
                    Finding.STRUCTURE,
                    withCoding(
                        seg(
                            0xDB,
                            payload(dqt) + CANARY.encodeToByteArray(),
                        ),
                        sof0,
                        dht,
                        sos,
                    ),
                ),
                Triple(
                    "DQT with 16-bit precision but 8-bit size",
                    Finding.STRUCTURE,
                    withCoding(
                        seg(
                            0xDB,
                            byteArrayOf(0x10) + ByteArray(64),
                        ),
                        sof0,
                        dht,
                        sos,
                    ),
                ),
                Triple(
                    "DQT with table id 4",
                    Finding.STRUCTURE,
                    withCoding(seg(0xDB, byteArrayOf(0x04) + ByteArray(64)), sof0, dht, sos),
                ),
                Triple("empty DQT", Finding.STRUCTURE, withCoding(seg(0xDB, ByteArray(0)), dqt, sof0, dht, sos)),
                Triple(
                    "DHT with appended bytes",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        sof0,
                        seg(
                            0xC4,
                            payload(dht) + byteArrayOf(1),
                        ),
                        sos,
                    ),
                ),
                Triple(
                    "DHT with more than 256 symbols",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        sof0,
                        seg(
                            0xC4,
                            byteArrayOf(0) + ByteArray(16) {
                                if (it == 15) {
                                    255.toByte()
                                } else if (it == 14) {
                                    2
                                } else {
                                    0
                                }
                            } +
                                ByteArray(257),
                        ),
                        sos,
                    ),
                ),
                Triple(
                    "DHT with class 2",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        sof0,
                        seg(
                            0xC4,
                            byteArrayOf(0x20) + payload(dht).copyOfRange(1, 18),
                        ),
                        sos,
                    ),
                ),
                Triple(
                    "SOF with appended bytes",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        seg(
                            0xC0,
                            payload(sof0) + byteArrayOf(0),
                        ),
                        dht,
                        sos,
                    ),
                ),
                Triple(
                    "SOF with 12-bit precision",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        seg(
                            0xC0,
                            byteArrayOf(12) + payload(sof0).copyOfRange(1, 9),
                        ),
                        dht,
                        sos,
                    ),
                ),
                Triple(
                    "SOF with zero height",
                    Finding.STRUCTURE,
                    withCoding(dqt, seg(0xC0, byteArrayOf(8, 0, 0, 0, 16, 1, 1, 0x11, 0)), dht, sos),
                ),
                Triple(
                    "SOF with zero width",
                    Finding.STRUCTURE,
                    withCoding(dqt, seg(0xC0, byteArrayOf(8, 0, 16, 0, 0, 1, 1, 0x11, 0)), dht, sos),
                ),
                Triple(
                    "SOF with 5 components",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        seg(
                            0xC0,
                            byteArrayOf(8, 0, 16, 0, 16, 5) + ByteArray(15),
                        ),
                        dht,
                        sos,
                    ),
                ),
                Triple("short SOF", Finding.STRUCTURE, withCoding(dqt, seg(0xC0, byteArrayOf(8, 0, 16)), dht, sos)),
                Triple(
                    "SOS header with appended bytes",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        sof0,
                        dht,
                        seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0, 0x41)) + byteArrayOf(0x12),
                    ),
                ),
                Triple(
                    "SOS with 0 components",
                    Finding.STRUCTURE,
                    withCoding(
                        dqt,
                        sof0,
                        dht,
                        seg(0xDA, byteArrayOf(0, 0, 63, 0)) + byteArrayOf(0x12),
                    ),
                ),
                Triple(
                    "DRI with appended bytes",
                    Finding.STRUCTURE,
                    withCoding(dqt, sof0, dht, seg(0xDD, byteArrayOf(0, 4, 0)), sos),
                ),
            )
        for ((name, code, bytes) in planted) {
            val f = OutputValidator.findings(bytes, strip)
            assertTrue(code in f, "$name: expected $code, got $f")
            assertFailsWith<FaceMaskException.OutputValidationFailed>(name) {
                OutputValidator.requireValid(bytes, strip)
            }
        }
        // Controls: valid coding segments of other legal shapes pass.
        val twoTables = seg(0xDB, payload(dqt) + byteArrayOf(0x11) + ByteArray(128))
        assertEquals(emptyList(), OutputValidator.findings(withCoding(twoTables, sof0, dht, dri, sos), strip))
        val twoHuffman = seg(0xC4, payload(dht) + byteArrayOf(0x10) + payload(dht).copyOfRange(1, 18))
        assertEquals(emptyList(), OutputValidator.findings(withCoding(dqt, sof0, twoHuffman, sos), strip))
        val threeComponents = seg(0xC0, byteArrayOf(8, 0, 16, 0, 16, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
        val sos3 = seg(0xDA, byteArrayOf(3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0)) + byteArrayOf(0x12)
        assertEquals(emptyList(), OutputValidator.findings(withCoding(dqt, threeComponents, dht, sos3), strip))
    }

    /** EXIF content checks, with each defect planted in an otherwise valid segment. */
    @Test
    fun theValidatorChecksTheExifContent() {
        fun withExif(app1: ByteArray) = SOI + JpegTestData.jfif() + app1 + JpegTestData.coding() + EOI
        fun tiffSegment(tiff: ByteArray) = seg(0xE1, Exif.HEADER + tiff)
        val header = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8)
        fun ifd(vararg entries: ByteArray, next: Int = 0) =
            ExifWriter.u16(entries.size) + entries.fold(ByteArray(0)) { a, e -> a + e } + ExifWriter.u32(next)
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) =
            ExifWriter.u16(tag) + ExifWriter.u16(type) + ExifWriter.u32(count) + value
        val orientation6 = entry(Exif.ORIENTATION, Exif.SHORT, 1, byteArrayOf(0, 6, 0, 0))
        val orientation1 = entry(Exif.ORIENTATION, Exif.SHORT, 1, byteArrayOf(0, 1, 0, 0))
        val camera = MetadataPolicy.KeepAllowlist(camera = true)
        val dateTime = MetadataPolicy.KeepAllowlist(dateTime = true)
        val all = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        val makeValue = "ABCDEF".encodeToByteArray() + 0 // 7 bytes: out of line, one padding byte
        val makeEntry = { at: Int -> entry(Exif.MAKE, Exif.ASCII, 7, ExifWriter.u32(at)) }
        val gap = CANARY.encodeToByteArray() + ByteArray(40 - CANARY.length)
        val dated = ExifWriter.app1(PhotoMetadata.of(dateTimeOriginal = "2026:11:02 10:11:12"))
        val gps =
            ExifWriter.app1(
                PhotoMetadata.of(
                    latitudeRef = 'N',
                    latitude = listOf(Rational(42, 1), Rational(41, 1), Rational(5678, 100)),
                    longitudeRef = 'E',
                    longitude = listOf(Rational(23, 1), Rational(19, 1), Rational(1234, 100)),
                    altitudeRef = 0,
                    altitude = Rational(100, 1),
                ),
            )
        val cameraAxb = ExifWriter.app1(PhotoMetadata.of(make = "AxB"))
        fun make(bytes: ByteArray) = JpegTestData.replaceOnce(cameraAxb, "AxB".encodeToByteArray(), bytes)
        val cases =
            listOf(
                Triple("orientation 6", Finding.ORIENTATION_NOT_1, tiffSegment(header + ifd(orientation6))) to strip,
                Triple(
                    "IFD1 (thumbnail) linked",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(orientation1, next = 8)),
                ) to
                    strip,
                Triple(
                    "little-endian",
                    Finding.EXIF_INVALID,
                    tiffSegment(byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0) + ifd(orientation1)),
                ) to
                    strip,
                Triple(
                    "orientation as LONG",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(entry(Exif.ORIENTATION, Exif.LONG, 1, byteArrayOf(0, 0, 0, 1)))),
                ) to
                    strip,
                Triple(
                    "orientation twice",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(orientation1, orientation1)),
                ) to
                    strip,
                Triple(
                    "unsorted entries",
                    Finding.EXIF_INVALID,
                    tiffSegment(
                        header + ifd(orientation1, entry(Exif.MAKE, Exif.ASCII, 2, byteArrayOf(0x41, 0, 0, 0))),
                    ),
                ) to camera,
                Triple(
                    "Make without the camera group",
                    Finding.EXIF_TAG_NOT_ALLOWED,
                    tiffSegment(
                        header + ifd(entry(Exif.MAKE, Exif.ASCII, 2, byteArrayOf(0x41, 0, 0, 0)), orientation1),
                    ),
                ) to strip,
                Triple(
                    "ImageDescription (not on the closed list)",
                    Finding.EXIF_TAG_NOT_ALLOWED,
                    tiffSegment(header + ifd(entry(0x010E, Exif.ASCII, 2, byteArrayOf(0x41, 0, 0, 0)), orientation1)),
                ) to all,
                Triple(
                    "Make not NUL-terminated",
                    Finding.EXIF_INVALID,
                    tiffSegment(
                        header + ifd(entry(Exif.MAKE, Exif.ASCII, 2, byteArrayOf(0x41, 0x42, 0, 0)), orientation1),
                    ),
                ) to camera,
                Triple("huge entry count", Finding.EXIF_INVALID, tiffSegment(header + ExifWriter.u16(0xFFFF))) to strip,
                Triple(
                    "IFD0 not at 8",
                    Finding.EXIF_INVALID,
                    tiffSegment(byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 10, 0, 0) + ifd(orientation1)),
                ) to
                    strip,
                Triple(
                    "value outside the segment",
                    Finding.EXIF_INVALID,
                    tiffSegment(
                        header + ifd(entry(Exif.MAKE, Exif.ASCII, 20, ExifWriter.u32(0x7FFF_FFF0)), orientation1),
                    ),
                ) to camera,
                Triple(
                    "GPS IFD pointing back at IFD0 (loop)",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(orientation1, entry(Exif.GPS_IFD, Exif.LONG, 1, ExifWriter.u32(8)))),
                ) to all,
                Triple(
                    "GPS without the gps group",
                    Finding.EXIF_TAG_NOT_ALLOWED,
                    tiffSegment(
                        header + ifd(orientation1, entry(Exif.GPS_IFD, Exif.LONG, 1, ExifWriter.u32(38))) +
                            ifd(entry(Exif.GPS_VERSION_ID, Exif.BYTE, 4, byteArrayOf(2, 3, 0, 0))),
                    ),
                ) to strip,
                // Odd and out-of-range sub-IFD offsets.
                Triple(
                    "Exif IFD at an odd offset",
                    Finding.EXIF_INVALID,
                    tiffSegment(
                        header + ifd(orientation1, entry(Exif.EXIF_IFD, Exif.LONG, 1, ExifWriter.u32(39))) +
                            ByteArray(40),
                    ),
                ) to dateTime,
                Triple(
                    "Exif IFD at the segment end",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(orientation1, entry(Exif.EXIF_IFD, Exif.LONG, 1, ExifWriter.u32(38)))),
                ) to dateTime,
                Triple(
                    "Exif and GPS pointers at the same IFD",
                    Finding.EXIF_INVALID,
                    ExifWriter
                        .app1(
                            PhotoMetadata.of(
                                dateTimeOriginal = "2026:11:02 10:11:12",
                                latitudeRef = 'N',
                                latitude = listOf(Rational(1, 1), Rational(1, 1), Rational(1, 1)),
                                longitudeRef = 'E',
                                longitude = listOf(Rational(1, 1), Rational(1, 1), Rational(1, 1)),
                            ),
                        ).let { s ->
                            // Point the GPS IFD entry at the Exif IFD.
                            val gpsEntry = JpegTestData.indexOf(
                                s,
                                ExifWriter.u16(Exif.GPS_IFD) + ExifWriter.u16(Exif.LONG),
                            )
                            val exifEntry = JpegTestData.indexOf(
                                s,
                                ExifWriter.u16(Exif.EXIF_IFD) + ExifWriter.u16(Exif.LONG),
                            )
                            s.copyOf().also { c -> s.copyInto(c, gpsEntry + 8, exifEntry + 8, exifEntry + 12) }
                        },
                ) to all,
                // Unreferenced bytes (a canary) between IFD0 and the value.
                Triple(
                    "gap between the IFD and an out-of-line value",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(makeEntry(38 + gap.size), orientation1) + gap + makeValue + 0),
                ) to camera,
                // The unused bytes of an inline value.
                Triple(
                    "data in the unused bytes of the inline Orientation",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(entry(Exif.ORIENTATION, Exif.SHORT, 1, byteArrayOf(0, 1, 0x41, 0x42)))),
                ) to strip,
                Triple(
                    "non-zero padding after an odd-length value",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(makeEntry(38), orientation1) + makeValue + 0x41),
                ) to camera,
                Triple(
                    "two bytes after the last IFD",
                    Finding.EXIF_INVALID,
                    tiffSegment(header + ifd(orientation1) + byteArrayOf(0x41, 0x42)),
                ) to
                    strip,
                // The printable-ASCII rule (a mutant that drops it must fail here).
                Triple("Make with a 0x01 byte", Finding.EXIF_INVALID, make(byteArrayOf(0x41, 0x01, 0x42))) to camera,
                Triple("Make with a tab", Finding.EXIF_INVALID, make(byteArrayOf(0x41, 0x09, 0x42))) to camera,
                Triple("Make with DEL", Finding.EXIF_INVALID, make(byteArrayOf(0x41, 0x7F, 0x42))) to camera,
                Triple(
                    "Make with a byte above 0x7F",
                    Finding.EXIF_INVALID,
                    make(byteArrayOf(0x41, 0x80.toByte(), 0x42)),
                ) to
                    camera,
                Triple(
                    "Make with an embedded NUL",
                    Finding.EXIF_INVALID,
                    make(byteArrayOf(0x41, 0x00, 0x42)),
                ) to camera,
                // The values are re-checked, not only the structure.
                Triple(
                    "DateTimeOriginal with ':' for a digit",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(dated, "10:11:12".encodeToByteArray(), "10:1::12".encodeToByteArray()),
                ) to dateTime,
                Triple(
                    "DateTimeOriginal on 31 February",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(dated, "2026:11:02".encodeToByteArray(), "2026:02:31".encodeToByteArray()),
                ) to dateTime,
                Triple(
                    "latitude 91 degrees",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(
                        gps,
                        ExifWriter.u32(42) + ExifWriter.u32(1),
                        ExifWriter.u32(91) + ExifWriter.u32(1),
                    ),
                ) to all,
                Triple(
                    "zero denominator",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(
                        gps,
                        ExifWriter.u32(5678) + ExifWriter.u32(100),
                        ExifWriter.u32(5678) + ExifWriter.u32(0),
                    ),
                ) to all,
                Triple(
                    "latitude reference X",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(
                        gps,
                        entry(Exif.GPS_LATITUDE_REF, Exif.ASCII, 2, byteArrayOf(0x4E, 0, 0, 0)),
                        entry(Exif.GPS_LATITUDE_REF, Exif.ASCII, 2, byteArrayOf(0x58, 0, 0, 0)),
                    ),
                ) to all,
                Triple(
                    "altitude reference 7",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(
                        gps,
                        entry(Exif.GPS_ALTITUDE_REF, Exif.BYTE, 1, byteArrayOf(0, 0, 0, 0)),
                        entry(Exif.GPS_ALTITUDE_REF, Exif.BYTE, 1, byteArrayOf(7, 0, 0, 0)),
                    ),
                ) to all,
                Triple(
                    "GPS version 2.2.0.0",
                    Finding.EXIF_INVALID,
                    JpegTestData.replaceOnce(
                        gps,
                        entry(Exif.GPS_VERSION_ID, Exif.BYTE, 4, byteArrayOf(2, 3, 0, 0)),
                        entry(Exif.GPS_VERSION_ID, Exif.BYTE, 4, byteArrayOf(2, 2, 0, 0)),
                    ),
                ) to all,
                Triple(
                    "too large",
                    Finding.EXIF_TOO_LARGE,
                    tiffSegment(header + ifd(orientation1) + ByteArray(1_025)),
                ) to
                    strip,
                Triple("truncated TIFF header", Finding.EXIF_INVALID, tiffSegment(byteArrayOf(0x4D, 0x4D, 0, 0x2A))) to
                    strip,
            )
        for ((case, policy) in cases) {
            val (name, code, app1) = case
            val f = OutputValidator.findings(withExif(app1), policy)
            assertTrue(code in f, "$name: expected $code, got $f")
        }
        // Controls: the writer's own segments pass; the same layouts without the planted defect pass.
        assertContentEquals(exif, tiffSegment(header + ifd(orientation1)))
        assertEquals(emptyList(), OutputValidator.findings(withExif(exif), strip))
        val noGap = tiffSegment(header + ifd(makeEntry(38), orientation1) + makeValue + 0)
        assertContentEquals(
            ExifWriter.app1(PhotoMetadata.of(make = "ABCDEF")),
            noGap,
            "the planted layouts are writer-shaped",
        )
        assertEquals(emptyList(), OutputValidator.findings(withExif(noGap), camera))
        assertEquals(
            emptyList(),
            OutputValidator.findings(withExif(make(byteArrayOf(0x41, 0x20, 0x7E))), camera),
            "space and tilde",
        )
        assertEquals(emptyList(), OutputValidator.findings(withExif(dated), dateTime))
        assertEquals(
            emptyList(),
            OutputValidator.findings(
                withExif(
                    JpegTestData.replaceOnce(dated, "2026:11:02".encodeToByteArray(), "2024:02:29".encodeToByteArray()),
                ),
                dateTime,
            ),
            "a leap day",
        )
        assertEquals(emptyList(), OutputValidator.findings(withExif(gps), all))
        // APP1 limit: 1,024 bytes are read (and fail only as non-canonical), 1,025 are refused unread.
        val pad1024 = tiffSegment(header + ifd(orientation1) + ByteArray(1_024 - exif.size))
        assertEquals(1_024, pad1024.size)
        assertEquals(listOf(Finding.EXIF_INVALID), OutputValidator.findings(withExif(pad1024), strip))
        val pad1025 = tiffSegment(header + ifd(orientation1) + ByteArray(1_025 - exif.size))
        assertEquals(listOf(Finding.EXIF_TOO_LARGE), OutputValidator.findings(withExif(pad1025), strip))
    }

    /**
     * The fixed APP0 and the StripAll APP1 hold no free value, so changing any single byte of them must be refused
     * (a random mutation run once found changed APP0/APP1 bytes that an earlier check accepted).
     */
    @Test
    fun everySingleByteChangeOfTheStripAllMetadataSegmentsIsRefused() {
        val ok = valid()
        val metadataEnd = 2 + JpegIds.FIXED_JFIF_APP0.size + exif.size
        for (i in 2 until metadataEnd) {
            for (delta in listOf(0x01, 0x02, 0x10, 0x80, 0xFF)) {
                val m = ok.copyOf()
                m[i] = (m[i].toInt() xor delta).toByte()
                assertTrue(OutputValidator.findings(m, strip).isNotEmpty(), "byte $i xor $delta accepted")
            }
        }
    }

    /** PRIV-020: arbitrary bytes and mutated outputs end in findings, never in an exception or a hang. */
    @Test
    fun hostileBytesNeverCrashOrHang() {
        val all = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        val full =
            JpegRebuilder.rebuild(
                JpegTestData.encoderOutput(),
                ExifWriter.app1(
                    PhotoMetadata.of(
                        make = "Cam",
                        dateTimeOriginal = "2026:11:02 10:11:12",
                        latitudeRef = 'N',
                        latitude = listOf(Rational(1, 1), Rational(2, 1), Rational(3, 1)),
                        longitudeRef = 'W',
                        longitude = listOf(Rational(4, 1), Rational(5, 1), Rational(6, 1)),
                    ),
                ),
            )
        val seeds = listOf(valid(), full)
        var seed = 0x2545F491
        fun next(): Int {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            return seed
        }
        var rejected = 0
        repeat(3_000) { k ->
            val bytes =
                if (k % 2 == 0) {
                    ByteArray((next() and 0x3FF) + 1) { next().toByte() }.also { if (k % 4 == 0) SOI.copyInto(it) }
                } else {
                    seeds[(k / 2) % 2].copyOf().also { m ->
                        repeat(1 + (next() and 7)) {
                            m[(next() and 0x7FFFFFFF) % m.size] =
                                next().toByte()
                        }
                    }
                }
            for (policy in listOf(strip, all)) {
                if (OutputValidator.findings(bytes, policy).isNotEmpty()) rejected++
            }
            runCatching { JpegRebuilder.rebuild(bytes, exif) }.exceptionOrNull()?.let {
                assertTrue(it is FaceMaskException.OutputValidationFailed, "rebuild threw ${it::class}")
            }
        }
        assertTrue(rejected > 3_000, "random input is rejected ($rejected of 6,000 checks)")
    }

    @Test
    fun inputFormatsAreRecognisedFromTheirFirstBytesOnly() {
        assertEquals(
            ImageFormat.JPEG,
            ImageFormat.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
        )
        assertEquals(
            ImageFormat.PNG,
            ImageFormat.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)),
        )
        fun ftyp(vararg brands: String): ByteArray {
            val body =
                brands.first().encodeToByteArray() + ByteArray(4) +
                    brands.drop(1).fold(ByteArray(0)) { a, b -> a + b.encodeToByteArray() }
            return ExifWriter.u32(8 + body.size) + "ftyp".encodeToByteArray() + body
        }
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(ftyp("heic", "mif1", "heic")))
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(ftyp("mif1", "heic")))
        val unsupported =
            listOf(
                ftyp("avif", "mif1", "avif"),
                ftyp("mif1", "avif"),
                ftyp("isom", "mp41"),
                "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray(),
                "GIF89a".encodeToByteArray(),
                byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
                ByteArray(0),
                ExifWriter.u32(10) + "ftyp".encodeToByteArray() + "heic".encodeToByteArray() + ByteArray(4),
            )
        for (b in unsupported) assertFailsWith<FaceMaskException.UnsupportedImage> { ImageFormat.sniff(b) }
    }
}
