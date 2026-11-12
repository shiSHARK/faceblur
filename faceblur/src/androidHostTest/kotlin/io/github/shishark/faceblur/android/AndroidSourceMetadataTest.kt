// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.JpegOutput
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskJpeg
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File mode on Android end to end with a SOURCE THAT CARRIES EVERY KIND OF METADATA (PRIV-001, PRIV-007, ST-001 AC1,
 * AC3, AC8, ST-012 AC1 and AC7): EXIF text tags, MakerNote, UTF-16 UserComment, GPS, an IFD1 thumbnail, XMP, IPTC,
 * COM, MPF and an ICC profile in a JPEG; tEXt, iTXt and eXIf chunks in a PNG. Each kind holds its own canary text. The
 * written file must hold none of them (also as UTF-16), exactly the fixed JFIF APP0 and the one EXIF APP1 with
 * Orientation = 1 and nothing else (no ICC profile, so an allowed-ICC list stays empty), one image, nothing after EOI.
 * The check of the output ([problems]) does not use the library's parser or validator; the library's own validator
 * runs in the other host tests. Negative controls prove that the check fails on a copy of the input, a planted XMP, a
 * trailing byte and an unmasked re-encode. All pictures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidSourceMetadataTest {
    private lateinit var masker: FaceMasker
    private lateinit var dir: File

    @Before
    fun setUp() {
        FaceBlurRuntime.installImageCodec(AndroidImageCodec.using(HostFiles))
        FaceBlurRuntime.installTargetWriter(AndroidTargetWriter.using(HostFiles))
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        dir = Files.createTempDirectory("faceblur-meta").toFile()
    }

    @After
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        dir.deleteRecursively()
    }

    /** The fake model with no face: every score 0. */
    private class NoFaceRunner : TensorRunner by OneFaceRunner() {
        override fun run(input: FloatArray): Map<String, FloatArray> =
            OneFaceRunner().run(input).mapValues { (k, v) -> if (k.startsWith("cls_")) FloatArray(v.size) else v }
    }

    private companion object {
        /** One canary text per metadata field of the sources (synthetic; no real data). */
        val CANARIES: Map<String, String> = linkedMapOf(
            "exif-description" to "CNRY-EXIF-DESC",
            "exif-make" to "CNRY-EXIF-MAKE",
            "exif-model" to "CNRY-EXIF-MODL",
            "exif-software" to "CNRY-EXIF-SOFT",
            "exif-artist" to "CNRY-EXIF-ARTS",
            "exif-copyright" to "CNRY-EXIF-COPY",
            "exif-user-comment-utf16" to "CNRY-EXIF-UCMT",
            "exif-makernote" to "CNRY-EXIF-MKNT",
            "gps-processing-method" to "CNRY-EXIF-GPSP",
            "exif-thumbnail" to "CNRY-EXIF-THMB",
            "xmp" to "CNRY-XMP-0001",
            "iptc" to "CNRY-IPTC-0001",
            "com" to "CNRY-COM-0001",
            "icc" to "CNRY-ICC-0001",
            "mpf" to "CNRY-MPF-0001",
            "png-text" to "CNRY-PNG-TEXT",
            "png-xmp" to "CNRY-PNG-XMP1",
            "png-exif" to "CNRY-PNG-EXIF",
        )
    }

    // ---- byte helpers (independent of the library) ----

    private fun b16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun b32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)

    private fun seg(marker: Int, payload: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte()) + b16(payload.size + 2) + payload

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
        "%02x".format(it)
    }

    /** ASCII, UTF-16 big endian and UTF-16 little endian forms of [text]. */
    private fun encodings(text: String): Map<String, ByteArray> = linkedMapOf(
        "ascii" to text.encodeToByteArray(),
        "utf16be" to text.toByteArray(Charsets.UTF_16BE),
        "utf16le" to text.toByteArray(Charsets.UTF_16LE),
    )

    // ---- a big-endian TIFF with EXIF, GPS and IFD1 thumbnail ----

    private class Ent(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun ascii(tag: Int, text: String) = Ent(tag, 2, text.length + 1, text.encodeToByteArray() + 0)

    private fun undefined(tag: Int, data: ByteArray) = Ent(tag, 7, data.size, data)

    private fun short(tag: Int, v: Int) = Ent(tag, 3, 1, b16(v) + byteArrayOf(0, 0))

    private fun long(tag: Int, v: Int) = Ent(tag, 4, 1, b32(v))

    private fun padded(n: Int) = (n + 1) and 1.inv()

    private fun ifdSize(e: List<Ent>) =
        2 + 12 * e.size + 4 + e.sumOf { if (it.data.size > 4) padded(it.data.size) else 0 }

    private fun ifd(entries: List<Ent>, at: Int, next: Int): ByteArray {
        var valueAt = at + 2 + 12 * entries.size + 4
        val head = ByteArrayOutputStream()
        val values = ByteArrayOutputStream()
        head.write(b16(entries.size))
        for (x in entries.sortedBy { it.tag }) {
            head.write(b16(x.tag))
            head.write(b16(x.type))
            head.write(b32(x.count))
            if (x.data.size <= 4) {
                head.write(x.data)
                repeat(4 - x.data.size) { head.write(0) }
            } else {
                head.write(b32(valueAt))
                values.write(x.data)
                if (x.data.size % 2 == 1) values.write(0)
                valueAt += padded(x.data.size)
            }
        }
        head.write(b32(next))
        return head.toByteArray() + values.toByteArray()
    }

    /** The TIFF of a source EXIF: [orientation], text tags, Exif IFD, GPS IFD and an IFD1 with a JPEG thumbnail. */
    private fun fullTiff(orientation: Int): ByteArray {
        val c = CANARIES
        val thumbnail =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xFE, c.getValue("exif-thumbnail").encodeToByteArray()) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val exif = listOf(
            undefined(
                0x9286,
                "UNICODE".encodeToByteArray() + 0 + c.getValue(
                    "exif-user-comment-utf16",
                ).toByteArray(Charsets.UTF_16BE),
            ),
            undefined(0x927C, "MAKERNOTE".encodeToByteArray() + 0 + c.getValue("exif-makernote").encodeToByteArray()),
            ascii(0x9003, "2026:01:02 03:04:05"),
        )
        val gps = listOf(
            Ent(0x0000, 1, 4, byteArrayOf(2, 3, 0, 0)),
            ascii(0x0001, "N"),
            Ent(0x0002, 5, 3, intArrayOf(40, 1, 50, 1, 0, 1).fold(ByteArray(0)) { acc, v -> acc + b32(v) }),
            ascii(0x0003, "E"),
            Ent(0x0004, 5, 3, intArrayOf(10, 1, 20, 1, 30, 1).fold(ByteArray(0)) { acc, v -> acc + b32(v) }),
            undefined(
                0x001B,
                "ASCII".encodeToByteArray() + byteArrayOf(0, 0, 0) +
                    c.getValue("gps-processing-method").encodeToByteArray(),
            ),
        )
        fun ifd0(exifAt: Int, gpsAt: Int) = listOf(
            ascii(0x010E, c.getValue("exif-description")),
            ascii(0x010F, c.getValue("exif-make")),
            ascii(0x0110, c.getValue("exif-model")),
            short(0x0112, orientation),
            ascii(0x0131, c.getValue("exif-software")),
            ascii(0x013B, c.getValue("exif-artist")),
            ascii(0x8298, c.getValue("exif-copyright")),
            long(0x8769, exifAt),
            long(0x8825, gpsAt),
        )
        val ifd0At = 8
        val exifAt = ifd0At + ifdSize(ifd0(0, 0))
        val gpsAt = exifAt + ifdSize(exif)
        val ifd1At = gpsAt + ifdSize(gps)
        val ifd1Entries = { thumbAt: Int ->
            listOf(short(0x0103, 6), long(0x0201, thumbAt), long(0x0202, thumbnail.size))
        }
        val thumbAt = ifd1At + ifdSize(ifd1Entries(0))
        return byteArrayOf(0x4D, 0x4D, 0x00, 0x2A) + b32(ifd0At) +
            ifd(ifd0(exifAt, gpsAt), ifd0At, ifd1At) + ifd(exif, exifAt, 0) + ifd(gps, gpsAt, 0) +
            ifd(ifd1Entries(thumbAt), ifd1At, 0) + thumbnail
    }

    /** [plain] with every kind of JPEG metadata segment right after SOI (the EXIF holds [orientation]). */
    private fun withEveryMetadataKind(plain: ByteArray, orientation: Int): ByteArray {
        val c = CANARIES
        val icc = ByteArray(400) { (it % 251).toByte() }.also {
            b32(it.size).copyInto(it, 0)
            "RGB ".encodeToByteArray().copyInto(it, 16)
            "acsp".encodeToByteArray().copyInto(it, 36)
            c.getValue("icc").encodeToByteArray().copyInto(it, 200)
        }
        val segments =
            seg(0xE1, "Exif".encodeToByteArray() + byteArrayOf(0, 0) + fullTiff(orientation)) +
                seg(
                    0xE1,
                    "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + 0 + c.getValue("xmp").encodeToByteArray(),
                ) +
                seg(0xED, "Photoshop 3.0".encodeToByteArray() + 0 + c.getValue("iptc").encodeToByteArray()) +
                seg(0xFE, c.getValue("com").encodeToByteArray()) +
                seg(0xE2, "ICC_PROFILE".encodeToByteArray() + 0 + byteArrayOf(1, 1) + icc) +
                seg(0xE2, "MPF".encodeToByteArray() + 0 + c.getValue("mpf").encodeToByteArray())
        return plain.copyOfRange(0, 2) + segments + plain.copyOfRange(2, plain.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.encodeToByteArray() + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return b32(data.size) + body + b32(crc)
    }

    /** [plain] PNG with tEXt, iTXt (XMP) and eXIf chunks right after IHDR. */
    private fun pngWithMetadata(plain: ByteArray): ByteArray {
        val c = CANARIES
        val exifTiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A) + b32(8) +
            ifd(listOf(ascii(0x010E, c.getValue("png-exif"))), 8, 0)
        val chunks =
            chunk("tEXt", "Comment".encodeToByteArray() + 0 + c.getValue("png-text").encodeToByteArray()) +
                chunk(
                    "iTXt",
                    "XML:com.adobe.xmp".encodeToByteArray() + byteArrayOf(0, 0, 0, 0, 0) +
                        c.getValue("png-xmp").encodeToByteArray(),
                ) +
                chunk("eXIf", exifTiff)
        val afterIhdr = 8 + 25
        return plain.copyOfRange(0, afterIhdr) + chunks + plain.copyOfRange(afterIhdr, plain.size)
    }

    // ---- the output check, independent of the library ----

    /**
     * Everything wrong with [output] as a privacy-clean output: a canary of any field in any of the three encodings,
     * APPn / COM segments other than the fixed APP0 and the one EXIF APP1, an EXIF other than Orientation = 1 alone,
     * a second JPEG signature, bytes after the final EOI. Empty = clean.
     */
    private fun problems(output: ByteArray): List<String> {
        val found = mutableListOf<String>()
        for ((name, text) in CANARIES) {
            for ((encoding, bytes) in encodings(text)) {
                if (indexOf(output, bytes) >= 0) found += "canary $name ($encoding)"
            }
        }
        val markers = mutableListOf<Int>()
        var exifSegment: ByteArray? = null
        var scanAt = -1
        try {
            check(output[0] == 0xFF.toByte() && output[1] == 0xD8.toByte())
            var i = 2
            while (true) {
                check(output[i] == 0xFF.toByte())
                val marker = output[i + 1].toInt() and 0xFF
                markers += marker
                if (marker == 0xDA) {
                    scanAt = i
                    break
                }
                val length = u16(output, i + 2)
                if (marker == 0xE1) exifSegment = output.copyOfRange(i, i + 2 + length)
                i += 2 + length
            }
        } catch (e: RuntimeException) {
            found += "marker walk failed: ${e.javaClass.simpleName}"
        }
        val appn = markers.filter { it in 0xE0..0xEF }
        if (appn != listOf(0xE0, 0xE1)) found += "APPn markers ${appn.map { it.toString(16) }}"
        if (markers.any { it == 0xFE }) found += "COM segment"
        val app1 = exifSegment
        if (app1 != null) {
            // 2 marker + 2 length + "Exif\0\0" + "MM\0*" + offset 8 + one entry (12) + count (2) + next IFD (4)
            val expectedLength = 2 + 2 + 6 + 8 + 2 + 12 + 4
            val tiff = 4 + 6
            val ok = app1.size == expectedLength &&
                indexOf(app1, "Exif".encodeToByteArray() + byteArrayOf(0, 0)) == 4 &&
                u32(app1, tiff) == 0x4D4D002A && u32(app1, tiff + 4) == 8 &&
                u16(app1, tiff + 8) == 1 && // exactly one entry in IFD0
                u16(app1, tiff + 10) == 0x0112 && u16(app1, tiff + 12) == 3 && u32(app1, tiff + 14) == 1 &&
                u16(app1, tiff + 18) == 1 && // Orientation = 1
                u32(app1, tiff + 22) == 0 // no next IFD
            if (!ok) found += "EXIF is not exactly Orientation = 1 (${app1.size} bytes)"
        }
        var signatures = 0
        var at = 0
        while (true) {
            at = indexOf(output, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), at)
            if (at < 0) break
            signatures++
            at++
        }
        if (signatures != 1) found += "$signatures JPEG signatures"
        if (scanAt >= 0) {
            val eoi = indexOf(output, byteArrayOf(0xFF.toByte(), 0xD9.toByte()), scanAt)
            if (eoi != output.size - 2) found += "bytes after the first EOI (EOI at $eoi of ${output.size})"
        }
        return found
    }

    private fun assertClean(output: ByteArray, label: String) = assertEquals(emptyList(), problems(output), label)

    private fun assertLibraryValidatorAgrees(output: ByteArray) =
        assertEquals(emptyList(), OutputValidator.findings(output, MetadataPolicy.StripAll), "the library's own check")

    // ---- tests ----

    /** The check has teeth: every canary is really in the sources, so a copy of a source is found by name. */
    @Test
    fun theSourcesCarryEveryCanaryAndTheCheckFindsAllOfThem() {
        val plain = TestImages.jpeg(640, 480)
        val jpegSource = withEveryMetadataKind(plain, 1)
        val pngSource = pngWithMetadata(TestImages.png(TestImages.picture(320, 240)))
        val inJpeg = problems(jpegSource).filter { it.startsWith("canary ") }.map { it.split(' ')[1] }.toSet()
        val inPng = problems(pngSource).filter { it.startsWith("canary ") }.map { it.split(' ')[1] }.toSet()
        val jpegFields = CANARIES.keys.filterNot { it.startsWith("png-") }.toSet()
        val pngFields = CANARIES.keys.filter { it.startsWith("png-") }.toSet()
        assertEquals(jpegFields, inJpeg, "every JPEG metadata field of the source holds its canary")
        assertEquals(pngFields, inPng, "every PNG metadata chunk of the source holds its canary")
        assertTrue(
            problems(jpegSource).any { it.startsWith("APPn markers") },
            "the source has APPn segments beyond the two fixed ones",
        )
    }

    /** PlanMasker with a fixed plan on a path, source with every metadata kind and EXIF orientation 6. */
    @Test
    fun aPlanOnAPathWithEveryMetadataKindWritesAMaskedFileWithoutAnyOfIt() {
        val plain = TestImages.jpeg(640, 480)
        val source = withEveryMetadataKind(plain, 6)
        val input = File(dir, "in.jpg").apply { writeBytes(source) }
        val out = File(dir, "out.jpg")
        val before = sha256(source)
        val planMasker = PlanMasker()
        val plan = planMasker.planFile(input.path)
        assertEquals(480 to 640, plan.width to plan.height, "orientation 6 turns the picture")
        val edited = plan.withMasks(listOf(Mask.manual(140f, 150f, 240f, 300f, planMasker.masking)))
        val result = planMasker.maskFile(input.path, OutputTarget.NewFile(out.path), edited)
        val bytes = out.readBytes()
        assertClean(bytes, "plan on a path")
        assertLibraryValidatorAgrees(bytes)
        assertEquals(480 to 640, TestImages.decodedSize(bytes), "the rotated size")
        val unmasked = PixelChecks.rotate90(PixelChecks.decode(plain))
        val reference = PixelChecks.reference(unmasked) {
            masker.applyMasks(it, MaskPlan(it.width, it.height, edited.masks))
        }
        TestImages.assertMaskedFile(bytes, reference, unmasked, result.masks)
        assertEquals(before, sha256(input.readBytes()), "the source file is unchanged")
        assertEquals(listOf("in.jpg", "out.jpg"), dir.list()!!.sorted(), "no other file is left")
    }

    @Test
    fun aJpegStreamWithEveryMetadataKindIsMaskedWithoutAnyOfIt() {
        val plain = TestImages.jpeg(640, 480)
        val source = withEveryMetadataKind(plain, 1)
        val out = ByteArrayOutputStream()
        val result = masker.maskJpeg(ByteArrayInputStream(source), out)
        val bytes = out.toByteArray()
        assertClean(bytes, "JPEG stream")
        assertLibraryValidatorAgrees(bytes)
        val unmasked = PixelChecks.decode(plain)
        val reference = PixelChecks.reference(unmasked) { masker.maskInPlace(it) }
        TestImages.assertMaskedFile(bytes, reference, unmasked, result.masks)
    }

    @Test
    fun aPngStreamWithTextAndExifChunksIsMaskedWithoutAnyOfThem() {
        val bitmap = TestImages.picture(640, 480)
        val source = pngWithMetadata(TestImages.png(bitmap))
        val out = ByteArrayOutputStream()
        val result = masker.maskJpeg(ByteArrayInputStream(source), out)
        val bytes = out.toByteArray()
        assertClean(bytes, "PNG stream")
        assertLibraryValidatorAgrees(bytes)
        val unmasked = PixelChecks.decode(TestImages.png(bitmap))
        val reference = PixelChecks.reference(unmasked) { masker.maskInPlace(it) }
        TestImages.assertMaskedFile(bytes, reference, unmasked, result.masks)
    }

    /** ST-001 AC8: no face found, or an empty plan: the file is still re-encoded without the metadata. */
    @Test
    fun zeroFacesAndAnEmptyPlanStillReEncodeWithoutTheMetadata() {
        val plain = TestImages.jpeg(640, 480)
        val source = withEveryMetadataKind(plain, 1)
        val unmasked = PixelChecks.decode(plain)
        val whole = PixelChecks.Box(0, 0, unmasked.width, unmasked.height)

        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(NoFaceRunner())).use { none ->
            val out = ByteArrayOutputStream()
            val result = none.maskJpeg(ByteArrayInputStream(source), out)
            assertEquals(emptyList(), result.faces, "the model finds no face")
            assertEquals(emptyList(), result.masks)
            val bytes = out.toByteArray()
            assertClean(bytes, "no face, stream")
            assertLibraryValidatorAgrees(bytes)
            assertFalse(bytes.contentEquals(source), "the output is not a copy of the input")
            assertFalse(bytes.contentEquals(plain), "the output is not a copy of the picture either")
            assertTrue(PixelChecks.meanDiff(PixelChecks.decode(bytes), unmasked, whole) <= TestImages.OUTSIDE_TOLERANCE)
        }

        val input = File(dir, "in.jpg").apply { writeBytes(source) }
        val out = File(dir, "out.jpg")
        val planMasker = PlanMasker()
        val result = planMasker.maskFile(input.path, OutputTarget.NewFile(out.path), planMasker.planFile(input.path))
        assertEquals(emptyList(), result.masks, "an empty plan")
        val bytes = out.readBytes()
        assertClean(bytes, "empty plan, path")
        assertLibraryValidatorAgrees(bytes)
        assertFalse(bytes.contentEquals(source), "the output is not a copy of the input")
        assertTrue(PixelChecks.meanDiff(PixelChecks.decode(bytes), unmasked, whole) <= TestImages.OUTSIDE_TOLERANCE)
        assertContentEquals(source, input.readBytes(), "the source file is unchanged")
        assertEquals(listOf("in.jpg", "out.jpg"), dir.list()!!.sorted())
    }

    /** ST-017: two consecutive runs on the same input give the same bytes (no time stamp or random value inside). */
    @Test
    fun twoConsecutiveRunsGiveByteIdenticalOutput() {
        val source = withEveryMetadataKind(TestImages.jpeg(640, 480), 1)
        val first = ByteArrayOutputStream().also { masker.maskJpeg(ByteArrayInputStream(source), it) }.toByteArray()
        val second = ByteArrayOutputStream().also { masker.maskJpeg(ByteArrayInputStream(source), it) }.toByteArray()
        assertTrue(first.isNotEmpty())
        assertContentEquals(first, second)
        val input = File(dir, "in.jpg").apply { writeBytes(source) }
        val planMasker = PlanMasker()
        val plan = planMasker.planFile(
            input.path,
        ).withMasks(listOf(Mask.manual(200f, 120f, 360f, 300f, planMasker.masking)))
        val a = File(dir, "a.jpg")
        val b = File(dir, "b.jpg")
        planMasker.maskFile(input.path, OutputTarget.NewFile(a.path), plan)
        planMasker.maskFile(input.path, OutputTarget.NewFile(b.path), plan)
        assertContentEquals(a.readBytes(), b.readBytes())
    }

    // ---- negative controls: each check must FAIL on its planted defect ----

    @Test
    fun controlACopyOfTheInputAsOutputFailsTheCheckAndTheLibraryValidator() {
        val source = withEveryMetadataKind(TestImages.jpeg(640, 480), 1)
        val found = problems(source)
        assertTrue(found.any { it.startsWith("canary ") }, "$found")
        assertTrue(found.any { it.startsWith("APPn markers") }, "$found")
        assertTrue(OutputValidator.findings(source, MetadataPolicy.StripAll).isNotEmpty())
    }

    @Test
    fun controlAPlantedXmpInACleanOutputFailsTheCheckAndTheLibraryValidator() {
        val source = withEveryMetadataKind(TestImages.jpeg(640, 480), 1)
        val clean = ByteArrayOutputStream().also { masker.maskJpeg(ByteArrayInputStream(source), it) }.toByteArray()
        assertClean(clean, "control: the clean output")
        val app0End = 2 + 2 + u16(clean, 4)
        val xmp = seg(
            0xE1,
            "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + 0 + CANARIES.getValue("xmp").encodeToByteArray(),
        )
        val planted = clean.copyOfRange(0, app0End) + xmp + clean.copyOfRange(app0End, clean.size)
        val found = problems(planted)
        assertTrue(found.contains("canary xmp (ascii)"), "$found")
        assertTrue(found.any { it.startsWith("APPn markers") }, "$found")
        assertTrue(OutputValidator.findings(planted, MetadataPolicy.StripAll).isNotEmpty())
    }

    @Test
    fun controlABytePastTheEoiFailsTheCheckAndTheLibraryValidator() {
        val source = withEveryMetadataKind(TestImages.jpeg(640, 480), 1)
        val clean = ByteArrayOutputStream().also { masker.maskJpeg(ByteArrayInputStream(source), it) }.toByteArray()
        val planted = clean + byteArrayOf(0)
        assertTrue(problems(planted).any { it.startsWith("bytes after the first EOI") }, "${problems(planted)}")
        assertTrue(OutputValidator.findings(planted, MetadataPolicy.StripAll).isNotEmpty())
    }

    /** A re-encode that masks nothing is clean as to metadata but fails the pixel check (a face left unmasked). */
    @Test
    fun controlAnUnmaskedReEncodeFailsThePixelCheck() {
        val plain = TestImages.jpeg(640, 480)
        val source = withEveryMetadataKind(plain, 1)
        val out = ByteArrayOutputStream()
        val result = masker.maskJpeg(ByteArrayInputStream(source), out)
        val unmasked = PixelChecks.decode(plain)
        val reference = PixelChecks.reference(unmasked) { masker.maskInPlace(it) }
        TestImages.assertMaskedFile(out.toByteArray(), reference, unmasked, result.masks)
        val reEncoded = ByteArrayOutputStream().also {
            assertTrue(unmasked.compress(Bitmap.CompressFormat.JPEG, 92, it))
        }
        val noMask = JpegOutput.finish(reEncoded.toByteArray(), null, MetadataPolicy.StripAll, Size(640, 480)).bytes
        assertClean(noMask, "control: the unmasked re-encode is clean as to metadata")
        val e =
            assertFailsWith<AssertionError> { TestImages.assertMaskedFile(noMask, reference, unmasked, result.masks) }
        assertTrue(e.message.orEmpty().contains("the output holds the masked pixels"), "${e.message}")
    }
}
