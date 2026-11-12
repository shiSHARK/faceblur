// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.io.ByteArrayOutputStream

/** One IFD entry: [value] holds the raw value bytes already in the TIFF's byte order. */
public class Entry(public val tag: Int, public val type: Int, public val count: Long, public val value: ByteArray)

/**
 * Test-only TIFF/EXIF writer used to plant defects. It is written for the self-test and shares no code with
 * the checker or the library.
 */
public class TiffBuilder(private val littleEndian: Boolean = true) {
    public val ifd0: MutableList<Entry> = mutableListOf()
    public var exif: MutableList<Entry>? = null
    public var gps: MutableList<Entry>? = null
    public var interop: MutableList<Entry>? = null
    public var ifd1: MutableList<Entry>? = null
    public var thumbnail: ByteArray? = null

    /** Extra zero bytes appended to the structure (a size defect without any hidden data). */
    public var zeroPadding: Int = 0

    /** Bytes appended to the structure that no IFD refers to. */
    public var unreferenced: ByteArray = ByteArray(0)

    private fun num(value: Long, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes) {
            val shift = 8 * if (littleEndian) i else bytes - 1 - i
            out[i] = ((value shr shift) and 0xFF).toByte()
        }
        return out
    }

    public fun short(tag: Int, vararg v: Int): Entry =
        Entry(tag, 3, v.size.toLong(), v.fold(ByteArray(0)) { acc, x -> acc + num(x.toLong(), 2) })

    public fun long(tag: Int, vararg v: Long): Entry =
        Entry(tag, 4, v.size.toLong(), v.fold(ByteArray(0)) { acc, x -> acc + num(x, 4) })

    public fun byte(tag: Int, vararg v: Int): Entry = Entry(
        tag,
        1,
        v.size.toLong(),
        ByteArray(v.size) {
            v[it].toByte()
        },
    )

    public fun ascii(tag: Int, text: String): Entry {
        val raw = text.toByteArray(Charsets.ISO_8859_1) + 0
        return Entry(tag, 2, raw.size.toLong(), raw)
    }

    public fun undefined(tag: Int, raw: ByteArray): Entry = Entry(tag, 7, raw.size.toLong(), raw)

    /** Rationals as numerator/denominator pairs. */
    public fun rational(tag: Int, vararg pairs: Pair<Long, Long>): Entry = Entry(
        tag,
        5,
        pairs.size.toLong(),
        pairs.fold(ByteArray(0)) { acc, p ->
            acc + num(p.first, 4) + num(p.second, 4)
        },
    )

    public fun orientation(v: Int = 1): Entry = short(0x0112, v)

    public fun build(): ByteArray {
        val tables = mutableListOf<MutableList<Entry>>()
        val exifList = exif
        val gpsList = gps
        val interopList = interop
        val ifd1List = ifd1 ?: if (thumbnail != null) mutableListOf() else null
        val ifd0Full = ifd0.toMutableList()
        // Pointers are added with placeholder values, patched below.
        if (exifList != null) ifd0Full.add(long(0x8769, 0))
        if (gpsList != null) ifd0Full.add(long(0x8825, 0))
        val sorted0 = ifd0Full.sortedBy { it.tag }
        tables.add(sorted0.toMutableList())
        if (exifList != null) {
            val full = exifList.toMutableList()
            if (interopList != null) full.add(long(0xA005, 0))
            tables.add(full.sortedBy { it.tag }.toMutableList())
        }
        if (gpsList != null) tables.add(gpsList.sortedBy { it.tag }.toMutableList())
        if (interopList != null) tables.add(interopList.sortedBy { it.tag }.toMutableList())
        if (ifd1List != null) {
            val full = ifd1List.toMutableList()
            if (thumbnail != null) {
                full.add(short(0x0103, 6))
                full.add(long(0x0201, 0))
                full.add(long(0x0202, thumbnail!!.size.toLong()))
            }
            tables.add(full.sortedBy { it.tag }.toMutableList())
        }
        val tableOffsets = mutableListOf<Int>()
        var cursor = 8
        for (t in tables) {
            tableOffsets.add(cursor)
            cursor += 2 + 12 * t.size + 4
        }
        val exifOffset = if (exifList != null) tableOffsets[1] else 0
        val gpsOffset = if (gpsList != null) tableOffsets[if (exifList != null) 2 else 1] else 0
        val interopOffset = if (interopList !=
            null
        ) {
            tableOffsets[1 + (if (exifList != null) 1 else 0) + (if (gpsList != null) 1 else 0)]
        } else {
            0
        }
        val ifd1Offset = if (ifd1List != null) tableOffsets.last() else 0
        val data = ByteArrayOutputStream()
        val dataStart = cursor
        fun allocate(raw: ByteArray): Int {
            val at = dataStart + data.size()
            data.write(raw)
            if (raw.size % 2 == 1) data.write(0)
            return at
        }
        val out = ByteArrayOutputStream()
        out.write(if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D))
        out.write(num(42, 2))
        out.write(num(8, 4))
        for ((index, table) in tables.withIndex()) {
            out.write(num(table.size.toLong(), 2))
            for (entry in table) {
                out.write(num(entry.tag.toLong(), 2))
                out.write(num(entry.type.toLong(), 2))
                out.write(num(entry.count, 4))
                var value = entry.value
                if (index == 0 && entry.tag == 0x8769 && exifList != null) value = num(exifOffset.toLong(), 4)
                if (index == 0 && entry.tag == 0x8825 && gpsList != null) value = num(gpsOffset.toLong(), 4)
                if (index == 1 && entry.tag == 0xA005 && interopList != null) value = num(interopOffset.toLong(), 4)
                val isThumbOffset =
                    ifd1List != null && index == tables.size - 1 && entry.tag == 0x0201 && thumbnail != null
                if (isThumbOffset) value = num(allocate(thumbnail!!).toLong(), 4)
                if (value.size <= 4) {
                    out.write(value)
                    out.write(ByteArray(4 - value.size))
                } else {
                    out.write(num(allocate(value).toLong(), 4))
                }
            }
            out.write(num(if (index == 0) ifd1Offset.toLong() else 0, 4))
        }
        out.write(data.toByteArray())
        out.write(unreferenced)
        out.write(ByteArray(zeroPadding))
        return out.toByteArray()
    }
}
