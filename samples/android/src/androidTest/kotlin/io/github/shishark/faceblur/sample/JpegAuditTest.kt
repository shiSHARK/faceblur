// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The audit that judges the export is itself checked: a clean file passes and every planted defect is found. */
class JpegAuditTest {
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(marker)
        out.write((payload.size + 2) shr 8)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private val jfif =
        segment(
            0xE0,
            "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1),
        )
    private val frame = segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
    private val scan = segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)) + byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34)

    /** A TIFF block of little-endian IFD0 with the given 12-byte entries, then the next-directory offset. */
    private fun exif(entries: List<ByteArray>, next: Int = 0): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write("II*\u0000".toByteArray(Charsets.ISO_8859_1))
        tiff.write(byteArrayOf(8, 0, 0, 0))
        tiff.write(byteArrayOf(entries.size.toByte(), 0))
        entries.forEach { tiff.write(it) }
        tiff.write(byteArrayOf(next.toByte(), 0, 0, 0))
        return segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray())
    }

    private fun orientation(value: Int) = byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, value.toByte(), 0, 0, 0)

    private fun jpeg(vararg parts: ByteArray, tail: ByteArray = byteArrayOf()): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + parts.fold(byteArrayOf()) { a, b -> a + b } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail

    private fun problems(data: ByteArray) = JpegAudit.audit(data).problems

    @Test
    fun aFileWithOnlyOrientationOneExifPasses() {
        assertEquals(emptyList<String>(), problems(jpeg(jfif, exif(listOf(orientation(1))), frame, scan)))
        assertEquals(emptyList<String>(), problems(jpeg(jfif, frame, scan)))
    }

    @Test
    fun everyPlantedDefectIsFound() {
        val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.ISO_8859_1))
        val mpf = segment(0xE2, "MPF\u0000II*\u0000".toByteArray(Charsets.ISO_8859_1))
        val gpsPointer = byteArrayOf(0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 0x20, 0, 0, 0)
        val cases = mapOf(
            "not a JPEG" to byteArrayOf(1, 2, 3, 4),
            "XMP packet" to jpeg(jfif, xmp, frame, scan),
            "multi-picture" to jpeg(jfif, mpf, frame, scan),
            "comment" to jpeg(jfif, segment(0xFE, "hello".toByteArray()), frame, scan),
            "application segment" to jpeg(jfif, segment(0xED, "Photoshop 3.0".toByteArray()), frame, scan),
            "EXIF tag" to jpeg(jfif, exif(listOf(orientation(6))), frame, scan),
            "EXIF directory with 2 tags" to jpeg(jfif, exif(listOf(orientation(1), gpsPointer)), frame, scan),
            "second directory" to jpeg(jfif, exif(listOf(orientation(1)), next = 0x40), frame, scan),
            "bytes after the end-of-image" to
                jpeg(jfif, frame, scan, tail = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)),
            "no end-of-image" to byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif + frame + scan,
            "2 frames" to jpeg(jfif, frame, frame, scan),
            "second start-of-image" to jpeg(jfif, byteArrayOf(0xFF.toByte(), 0xD8.toByte()), frame, scan),
            "hdrgm" to jpeg(jfif, frame, scan.copyOf(scan.size) + "hdrgm".toByteArray()),
        )
        for ((expected, data) in cases) {
            val found = problems(data)
            assertTrue("a planted '$expected' was not found: $found", found.any { expected in it })
        }
    }
}
