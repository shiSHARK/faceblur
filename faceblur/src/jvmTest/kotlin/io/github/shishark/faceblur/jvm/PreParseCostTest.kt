// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.model.OnnxDescription
import io.github.shishark.faceblur.internal.model.OnnxPreParse
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pre-parse on large descriptions: graph inputs matched against initializers by name, many distinct inputs. The
 * parse keeps at most two inputs, and the heap its result keeps stays below the file size (the earlier walker kept
 * about 8 × the file size). Times and allocated bytes are printed for the record, never asserted (no wall-clock
 * limit; allocation counts depend on the JIT).
 */
class PreParseCostTest {
    /** A minimal protobuf writer into one growing array (fast enough for millions of fields). */
    private class W {
        val out = ByteArrayOutputStream()

        fun varint(v: Long): W = apply {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun tag(field: Int, wire: Int): W = varint((field.toLong() shl 3) or wire.toLong())

        fun bytes(field: Int, b: ByteArray): W = apply {
            tag(field, 2).varint(b.size.toLong())
            out.write(b)
        }

        fun varintField(field: Int, v: Long): W = tag(field, 0).varint(v)

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** `ValueInfoProto` with a float tensor type of shape [1]. */
    private fun valueInfo(name: String): ByteArray {
        val dim = W().varintField(1, 1).toByteArray()
        val shape = W().bytes(1, dim).toByteArray()
        val tensor = W().varintField(1, 1).bytes(2, shape).toByteArray()
        val type = W().bytes(1, tensor).toByteArray()
        return W().bytes(1, name.encodeToByteArray()).bytes(2, type).toByteArray()
    }

    /** A model with [inputs] named inputs (plus the image input) and [initializers] of the first names. */
    private fun model(inputs: Int, initializers: Int): ByteArray {
        val g = W()
        for (i in 0 until initializers) {
            val t = W().varintField(1, 1).varintField(2, 1).bytes(8, "n$i".encodeToByteArray())
                .bytes(9, ByteArray(4)).toByteArray()
            g.bytes(5, t)
        }
        for (i in 0 until inputs) g.bytes(11, valueInfo("n$i"))
        g.bytes(11, valueInfo("image"))
        g.bytes(12, valueInfo("out"))
        val opset = W().bytes(1, ByteArray(0)).varintField(2, 13).toByteArray()
        return W().varintField(1, 8).bytes(8, opset).bytes(7, g.toByteArray()).toByteArray()
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun usedAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Parses [bytes] and returns the heap the result keeps (used heap after GC, with the result reachable, minus
     * before). Prints the time and the bytes allocated by the parse (most of them short-lived) for the record.
     */
    private fun retainedBy(label: String, bytes: ByteArray, check: (OnnxDescription) -> Unit): Long {
        check(OnnxPreParse.parse(bytes)) // warm-up
        val id = Thread.currentThread().id
        val before = usedAfterGc()
        val allocatedBefore = threads.getThreadAllocatedBytes(id)
        val t0 = System.nanoTime()
        val d = OnnxPreParse.parse(bytes)
        val ms = (System.nanoTime() - t0) / 1e6
        val allocated = threads.getThreadAllocatedBytes(id) - allocatedBefore
        val retained = usedAfterGc() - before
        check(d)
        println(
            "PREPARSE $label: file ${bytes.size} bytes, parse ${"%.1f".format(ms)} ms, allocated $allocated bytes " +
                "(${"%.2f".format(allocated.toDouble() / bytes.size)} x file), retained after GC $retained bytes",
        )
        return retained
    }

    @Test
    fun inputsMatchedAgainstInitializersKeepOnlyTheImageInput() {
        for (n in listOf(2_500, 5_000, 10_000, 20_000, 40_000)) {
            val bytes = model(n, n)
            val retained = retainedBy("$n inputs + $n initializers", bytes) {
                assertEquals(
                    listOf("image"),
                    it.inputs.map { t ->
                        t.name
                    },
                    "only the image input is not an initializer",
                )
            }
            assertTrue(retained < bytes.size, "retained $retained for ${bytes.size} bytes")
        }
    }

    @Test
    fun theLargestAcceptedInputCountKeepsTwoAndRetainsLessThanTheFileSize() {
        val max = OnnxPreParse.MAX_GRAPH_INPUTS
        // `model` adds the image input: max - 1 named inputs give exactly the limit.
        val bytes = model(max - 1, 0)
        val retained = retainedBy("$max inputs", bytes) {
            assertEquals(2, it.inputs.size, "the contract needs one input: the second decides")
        }
        assertTrue(retained < bytes.size, "retained $retained for ${bytes.size} bytes")
        // At the limit with an initializer for every other input, everything is matched except the image input.
        val both = model(max - 1, max - 1)
        val retainedBoth = retainedBy("$max inputs + ${max - 1} initializers", both) {
            assertEquals(listOf("image"), it.inputs.map { t -> t.name })
        }
        assertTrue(retainedBoth < both.size, "retained $retainedBoth for ${both.size} bytes")
        // One input more, or a million, is refused (initializers listed as inputs count too).
        for ((inputs, initializers) in listOf(max to 0, max to max, 1_000_000 to 0)) {
            val e = assertFailsWith<FaceMaskException.ModelRejected>("${inputs + 1} inputs") {
                OnnxPreParse.parse(model(inputs, initializers))
            }
            assertEquals(listOf("NOT_ONNX"), e.findings)
        }
        // A description limit still refuses (here: too many outputs), whatever the input count.
        val outputs = W()
        for (i in 0 until 1_025) outputs.bytes(12, valueInfo("o$i"))
        outputs.bytes(11, valueInfo("image"))
        val tooMany = W().varintField(1, 8).bytes(7, outputs.toByteArray()).toByteArray()
        val e = runCatching { OnnxPreParse.parse(tooMany) }.exceptionOrNull() as FaceMaskException.ModelRejected
        assertEquals(listOf("NOT_ONNX"), e.findings)
    }

    /**
     * The name table's comparison counts stay within n·⌈log₂ n⌉ for the sort (with its duplicate removal) and
     * ⌈log₂(n + 1)⌉ per lookup, for names in sorted, reversed, random and all-equal-prefix order.
     */
    @Test
    fun theNameTableComparisonsStayWithinTheirBounds() {
        for (n in listOf(1, 2, 3, 5, 64, 1_000, 4_097, OnnxPreParse.MAX_GRAPH_INPUTS)) {
            val orders = mapOf(
                "sorted" to (0 until n).toList(),
                "reversed" to (n - 1 downTo 0).toList(),
                "random" to (0 until n).shuffled(kotlin.random.Random(n)),
            )
            for ((label, order) in orders) {
                // Names "p" + 6-digit numbers (a shared prefix, so comparisons look past the first byte).
                val buf = ByteArrayOutputStream()
                val positions = IntArray(n)
                for ((k, v) in order.withIndex()) {
                    positions[k] = buf.size()
                    val name = "p" + v.toString().padStart(6, '0')
                    buf.write(name.length)
                    buf.write(name.encodeToByteArray())
                }
                val bytes = buf.toByteArray()
                val table = OnnxPreParse.NameTable(bytes)
                for (p in positions) table.add(p)
                for (p in positions) table.mark(p)
                for (p in positions) assertTrue(table.isMarked(p))
                val log = if (n <= 1) 0 else 32 - Integer.numberOfLeadingZeros(n - 1)
                assertTrue(
                    table.sortComparisons <= n.toLong() * log,
                    "$label n=$n: ${table.sortComparisons} sort comparisons > n·⌈log₂ n⌉ = ${n.toLong() * log}",
                )
                val lookupBound = 32 - Integer.numberOfLeadingZeros(n)
                assertTrue(
                    table.maxLookupComparisons <= lookupBound,
                    "$label n=$n: ${table.maxLookupComparisons} comparisons per lookup > ⌈log₂(n + 1)⌉ = $lookupBound",
                )
            }
        }
    }
}
