// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** Raised when the file cannot be understood; becomes an 'unparseable' failure. */
internal class Fatal(val code: FindingCode, message: String, val offset: Int?) : Exception(message)

/**
 * Validates the content of the coding segments (SOF, SOS, DRI, DQT, DHT): lengths must match their content
 * exactly, and quantisation and Huffman tables must be few and used, so these segments cannot carry hidden data.
 */
internal class CodingValidator(private val data: ByteArray, private val fail: (FindingCode, String, Int?) -> Unit) {
    private var scans = 0
    private var frameSeen = false
    private val components = mutableSetOf<Int>()
    private val referencedQuant = mutableSetOf<Int>()
    private val referencedHuffman = mutableSetOf<Int>()
    private val definedQuant = mutableListOf<Int>()
    private val definedHuffman = mutableListOf<Int>()
    private var quantDefinitions = 0
    private var huffmanDefinitions = 0
    private val definitionScanIndex = mutableListOf<Int>()

    private fun u8(pos: Int): Int = data[pos].toInt() and 0xFF

    fun sof(markerPos: Int, payload: Int, size: Int) {
        if (size < 6) throw Fatal(FindingCode.UNPARSEABLE, "start-of-frame segment too short", markerPos)
        val count = u8(payload + 5)
        if (count !in 1..MAX_COMPONENTS || size != 6 + 3 * count) {
            throw Fatal(
                FindingCode.UNPARSEABLE,
                "start-of-frame length does not match its $count components",
                markerPos,
            )
        }
        if (frameSeen) return // a second frame is reported as MULTIPLE_FRAMES; only the first is tracked
        frameSeen = true
        for (i in 0 until count) {
            components.add(u8(payload + 6 + 3 * i))
            referencedQuant.add(u8(payload + 8 + 3 * i))
        }
    }

    fun sos(markerPos: Int, payload: Int, size: Int) {
        if (size < 1) throw Fatal(FindingCode.UNPARSEABLE, "start-of-scan segment empty", markerPos)
        val count = u8(payload)
        if (count !in 1..MAX_COMPONENTS || size != 4 + 2 * count) {
            throw Fatal(FindingCode.UNPARSEABLE, "start-of-scan length does not match its $count components", markerPos)
        }
        scans++
        for (i in 0 until count) {
            val id = u8(payload + 1 + 2 * i)
            val tables = u8(payload + 2 + 2 * i)
            if (frameSeen && id !in components) {
                throw Fatal(FindingCode.UNPARSEABLE, "scan names a component the frame does not have", markerPos)
            }
            referencedHuffman.add(tables shr 4)
            referencedHuffman.add(16 + (tables and 0x0F))
        }
    }

    fun dri(markerPos: Int, size: Int) {
        if (size != 2) throw Fatal(FindingCode.UNPARSEABLE, "restart-interval length is not 4", markerPos)
    }

    fun dqt(markerPos: Int, payload: Int, size: Int) {
        var offset = 0
        while (offset < size) {
            val info = u8(payload + offset)
            val precision = info shr 4
            val id = info and 0x0F
            if (precision > 1 ||
                id > 3
            ) {
                throw Fatal(FindingCode.UNPARSEABLE, "quantisation table header invalid", markerPos)
            }
            offset += 1 + 64 * (precision + 1)
            if (offset >
                size
            ) {
                throw Fatal(FindingCode.UNPARSEABLE, "quantisation table runs past its segment", markerPos)
            }
            define(definedQuant, id, scans, ++quantDefinitions, MAX_QUANT_DEFINITIONS, "quantisation table", markerPos)
        }
    }

    fun dht(markerPos: Int, payload: Int, size: Int) {
        var offset = 0
        while (offset < size) {
            if (offset + 17 >
                size
            ) {
                throw Fatal(FindingCode.UNPARSEABLE, "Huffman table header runs past its segment", markerPos)
            }
            val info = u8(payload + offset)
            val tableClass = info shr 4
            val id = info and 0x0F
            if (tableClass > 1 ||
                id > 3
            ) {
                throw Fatal(FindingCode.UNPARSEABLE, "Huffman table header invalid", markerPos)
            }
            var symbols = 0
            for (i in 1..16) symbols += u8(payload + offset + i)
            if (symbols >
                MAX_SYMBOLS
            ) {
                throw Fatal(FindingCode.UNPARSEABLE, "Huffman table with $symbols symbols", markerPos)
            }
            offset += 17 + symbols
            if (offset > size) throw Fatal(FindingCode.UNPARSEABLE, "Huffman table runs past its segment", markerPos)
            val key = tableClass * 16 + id
            define(
                definedHuffman,
                key,
                scans,
                ++huffmanDefinitions,
                MAX_HUFFMAN_DEFINITIONS,
                "Huffman table",
                markerPos,
            )
        }
    }

    private fun <T> define(
        defined: MutableList<T>,
        key: T,
        scansSoFar: Int,
        total: Int,
        limit: Int,
        what: String,
        markerPos: Int,
    ) {
        // Redefinition before the first scan is redundant; between progressive scans it is normal but capped.
        if (scansSoFar == 0 && key in defined) {
            fail(FindingCode.CODING_TABLE_REDUNDANT, "$what defined twice before the first scan", markerPos)
        }
        if (total > limit) throw Fatal(FindingCode.UNPARSEABLE, "more than $limit ${what}s defined", markerPos)
        defined.add(key)
        definitionScanIndex.add(scansSoFar)
    }

    /** At the end of the file: tables nobody uses are redundant data. */
    fun finish() {
        if (scans > 0 && definitionScanIndex.any { it == scans }) {
            fail(
                FindingCode.CODING_TABLE_REDUNDANT,
                "a table is defined after the last scan, where nothing can use it",
                null,
            )
        }
        if (frameSeen) {
            val unusedQuant = definedQuant.toSet().filter { it !in referencedQuant }
            if (unusedQuant.isNotEmpty()) {
                fail(
                    FindingCode.CODING_TABLE_REDUNDANT,
                    "${unusedQuant.size} quantisation table(s) no component uses",
                    null,
                )
            }
        }
        val unusedHuffman = definedHuffman.toSet().filter { it !in referencedHuffman }
        if (scans > 0 && unusedHuffman.isNotEmpty()) {
            fail(FindingCode.CODING_TABLE_REDUNDANT, "${unusedHuffman.size} Huffman table(s) no scan uses", null)
        }
    }

    private companion object {
        const val MAX_COMPONENTS = 4
        const val MAX_SYMBOLS = 256
        const val MAX_QUANT_DEFINITIONS = 32
        const val MAX_HUFFMAN_DEFINITIONS = 64
    }
}
