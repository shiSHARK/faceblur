// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MetadataPolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rebuild step and the validator on real encoder output (the JDK's JPEG encoder stands in for the platform
 * encoders). The outputs are also written to the directory named by the system property faceblur.crosscheckDir (set
 * by the Gradle test task, which empties it first) together with manifest.tsv, so that the independent ST-042 checker
 * can be run on them from the command line (never linked into the library; scripts/crosscheck-file-mode.sh).
 */
class EncoderOutputTest {
    private val out: File? = System.getProperty("faceblur.crosscheckDir")?.let { File(it).apply { mkdirs() } }

    /**
     * Writes one cross-check output and its manifest line: name, checker policy, expected verdict and, for a planted
     * output, the exact finding codes the checker must report. Without the property (e.g. an IDE run) nothing is
     * written; the CI step then fails because the manifest is missing.
     */
    private fun crosscheck(name: String, bytes: ByteArray, policy: String, rejectedWith: List<String> = emptyList()) {
        val dir = out ?: return
        File(dir, name).writeBytes(bytes)
        val verdict = if (rejectedWith.isEmpty()) "pass" else "fail"
        val codes = if (rejectedWith.isEmpty()) "-" else rejectedWith.sorted().joinToString(",")
        synchronized(EncoderOutputTest::class.java) {
            File(dir, "manifest.tsv").appendText("$name\t$policy\t$verdict\t$codes\n")
        }
    }

    /** The size of [encoded]'s image. */
    private val size = Size(96, 64)

    /** A synthetic gradient image encoded by the JDK at quality 90. */
    private fun encoded(): ByteArray {
        val img = BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) {
            for (x in 0 until 96) {
                img.setRGB(
                    x,
                    y,
                    (x * 2 shl 16) or (y * 3 shl 8) or ((x + y) and 0xFF),
                )
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.9f
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    private fun pixels(jpeg: ByteArray): IntArray {
        val img = ImageIO.read(jpeg.inputStream())
        return IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) }
    }

    private fun gps() = PhotoMetadata.of(
        make = "SyntheticCam",
        model = "Model 1",
        dateTimeOriginal = "2026:11:02 10:11:12",
        offsetTimeOriginal = "+02:00",
        latitudeRef = 'N',
        latitude = listOf(Rational(42, 1), Rational(41, 1), Rational(5678, 100)),
        longitudeRef = 'E',
        longitude = listOf(Rational(23, 1), Rational(19, 1), Rational(1234, 100)),
    )

    private fun replaceSegment(bytes: ByteArray, marker: Int, replacement: ByteArray): ByteArray {
        val s = JpegParser.parse(bytes).segments.first { it.marker == marker }
        return bytes.copyOfRange(0, s.start) + replacement + bytes.copyOfRange(s.end, bytes.size)
    }

    private fun app0(bytes: ByteArray): ByteArray =
        JpegParser.parse(bytes).segments.first().let { bytes.copyOfRange(it.start, it.end) }

    @Test
    fun realEncoderOutputIsRebuiltWithTheSamePixelsAndPassesTheValidator() {
        val raw = encoded()
        println("JDK encoder segments: ${JpegParser.parse(raw).segments.map { it.marker.toString(16) }}")
        val stripped = JpegRebuilder.rebuild(raw, ExifWriter.app1(null))
        assertEquals(emptyList(), OutputValidator.findings(stripped, MetadataPolicy.StripAll))
        assertContentEquals(JpegIds.FIXED_JFIF_APP0, app0(stripped), "the encoder's APP0 is replaced")
        val finished = JpegOutput.finish(raw, gps(), MetadataPolicy.StripAll, size)
        assertContentEquals(stripped, finished.bytes, "one-call form")
        assertContentEquals(pixels(raw), pixels(stripped), "the rebuild changes metadata only, never the pixels")
        crosscheck("strip-all.jpg", stripped, "strip-all")

        val policy = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        val kept = JpegRebuilder.rebuild(raw, ExifWriter.app1(gps().restrictTo(policy)))
        assertEquals(emptyList(), OutputValidator.findings(kept, policy))
        assertContentEquals(pixels(raw), pixels(kept))
        crosscheck("allowlist-all.jpg", kept, "allowlist:gps,datetime,camera")

        val dateOnly = MetadataPolicy.KeepAllowlist(dateTime = true)
        val dated = JpegRebuilder.rebuild(raw, ExifWriter.app1(gps().restrictTo(dateOnly)))
        assertEquals(emptyList(), OutputValidator.findings(dated, dateOnly))
        assertFalse(JpegTestData.contains(dated, "SyntheticCam"), "camera not allowed, not written")
        crosscheck("allowlist-datetime.jpg", dated, "allowlist:datetime")
    }

    /**
     * On real encoder output: a JFIF APP0 with a thumbnail and a well-formed RGB ICC
     * profile (both with a canary) are dropped by the rebuild, the pixels are unchanged; a DQT with appended bytes
     * is refused.
     */
    @Test
    fun aJfifThumbnailAndAnIccProfileFromTheEncoderNeverReachTheOutput() {
        val raw = encoded()
        val injected = replaceSegment(raw, 0xE0, JpegTestData.jfifWithThumbnail() + JpegTestData.icc())
        assertTrue(JpegTestData.contains(injected, JpegTestData.CANARY))
        assertTrue(OutputValidator.findings(injected, MetadataPolicy.StripAll).isNotEmpty(), "control: refused as is")
        val rebuilt = JpegOutput.finish(injected, null, MetadataPolicy.StripAll, size).bytes
        assertFalse(JpegTestData.contains(rebuilt, JpegTestData.CANARY))
        assertContentEquals(JpegIds.FIXED_JFIF_APP0, app0(rebuilt))
        assertTrue(JpegParser.parse(rebuilt).segments.none { it.marker == 0xE2 }, "no APP2 / ICC profile")
        assertContentEquals(pixels(raw), pixels(rebuilt))
        crosscheck("jfif-thumbnail-and-icc-dropped.jpg", rebuilt, "strip-all")

        val dqt = JpegParser.parse(rebuilt).segments.first { it.marker == 0xDB }
        val padded =
            replaceSegment(
                rebuilt,
                0xDB,
                JpegTestData.seg(
                    0xDB,
                    rebuilt.copyOfRange(dqt.start + 4, dqt.end) + JpegTestData.CANARY.encodeToByteArray(),
                ),
            )
        assertTrue(Finding.STRUCTURE in OutputValidator.findings(padded, MetadataPolicy.StripAll))
    }

    /** A faulty writer: its TIFF links an IFD1 with a JPEG thumbnail (Compression 6, offset, length) holding a canary. */
    private fun writerLinkingIfd1(metadata: PhotoMetadata): ByteArray {
        val tiff = ExifWriter.app1(metadata).let { it.copyOfRange(4 + Exif.HEADER.size, it.size) }
        val nextIfdAt = 8 + 2 + 12 * JpegParser.u16(tiff, 8)
        val ifd1At = tiff.size
        val thumbnailAt = ifd1At + 2 + 3 * 12 + 4
        val thumbnail = JpegTestData.CANARY.encodeToByteArray() + 0 // 14 bytes, even
        fun entry(tag: Int, type: Int, value: ByteArray) =
            ExifWriter.u16(tag) + ExifWriter.u16(type) + ExifWriter.u32(1) + value
        val ifd1 =
            ExifWriter.u16(3) +
                entry(0x0103, Exif.SHORT, ExifWriter.u16(6) + ByteArray(2)) +
                entry(0x0201, Exif.LONG, ExifWriter.u32(thumbnailAt)) +
                entry(0x0202, Exif.LONG, ExifWriter.u32(thumbnail.size)) +
                ExifWriter.u32(0)
        ExifWriter.u32(ifd1At).copyInto(tiff, nextIfdAt)
        return JpegTestData.seg(0xE1, Exif.HEADER + tiff + ifd1 + thumbnail)
    }

    /**
     * A faulty writer: IFD0's next-IFD offset points back at IFD0 (a linked IFD1 without extra bytes, so the length
     * is unchanged; only the next-IFD check can see it).
     */
    private fun writerLinkingIfd0AsIfd1(metadata: PhotoMetadata): ByteArray {
        val segment = ExifWriter.app1(metadata)
        val tiffAt = 4 + Exif.HEADER.size
        ExifWriter.u32(8).copyInto(segment, tiffAt + 8 + 2 + 12 * JpegParser.u16(segment, tiffAt + 8))
        return segment
    }

    /** A faulty writer: its segment carries two extra bytes after the last value (the length field includes them). */
    private fun writerAppendingBytes(metadata: PhotoMetadata): ByteArray {
        val segment = ExifWriter.app1(metadata)
        return JpegTestData.seg(0xE1, segment.copyOfRange(4, segment.size) + byteArrayOf(0x41, 0x42))
    }

    /**
     * The checks that do not depend on the writer (next-IFD offset 0, segment length equal to the parsed structure)
     * refuse the output of a faulty writer even when that same writer is the reference of the byte comparison, on
     * real encoder output, for StripAll and for every group.
     */
    @Test
    fun aFaultyWriterIsRefusedEvenWhenItIsTheReference() {
        val raw = encoded()
        val all = MetadataPolicy.KeepAllowlist(gps = true, dateTime = true, camera = true)
        for (policy in listOf(MetadataPolicy.StripAll, all)) {
            val metadata = assertNotNull(gps().restrictTo(all)).takeIf { policy == all } ?: PhotoMetadata.of()
            val good = JpegRebuilder.rebuild(raw, ExifWriter.app1(metadata))
            assertEquals(emptyList(), OutputValidator.findings(good, policy), "control: the real writer, $policy")
            for ((name, writer) in listOf(
                "links an IFD1" to ::writerLinkingIfd1,
                "links IFD0 as its IFD1" to ::writerLinkingIfd0AsIfd1,
                "appends bytes" to ::writerAppendingBytes,
            )) {
                val out = JpegRebuilder.rebuild(raw, writer(metadata))
                assertContentEquals(pixels(raw), pixels(out), "$name: still a decodable image")
                val f = OutputValidator.findings(out, policy, exifWriter = writer)
                assertEquals(listOf(Finding.EXIF_INVALID), f, "$name, $policy")
            }
        }
    }

    /** Planted metadata in the encoder output is removed; the unprocessed file is refused (negative control). */
    @Test
    fun plantedMetadataInEncoderOutputIsRemovedAndTheRawFileFails() {
        val raw = encoded()
        // Insert XMP, COM, MPF and a source EXIF (all with the canary) right after the encoder's first segment.
        val first = JpegParser.parse(raw).segments.first()
        val planted =
            raw.copyOfRange(0, first.end) + JpegTestData.xmp() + JpegTestData.com() + JpegTestData.mpf() +
                JpegTestData.sourceExif() + raw.copyOfRange(first.end, raw.size)
        assertTrue(JpegTestData.contains(planted, JpegTestData.CANARY))
        assertTrue(
            OutputValidator.findings(planted, MetadataPolicy.StripAll).isNotEmpty(),
            "control: the raw file fails",
        )
        crosscheck(
            "planted-unprocessed.jpg",
            planted,
            "strip-all",
            listOf(
                "XMP_PRESENT",
                "COM_PRESENT",
                "MPF_PRESENT",
                "EXIF_TAG_NOT_ALLOWED",
                "ORIENTATION_MISSING",
                "SEGMENT_ORDER",
            ),
        )
        // A correct output followed by a second image: trailing data is its only defect.
        val stripped = JpegOutput.finish(raw, null, MetadataPolicy.StripAll, size).bytes
        crosscheck("planted-trailing.jpg", stripped + raw, "strip-all", listOf("TRAILING_BYTES", "SECOND_IMAGE"))
        val rebuilt = JpegRebuilder.rebuild(planted, ExifWriter.app1(null))
        assertFalse(JpegTestData.contains(rebuilt, JpegTestData.CANARY))
        assertEquals(emptyList(), OutputValidator.findings(rebuilt, MetadataPolicy.StripAll))
        assertContentEquals(pixels(raw), pixels(rebuilt))
        assertFailsWith<FaceMaskException.OutputValidationFailed> {
            JpegRebuilder.rebuild(raw + raw, ExifWriter.app1(null))
        }
    }
}
