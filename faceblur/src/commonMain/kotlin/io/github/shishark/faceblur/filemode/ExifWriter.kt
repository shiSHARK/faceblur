// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

/** EXIF tag numbers and TIFF types of the closed list (ADR 0005 decision 3). */
internal object Exif {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5

    // IFD0
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val ORIENTATION = 0x0112
    const val EXIF_IFD = 0x8769
    const val GPS_IFD = 0x8825

    // Exif sub-IFD
    const val DATE_TIME_ORIGINAL = 0x9003
    const val DATE_TIME_DIGITIZED = 0x9004
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val OFFSET_TIME_DIGITIZED = 0x9012
    const val SUB_SEC_TIME_ORIGINAL = 0x9291

    // GPS IFD
    const val GPS_VERSION_ID = 0x0000
    const val GPS_LATITUDE_REF = 0x0001
    const val GPS_LATITUDE = 0x0002
    const val GPS_LONGITUDE_REF = 0x0003
    const val GPS_LONGITUDE = 0x0004
    const val GPS_ALTITUDE_REF = 0x0005
    const val GPS_ALTITUDE = 0x0006
    const val GPS_TIME_STAMP = 0x0007
    const val GPS_IMG_DIRECTION_REF = 0x0010
    const val GPS_IMG_DIRECTION = 0x0011
    const val GPS_DATE_STAMP = 0x001D
    const val GPS_H_POSITIONING_ERROR = 0x001F

    /** Largest APP1 segment the validator accepts, marker and length included (ADR 0005 decision 6). */
    const val MAX_APP1_BYTES = 1_024

    /** "Exif\0\0". */
    val HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
}

/**
 * Writes the output's single EXIF APP1 segment from validated values only (ADR 0005 decision 3): IFD0 with
 * Orientation = 1 always, plus the allowlisted groups that [PhotoMetadata] holds. Big-endian TIFF ("MM"), IFD0 at
 * offset 8, no IFD1 (so never a thumbnail), next-IFD offsets 0, every value word-aligned. Byte-identical on every
 * platform.
 */
internal object ExifWriter {
    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** The complete APP1 segment (FF E1, length, "Exif\0\0", TIFF). [metadata] = null writes Orientation only. */
    fun app1(metadata: PhotoMetadata?): ByteArray {
        val exif = metadata?.let { exifEntries(it) }.orEmpty()
        val gps = metadata?.gps?.let { gpsEntries(it) }.orEmpty()
        val ifd0 = ArrayList<Entry>()
        metadata?.make?.let { ifd0 += ascii(Exif.MAKE, it) }
        metadata?.model?.let { ifd0 += ascii(Exif.MODEL, it) }
        ifd0 += Entry(Exif.ORIENTATION, Exif.SHORT, 1, u16(1))
        if (exif.isNotEmpty()) ifd0 += Entry(Exif.EXIF_IFD, Exif.LONG, 1, u32(0))
        if (gps.isNotEmpty()) ifd0 += Entry(Exif.GPS_IFD, Exif.LONG, 1, u32(0))

        // Layout: IFD0 (+ its values), Exif IFD (+ values), GPS IFD (+ values); offsets from the TIFF header.
        val ifd0Offset = 8
        val exifOffset = ifd0Offset + size(ifd0)
        val gpsOffset = exifOffset + size(exif)
        val withPointers =
            ifd0.map {
                when (it.tag) {
                    Exif.EXIF_IFD -> Entry(it.tag, it.type, 1, u32(exifOffset))
                    Exif.GPS_IFD -> Entry(it.tag, it.type, 1, u32(gpsOffset))
                    else -> it
                }
            }
        val tiff = ArrayList<Byte>()
        tiff += listOf(0x4D, 0x4D, 0x00, 0x2A).map { it.toByte() }
        tiff += u32(ifd0Offset).toList()
        writeIfd(tiff, withPointers, ifd0Offset)
        if (exif.isNotEmpty()) writeIfd(tiff, exif, exifOffset)
        if (gps.isNotEmpty()) writeIfd(tiff, gps, gpsOffset)
        val body = Exif.HEADER + tiff.toByteArray()
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + u16(body.size + 2) + body
    }

    private fun exifEntries(m: PhotoMetadata): List<Entry> = listOfNotNull(
        m.dateTimeOriginal?.let { ascii(Exif.DATE_TIME_ORIGINAL, it) },
        m.dateTimeDigitized?.let { ascii(Exif.DATE_TIME_DIGITIZED, it) },
        m.offsetTimeOriginal?.let { ascii(Exif.OFFSET_TIME_ORIGINAL, it) },
        m.offsetTimeDigitized?.let { ascii(Exif.OFFSET_TIME_DIGITIZED, it) },
        m.subSecTimeOriginal?.let { ascii(Exif.SUB_SEC_TIME_ORIGINAL, it) },
    )

    private fun gpsEntries(g: GpsMetadata): List<Entry> = listOfNotNull(
        Entry(Exif.GPS_VERSION_ID, Exif.BYTE, 4, byteArrayOf(2, 3, 0, 0)),
        ascii(Exif.GPS_LATITUDE_REF, g.latitudeRef.toString()),
        rationals(Exif.GPS_LATITUDE, g.latitude),
        ascii(Exif.GPS_LONGITUDE_REF, g.longitudeRef.toString()),
        rationals(Exif.GPS_LONGITUDE, g.longitude),
        g.altitudeRef?.let { Entry(Exif.GPS_ALTITUDE_REF, Exif.BYTE, 1, byteArrayOf(it.toByte())) },
        g.altitude?.let { rationals(Exif.GPS_ALTITUDE, listOf(it)) },
        g.timeStamp?.let { rationals(Exif.GPS_TIME_STAMP, it) },
        g.imgDirectionRef?.let { ascii(Exif.GPS_IMG_DIRECTION_REF, it.toString()) },
        g.imgDirection?.let { rationals(Exif.GPS_IMG_DIRECTION, listOf(it)) },
        g.dateStamp?.let { ascii(Exif.GPS_DATE_STAMP, it) },
        g.horizontalError?.let { rationals(Exif.GPS_H_POSITIONING_ERROR, listOf(it)) },
    )

    /** Bytes an IFD occupies: count, entries, next-IFD offset, then its out-of-line values (each word-aligned). */
    private fun size(entries: List<Entry>): Int = if (entries.isEmpty()) {
        0
    } else {
        2 + 12 * entries.size + 4 +
            entries.sumOf { if (it.data.size > 4) align(it.data.size) else 0 }
    }

    private fun writeIfd(out: MutableList<Byte>, entries: List<Entry>, offset: Int) {
        check(out.size == offset) { "EXIF layout out of step" }
        var valueOffset = offset + 2 + 12 * entries.size + 4
        out += u16(entries.size).toList()
        for (e in entries.sortedBy { it.tag }) {
            out += u16(e.tag).toList()
            out += u16(e.type).toList()
            out += u32(e.count).toList()
            if (e.data.size <= 4) {
                out += (e.data + ByteArray(4 - e.data.size)).toList()
            } else {
                out += u32(valueOffset).toList()
                valueOffset += align(e.data.size)
            }
        }
        out += u32(0).toList() // no next IFD: never an IFD1, never a thumbnail
        for (e in entries.sortedBy { it.tag }) {
            if (e.data.size > 4) out += (e.data + ByteArray(align(e.data.size) - e.data.size)).toList()
        }
    }

    private fun ascii(tag: Int, s: String): Entry {
        val bytes = s.encodeToByteArray() + 0
        return Entry(tag, Exif.ASCII, bytes.size, bytes)
    }

    private fun rationals(tag: Int, values: List<Rational>): Entry = Entry(
        tag,
        Exif.RATIONAL,
        values.size,
        values.fold(ByteArray(0)) { acc, r ->
            acc + u32(r.numerator) +
                u32(r.denominator)
        },
    )

    private fun align(n: Int) = (n + 1) and 1.inv()

    fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    fun u32(v: Int) = u32(v.toLong())

    fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
