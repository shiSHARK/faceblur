// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Code-generated JPEG fixtures (no committed binaries, no faces). The pixel data comes from the JDK encoder
 * (`javax.imageio`), so the base file is a real, decodable baseline JPEG; all metadata is added by this
 * builder, which has its own tiny segment splitter and shares no code with the checker.
 */
public object JpegFixtures {
    /** A deterministic synthetic image: gradient with seeded blocks (not a face, no personal data). */
    public fun syntheticJpeg(
        width: Int = 64,
        height: Int = 48,
        seed: Long = 1L,
        quality: Float = 0.8f,
        progressive: Boolean = false,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(seed)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or ((x + y) and 0xFF))
            }
        }
        repeat(6) {
            val bx = rnd.nextInt(width)
            val by = rnd.nextInt(height)
            val color = rnd.nextInt(0x1000000)
            for (y in by until minOf(height, by + 8)) for (x in bx until minOf(width, bx + 8)) image.setRGB(x, y, color)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
        if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    public fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        require(len <= 0xFFFF) { "payload too large for one segment" }
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    public fun app1Exif(tiff: ByteArray): ByteArray = segment(
        0xE1,
        "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff,
    )

    public fun app1Xmp(canary: String = "XMP-CANARY"): ByteArray = segment(
        0xE1,
        (
            "http://ns.adobe.com/xap/1.0/\u0000<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>" +
                "<x:xmpmeta xmlns:x='adobe:ns:meta/'><dc:description>$canary</dc:description></x:xmpmeta>"
            )
            .toByteArray(Charsets.ISO_8859_1),
    )

    public fun app13Iptc(canary: String = "IPTC-CANARY"): ByteArray = segment(
        0xED,
        "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + "8BIM".toByteArray() + canary.toByteArray(),
    )

    public fun com(text: String = "COM-CANARY"): ByteArray = segment(0xFE, text.toByteArray())

    public fun mpf(): ByteArray = segment(
        0xE2,
        "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    )

    public fun icc(profile: ByteArray, chunk: Int = 1, chunks: Int = 1): ByteArray = segment(
        0xE2,
        "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(chunk.toByte(), chunks.toByte()) +
            profile,
    )

    /** Splits a JPEG made by the JDK encoder: JFIF segment, coding segments before SOS, and SOS + data + EOI. */
    public class Parts(public val jfif: ByteArray?, public val coding: List<ByteArray>, public val scan: ByteArray)

    public fun split(jpeg: ByteArray): Parts {
        var pos = 2
        val segments = mutableListOf<ByteArray>()
        while (true) {
            check(jpeg[pos] == 0xFF.toByte()) { "fixture splitter lost sync" }
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            segments.add(jpeg.copyOfRange(pos, pos + 2 + len))
            pos += 2 + len
        }
        val first = segments.firstOrNull()
        val hasJfif = first != null && (first[1].toInt() and 0xFF) == 0xE0
        return Parts(
            if (hasJfif) first else null,
            if (hasJfif) segments.drop(1) else segments,
            jpeg.copyOfRange(pos, jpeg.size),
        )
    }
}

/** Assembles a JPEG from the base and planted segments. Default output is the correct strip-all shape. */
public class JpegBuilder(base: ByteArray = JpegFixtures.syntheticJpeg()) {
    private val parts = JpegFixtures.split(base)

    /** Segments placed directly after SOI, before the JFIF segment. */
    public val beforeJfif: MutableList<ByteArray> = mutableListOf()

    /** Segments after JFIF, before the coding segments (EXIF, ICC, planted metadata). */
    public val afterJfif: MutableList<ByteArray> = mutableListOf()

    /** Segments between the coding segments and SOS. */
    public val beforeScan: MutableList<ByteArray> = mutableListOf()

    /** The coding segments (DQT, SOF, DHT, ...) in order; tests may replace or patch them. */
    public val coding: MutableList<ByteArray> = parts.coding.toMutableList()

    /** SOS segment, entropy-coded data and the final EOI; tests may patch it. */
    public var scan: ByteArray = parts.scan

    /** Bytes inserted after the entropy-coded data and before the final EOI. */
    public var beforeEoi: ByteArray = ByteArray(0)

    /** Leave out the scan: the file ends with EOI right after the coding segments. */
    public var omitScan: Boolean = false

    public var includeJfif: Boolean = true
    public var trailing: ByteArray = ByteArray(0)
    public var dropEoi: Boolean = false

    /** Raw bytes inserted right after the last segment before SOS (for example a stray SOI). */
    public var rawBeforeScan: ByteArray = ByteArray(0)

    public fun exif(tiff: ByteArray): JpegBuilder = apply { afterJfif.add(JpegFixtures.app1Exif(tiff)) }

    /** The correct strip-all EXIF: IFD0 = { Orientation = 1 }. */
    public fun strippedExif(littleEndian: Boolean = true): JpegBuilder = apply {
        val t = TiffBuilder(littleEndian)
        t.ifd0.add(t.orientation(1))
        exif(t.build())
    }

    /** A copy of the frame header (SOF) segment of the base image. */
    public fun frameHeader(): ByteArray = coding.first { (it[1].toInt() and 0xFF) in 0xC0..0xC2 }.copyOf()

    public fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        beforeJfif.forEach { out.write(it) }
        if (includeJfif) parts.jfif?.let { out.write(it) }
        afterJfif.forEach { out.write(it) }
        coding.forEach { out.write(it) }
        beforeScan.forEach { out.write(it) }
        out.write(rawBeforeScan)
        if (omitScan) {
            if (!dropEoi) out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        } else {
            val body = scan.copyOf(scan.size - 2)
            out.write(body)
            out.write(beforeEoi)
            if (!dropEoi) out.write(scan, scan.size - 2, 2)
        }
        out.write(trailing)
        return out.toByteArray()
    }
}

/** Minimal ICC profiles for the colour-profile cases (header, one `desc` tag). Not for colour management. */
public object IccFixtures {
    /** A profile whose `desc` tag is a v4 `mluc` (UTF-16) record instead of the v2 ASCII record. */
    public fun profileWithMultiLocalizedDescription(description: String): ByteArray {
        val utf16 = description.toByteArray(Charsets.UTF_16BE)
        val tableEnd = 128 + 4 + 12
        val descLen = 28 + utf16.size
        val p = ByteArray(tableEnd + (descLen + 3) / 4 * 4)
        fun put32(at: Int, v: Int) {
            p[at] = (v shr 24).toByte()
            p[at + 1] = (v shr 16).toByte()
            p[at + 2] = (v shr 8).toByte()
            p[at + 3] = v.toByte()
        }
        put32(0, p.size)
        "mntr".toByteArray().copyInto(p, 12)
        "RGB ".toByteArray().copyInto(p, 16)
        "acsp".toByteArray().copyInto(p, 36)
        put32(128, 1)
        "desc".toByteArray().copyInto(p, 132)
        put32(136, tableEnd)
        put32(140, descLen)
        "mluc".toByteArray().copyInto(p, tableEnd)
        put32(tableEnd + 8, 1)
        put32(tableEnd + 12, 12)
        put32(tableEnd + 20, utf16.size)
        put32(tableEnd + 24, 28)
        utf16.copyInto(p, tableEnd + 28)
        return p
    }

    /** A real, valid sRGB profile from the JDK (the decoder accepts it; the synthetic profiles below it do not). */
    public fun validSrgb(): ByteArray = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).data

    /**
     * A profile with two tags (`desc` and `cprt`) that share one data block, as real profiles share identical curves.
     * Entry 0 can be patched to point elsewhere; the data stays covered through entry 1.
     */
    public fun profileWithTwoTags(description: String): ByteArray {
        val text = description.toByteArray(Charsets.ISO_8859_1)
        val tableEnd = 128 + 4 + 24
        val descLen = 12 + text.size + 1
        val p = ByteArray(tableEnd + (descLen + 3) / 4 * 4)
        fun put32(at: Int, v: Int) {
            p[at] = (v shr 24).toByte()
            p[at + 1] = (v shr 16).toByte()
            p[at + 2] = (v shr 8).toByte()
            p[at + 3] = v.toByte()
        }
        put32(0, p.size)
        "mntr".toByteArray().copyInto(p, 12)
        "RGB ".toByteArray().copyInto(p, 16)
        "acsp".toByteArray().copyInto(p, 36)
        put32(128, 2)
        for ((i, name) in listOf("desc", "cprt").withIndex()) {
            name.toByteArray().copyInto(p, 132 + 12 * i)
            put32(136 + 12 * i, tableEnd)
            put32(140 + 12 * i, descLen)
        }
        "desc".toByteArray().copyInto(p, tableEnd)
        put32(tableEnd + 8, text.size + 1)
        text.copyInto(p, tableEnd + 12)
        return p
    }

    /** SHA-256 (lower-case hex) of [bytes], computed independently of the checker. */
    public fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A valid profile followed by [extra] bytes that belong to no tag (the size field covers them). */
    public fun profileWithUnreferencedBytes(description: String, extra: ByteArray): ByteArray {
        val p = profile(description) + extra
        for (i in 0 until 4) p[i] = (p.size shr (24 - 8 * i)).toByte()
        return p
    }

    public fun profile(description: String, colorSpace: String = "RGB ", size: Int? = null): ByteArray {
        val text = description.toByteArray(Charsets.ISO_8859_1)
        val descLen = 12 + text.size + 1
        val descPadded = (descLen + 3) / 4 * 4
        val tableEnd = 128 + 4 + 12
        val total = size ?: (tableEnd + descPadded)
        val p = ByteArray(maxOf(total, tableEnd + descPadded))
        fun put32(at: Int, v: Int) {
            p[at] = (v shr 24).toByte()
            p[at + 1] = (v shr 16).toByte()
            p[at + 2] = (v shr 8).toByte()
            p[at + 3] =
                v.toByte()
        }
        fun putStr(at: Int, s: String) = s.toByteArray(Charsets.ISO_8859_1).copyInto(p, at)
        put32(0, p.size)
        put32(8, 0x02100000)
        putStr(12, "mntr")
        putStr(16, colorSpace)
        putStr(20, "XYZ ")
        putStr(36, "acsp")
        put32(128, 1)
        putStr(132, "desc")
        put32(136, tableEnd)
        put32(140, descPadded)
        putStr(tableEnd, "desc")
        put32(tableEnd + 8, text.size + 1)
        text.copyInto(p, tableEnd + 12)
        if (size == null) put32(0, p.size)
        return p
    }
}
