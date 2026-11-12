// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HEIC input in file mode (JPEG and HEIC in, JPEG out) on the device's own HEVC decoder, with a SYNTHETIC
 * fixture: a generated pattern without any face (tools/build_heic_fixture.sh), which carries canary metadata (EXIF,
 * TIFF, XMP, GPS) and the orientation tag 6. The masks come from the test ([PlanMasker], no model, no ONNX Runtime): the
 * subject is the HEIC route through the platform decoder, the masks, the encoder and the writer.
 *
 * The checks, each with a control that makes it able to fail:
 * - the input really holds the canaries and the orientation (control for the metadata checks);
 * - the output is a JPEG at the upright size (orientation baked in), whose only APPn segment is the library's EXIF
 *   with Orientation = 1 and nothing else, with no COM segment, none of the canary strings, nothing after the end of
 *   image;
 * - inside the mask the output is flat while the unmasked pipeline's output (same decode, same encoder, empty plan)
 *   still shows the pattern, and outside the masks both outputs agree;
 * - the unmasked pipeline run twice gives identical bytes (decoder and encoder are deterministic for this input);
 * - no file other than the two outputs is left in the scratch directory.
 *
 * The fixture is not committed: the build task `:faceblur:generateHeicFixture` generates it at test time from
 * tools/build_heic_fixture.sh (macOS) into the build directory, and the harness places it in the test app's private
 * storage (never in the APK). A missing fixture FAILS the test (it is never skipped).
 */
@RunWith(AndroidJUnit4::class)
class HeicInputDeviceTest {
    private companion object {
        const val FIXTURE = "synthetic-pattern-canary-256x192.heic"

        /** Stored size of the fixture; the tag turns it to 192 × 256 when shown. */
        const val UPRIGHT_WIDTH = 192
        const val UPRIGHT_HEIGHT = 256

        /** The texture block in the upright frame (the stored block 96..160 × 64..128 turned 90° clockwise). */
        val BLOCK = PixelChecks.Box(64, 96, 128, 160)

        /** Smallest mean channel difference between the masked and the unmasked output inside the mask core. */
        const val SEPARATION = 32.0

        /** Largest standard deviation of the luma inside the masked core (a flat fill, JPEG noise allowed). */
        const val FLAT_SD = 10.0

        /**
         * Smallest standard deviation of the luma inside the unmasked core (the pattern is there to be removed). The
         * pattern's two colours have luma 97 and 41, so an even mix has a standard deviation of 28.
         */
        const val TEXTURE_SD = 20.0

        /** Largest mean channel difference away from the masks: same decode, same encoder, so the outputs agree. */
        const val OUTSIDE_TOLERANCE = 1.0

        val CANARIES =
            listOf(
                "FBCANARY-EXIF-USERCOMMENT",
                "FBCANARY-EXIF-SERIAL",
                "FBCANARY-MAKE",
                "FBCANARY-MODEL",
                "FBCANARY-ARTIST",
                "FBCANARY-TIFF-DESC",
                "FBCANARY-XMP-CREATOR",
                "FBCANARY-XMP-RIGHTS",
            )
    }

    private fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    /**
     * The marker codes of the segments before the scan, the payloads of the APP1 segments, and whether the file ends
     * exactly at the end-of-image marker. Written for this test; it shares no code with the library.
     */
    private class Walk(val markers: List<Int>, val app1: List<ByteArray>, val endsAtEoi: Boolean)

    private fun walk(b: ByteArray): Walk {
        assertTrue(b.size > 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8, "starts with SOI")
        val markers = mutableListOf<Int>()
        val app1 = mutableListOf<ByteArray>()
        var p = 2
        while (true) {
            assertTrue(p + 4 <= b.size && (b[p].toInt() and 0xFF) == 0xFF, "a marker at $p")
            val code = b[p + 1].toInt() and 0xFF
            markers += code
            val length = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            assertTrue(p + 2 + length <= b.size, "segment inside the file")
            if (code == 0xE1) app1 += b.copyOfRange(p + 4, p + 2 + length)
            p += 2 + length
            if (code == 0xDA) break
        }
        // Entropy-coded data: 0xFF is followed by 0x00 or a restart marker until the end of image.
        while (p + 1 < b.size) {
            if ((b[p].toInt() and 0xFF) == 0xFF) {
                val next = b[p + 1].toInt() and 0xFF
                if (next == 0xD9) return Walk(markers, app1, p + 2 == b.size)
                assertTrue(next == 0x00 || next in 0xD0..0xD7, "only stuffing or restart markers inside the scan")
                p += 2
            } else {
                p++
            }
        }
        return Walk(markers, app1, false)
    }

    /**
     * The one APP1 the library writes (orientation baked in, written as 1): "Exif\0\0", a TIFF header, an IFD0 with ONE
     * entry (Orientation, SHORT, 1 value, value 1) and no next IFD. Anything more would carry metadata.
     */
    private fun assertOnlyOrientationOne(label: String, payload: ByteArray) {
        val header = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
        assertContentEquals(header, payload.copyOf(6), "$label: an Exif segment")
        val tiff = payload.copyOfRange(6, payload.size)
        assertEquals(
            8 + 2 + 12 + 4,
            tiff.size,
            "$label: the TIFF holds a header, one entry and the end of IFDs, nothing else",
        )
        val big = tiff[0] == 'M'.code.toByte()
        assertTrue(big || tiff[0] == 'I'.code.toByte(), "$label: a TIFF byte order")
        fun u16(at: Int) = if (big) {
            ((tiff[at].toInt() and 0xFF) shl 8) or (tiff[at + 1].toInt() and 0xFF)
        } else {
            ((tiff[at + 1].toInt() and 0xFF) shl 8) or (tiff[at].toInt() and 0xFF)
        }
        fun u32(at: Int) = if (big) (u16(at) shl 16) or u16(at + 2) else (u16(at + 2) shl 16) or u16(at)
        assertEquals(42, u16(2), "$label: TIFF magic")
        assertEquals(8, u32(4), "$label: IFD0 follows the header")
        assertEquals(1, u16(8), "$label: one IFD0 entry")
        assertEquals(0x0112, u16(10), "$label: the entry is Orientation")
        assertEquals(3, u16(12), "$label: type SHORT")
        assertEquals(1, u32(14), "$label: one value")
        assertEquals(1, u16(18), "$label: Orientation = 1")
        assertEquals(0, u32(22), "$label: no next IFD (no thumbnail, no GPS or EXIF sub-IFD)")
    }

    private fun assertPrivateJpeg(label: String, bytes: ByteArray) {
        val w = walk(bytes)
        Log.i(
            DeviceFixtures.TAG,
            "$label: ${bytes.size} bytes, markers before the scan ${w.markers.joinToString { "%02X".format(it) }}",
        )
        assertTrue(w.endsAtEoi, "$label: nothing after the end of image")
        val forbidden = w.markers.filter { it in 0xE2..0xEF || it == 0xFE }
        assertEquals(emptyList(), forbidden, "$label: no ICC, MPF, IPTC, other APPn or COM segment")
        assertEquals(1, w.app1.size, "$label: exactly one APP1")
        assertOnlyOrientationOne(label, w.app1.single())
        for (canary in CANARIES) assertFalse(contains(bytes, canary), "$label: no $canary")
        for (container in listOf("http://ns.adobe.com", "<x:xmpmeta", "FBCANARY")) {
            assertFalse(contains(bytes, container), "$label: no '$container' bytes")
        }
    }

    private fun luma(bitmap: Bitmap, box: PixelChecks.Box): Pair<Double, Double> {
        val w = box.right - box.left
        val h = box.bottom - box.top
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, box.left, box.top, w, h) }
        val y = px.map { 0.299 * Color.red(it) + 0.587 * Color.green(it) + 0.114 * Color.blue(it) }
        val mean = y.average()
        return mean to sqrt(y.sumOf { (it - mean) * (it - mean) } / y.size)
    }

    @Test
    fun aHeicInputGivesAMaskedMetadataFreeJpegAtTheUprightSize() {
        DeviceFixtures.logHeader()
        val input = DeviceFixtures.file(FIXTURE)
        val inputBytes = input.readBytes()

        // Controls on the input: a HEIC file with all the canaries, so that their absence in the output means something.
        assertEquals("ftyp", String(inputBytes, 4, 4, Charsets.US_ASCII), "an ISO base media file")
        assertTrue(contains(inputBytes, "heic") || contains(inputBytes, "mif1"), "a HEIF brand")
        for (canary in CANARIES) assertTrue(contains(inputBytes, canary), "the input holds $canary")

        val scratch = DeviceFixtures.scratch("heic")
        val planMasker = PlanMasker()
        val empty = planMasker.planFile(input.path)
        assertEquals(
            UPRIGHT_WIDTH to UPRIGHT_HEIGHT,
            empty.width to empty.height,
            "orientation 6 is applied: upright size",
        )
        assertEquals(emptyList(), empty.masks, "nothing is detected")

        val mask = Mask.manual(
            BLOCK.left.toFloat(),
            BLOCK.top.toFloat(),
            BLOCK.right.toFloat(),
            BLOCK.bottom.toFloat(),
            planMasker.masking,
        )
        val masked = File(scratch, "masked.jpg")
        val result = planMasker.maskFile(input.path, OutputTarget.NewFile(masked.path), empty.withMasks(listOf(mask)))
        assertEquals(UPRIGHT_WIDTH to UPRIGHT_HEIGHT, result.outputWidth to result.outputHeight, "output size")
        assertEquals(masked.length(), result.outputBytes, "the call reports the written size")

        // The unmasked pipeline: the same decode and encode with an empty plan, twice (determinism, control for flatness).
        val unmaskedA = File(scratch, "unmasked-a.jpg")
        val unmaskedB = File(scratch, "unmasked-b.jpg")
        planMasker.maskFile(input.path, OutputTarget.NewFile(unmaskedA.path), planMasker.planFile(input.path))
        planMasker.maskFile(input.path, OutputTarget.NewFile(unmaskedB.path), planMasker.planFile(input.path))
        assertContentEquals(
            unmaskedA.readBytes(),
            unmaskedB.readBytes(),
            "the HEIC decode and the encode are deterministic",
        )

        val maskedBytes = masked.readBytes()
        assertPrivateJpeg("masked output", maskedBytes)
        assertPrivateJpeg("unmasked output", unmaskedA.readBytes())

        val out = PixelChecks.decode(maskedBytes)
        val plain = PixelChecks.decode(unmaskedA.readBytes())
        assertEquals(
            UPRIGHT_WIDTH to UPRIGHT_HEIGHT,
            out.width to out.height,
            "decoded output size (no tag left to apply)",
        )
        val core = PixelChecks.core(mask, 1.0, out.width, out.height)
        val (plainMean, plainSd) = luma(plain, core)
        val (outMean, outSd) = luma(out, core)
        val separation = PixelChecks.meanDiff(out, plain, core)
        Log.i(
            DeviceFixtures.TAG,
            "heic mask core ${core.right - core.left}x${core.bottom - core.top}: separation %.1f, luma sd masked %.1f, unmasked %.1f (means %.0f / %.0f)"
                .format(separation, outSd, plainSd, outMean, plainMean),
        )
        assertTrue(plainSd >= TEXTURE_SD, "control: the decoded HEIC shows the pattern in the core (sd $plainSd)")
        assertTrue(outSd <= FLAT_SD, "the masked core is flat (sd $outSd)")
        assertTrue(separation >= SEPARATION, "the masked core differs from the pattern (diff $separation)")

        val corner = checkNotNull(PixelChecks.freeCorner(out.width, out.height, listOf(mask), 1.0)) { "a free corner" }
        val outside = PixelChecks.meanDiff(out, plain, corner)
        assertTrue(outside <= OUTSIDE_TOLERANCE, "away from the mask the outputs agree (diff $outside)")

        // Nothing else was left behind (PRIV-005): exactly the three files this test asked for.
        assertEquals(
            setOf("masked.jpg", "unmasked-a.jpg", "unmasked-b.jpg"),
            scratch.list().orEmpty().toSet(),
            "no copy of the source or temporary file is left",
        )
        Log.i(
            DeviceFixtures.TAG,
            "heic input: JPEG output, masked, canaries absent, upright ${out.width}x${out.height}",
        )
    }

    /** [bytes] with [segment] (a complete marker segment) inserted right after the SOI. */
    private fun withSegment(bytes: ByteArray, segment: ByteArray): ByteArray =
        bytes.copyOf(2) + segment + bytes.copyOfRange(2, bytes.size)

    private fun app(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    /** Controls: the privacy scan above fails on each planted defect, so its passes mean something. */
    @Test
    fun theScanFailsOnPlantedMetadata() {
        DeviceFixtures.logHeader()
        val input = DeviceFixtures.file(FIXTURE)
        val scratch = DeviceFixtures.scratch("heic-control")
        val planMasker = PlanMasker()
        val target = File(scratch, "good.jpg")
        planMasker.maskFile(input.path, OutputTarget.NewFile(target.path), planMasker.planFile(input.path))
        val good = target.readBytes()
        assertPrivateJpeg("control: the unchanged output", good)

        val ascii = { text: String -> text.toByteArray(Charsets.US_ASCII) }
        val planted =
            mapOf(
                "a COM segment" to withSegment(good, app(0xFE, ascii("hello"))),
                "an XMP packet" to withSegment(
                    good,
                    app(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>")),
                ),
                "a canary string in a COM segment" to withSegment(good, app(0xFE, ascii(CANARIES.first()))),
                "an ICC segment" to withSegment(good, app(0xE2, ascii("ICC_PROFILE\u0000") + ByteArray(8))),
                "a byte after the end of image" to good + byteArrayOf(0),
            )
        for ((what, bytes) in planted) {
            assertFailsWith<AssertionError>("the scan must reject $what") { assertPrivateJpeg("planted", bytes) }
        }
        // The Exif segment itself: Orientation 6 instead of 1, and a count of two IFD0 entries.
        val app1 = (2 until good.size - 1).first { good[it] == 0xFF.toByte() && good[it + 1] == 0xE1.toByte() }
        val tiff = app1 + 4 + 6
        val big = good[tiff] == 'M'.code.toByte()
        val turned = good.copyOf().also { it[tiff + if (big) 19 else 18] = 6 }
        assertFailsWith<AssertionError>("the scan must reject Orientation 6") { assertPrivateJpeg("planted", turned) }
        val counted = good.copyOf().also { it[tiff + if (big) 9 else 8] = 2 }
        assertFailsWith<AssertionError>("the scan must reject a second IFD0 entry") {
            assertPrivateJpeg("planted", counted)
        }
    }
}
