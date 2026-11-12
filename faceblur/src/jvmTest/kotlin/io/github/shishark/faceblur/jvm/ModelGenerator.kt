// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.testmodel.Proto
import kotlin.random.Random

/**
 * Random ONNX models for the pre-parse differential (test only): every carrier message of the walker's field table,
 * nested subgraphs, functions and training info, tensors with every encoding of the external-data location, unknown
 * fields, main-graph inputs and outputs with merged and non-tensor types, and, rarely, the constructions on which the
 * walker is deliberately stricter than protobuf. For each model it records what a protobuf parser of onnx.proto sees
 * ([Model.visibleExternal]) and why the walker may refuse a model that protobuf accepts ([Model.strict]).
 */
internal class ModelGenerator(seed: Long) {
    private val r = Random(seed)

    /** A generated model, what protobuf sees in it and the reasons for a walker refusal that protobuf would accept. */
    class Model(val bytes: ByteArray, val visibleExternal: Boolean, val strict: Set<String>, val deepest: Int)

    private var visible = false
    private val strict = LinkedHashSet<String>()
    private var deepest = 0

    /** Per model: about one in four may hold strictness constructions, two in five external tensors. */
    private var allowStrict = false
    private var allowExternal = false

    fun next(): Model {
        visible = false
        strict.clear()
        deepest = 0
        allowStrict = chance(4)
        allowExternal = chance(5, 2)
        val bytes = model()
        return Model(bytes, visible, strict.toSet(), deepest)
    }

    // ---- Messages (levels as the walker counts them: the model is level 1) ------------------------------------------

    private fun enter(level: Int) {
        if (level > deepest) deepest = level
        if (level > OnnxPreParse.MAX_DEPTH) strict += DEPTH
    }

    private fun model(): ByteArray {
        enter(1)
        val m = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        if (chance(10, 9)) parts += { it.varint(1, irVersion()) }
        repeat(r.nextInt(4)) { parts += { it.message(8, opset()) } }
        when {
            // A chain of subgraphs around the depth limit as the main graph.
            chance(40) -> parts += { it.message(7, deepChain()) }

            chance(20, 19) -> parts += { it.message(7, graph(2, main = true, budget = 3)) }
        }
        if (allowStrict && chance(4)) parts += { it.message(7, graph(2, main = false, budget = 1)) }
        if (chance(4)) repeat(1 + r.nextInt(2)) { parts += { it.message(25, function(2)) } }
        if (chance(8)) parts += { it.message(20, training(2)) }
        if (chance(3)) parts += { it.string(2, "faceblur-test") }
        unknownFields(parts, walked = true)
        wrongWire(parts, listOf(1, 7, 8, 20, 25))
        parts.shuffle(r)
        for (p in parts) p(m)
        if (countField(m.bytes(), 7, wire = 2) > 1) strict += DUPLICATE
        return m.bytes()
    }

    private fun graph(level: Int, main: Boolean, budget: Int): Proto {
        enter(level)
        val g = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        repeat(r.nextInt(4)) { parts += { it.message(1, node(level + 1, budget)) } }
        if (chance(2)) parts += { it.string(2, name()) }
        repeat(r.nextInt(4)) { parts += { it.message(5, tensor(level + 1)) } }
        if (chance(3)) repeat(1 + r.nextInt(2)) { parts += { it.message(15, sparse(level + 1)) } }
        val inputs = if (main) r.nextInt(5) else r.nextInt(2)
        repeat(inputs) { parts += { it.message(11, valueInfo()) } }
        repeat(if (main) r.nextInt(5) else r.nextInt(2)) { parts += { it.message(12, valueInfo()) } }
        if (chance(4)) parts += { it.message(13, valueInfo()) }
        if (chance(6)) parts += { it.string(10, "doc") }
        unknownFields(parts, walked = true)
        wrongWire(parts, listOf(1, 2, 5, 11, 12, 15))
        parts.shuffle(r)
        for (p in parts) p(g)
        return g
    }

    private fun node(level: Int, budget: Int): Proto {
        enter(level)
        val n = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        repeat(r.nextInt(3)) { parts += { it.string(1, name()) } }
        repeat(r.nextInt(3)) { parts += { it.string(2, name()) } }
        parts += { it.string(4, listOf("Constant", "If", "Loop", "X").random(r)) }
        if (chance(4)) parts += { it.string(7, listOf("", "ai.onnx.ml", "com.example").random(r)) }
        repeat(r.nextInt(4)) { parts += { it.message(5, attribute(level + 1, budget)) } }
        // A whole external tensor hidden in a string field: bytes to both parsers.
        if (chance(15)) parts += { it.bytes(6, externalTensorBytes()) }
        unknownFields(parts, walked = true)
        wrongWire(parts, listOf(1, 4, 5))
        parts.shuffle(r)
        for (p in parts) p(n)
        return n
    }

    private fun attribute(level: Int, budget: Int): Proto {
        enter(level)
        val a = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        parts += { it.string(1, name()) }
        if (chance(10, 9)) parts += { it.varint(20, r.nextInt(16).toLong()) } // declared type, often not matching
        if (chance(3)) parts += { it.float(2, r.nextFloat()) }
        if (chance(3)) parts += { it.varint(3, r.nextLong()) }
        if (chance(4)) parts += { it.bytes(4, ByteArray(r.nextInt(4))) }
        if (chance(2)) parts += { it.message(5, tensor(level + 1)) }
        if (budget > 0 && chance(3)) parts += { it.message(6, graph(level + 1, main = false, budget = budget - 1)) }
        if (chance(4)) parts += { packedOrNot(it, 7, 5) }
        if (chance(4)) parts += { packedOrNot(it, 8, 0) }
        if (chance(4)) repeat(1 + r.nextInt(2)) { parts += { it.message(10, tensor(level + 1)) } }
        if (budget > 0 && chance(6)) parts += { it.message(11, graph(level + 1, main = false, budget = budget - 1)) }
        if (chance(4)) parts += { it.message(22, sparse(level + 1)) }
        if (chance(5)) repeat(1 + r.nextInt(2)) { parts += { it.message(23, sparse(level + 1)) } }
        if (chance(8)) parts += { it.message(14, typeProto()) }
        if (allowStrict && chance(10)) {
            when (r.nextInt(3)) {
                0 -> parts += { it.message(5, tensor(level + 1)) }
                1 -> parts += { it.message(22, sparse(level + 1)) }
                else -> parts += { it.message(6, Proto()) }
            }
        }
        unknownFields(parts, walked = true)
        wrongWire(parts, listOf(1, 3, 5, 6, 20))
        parts.shuffle(r)
        for (p in parts) p(a)
        if (listOf(5, 6, 22).any { countField(a.bytes(), it, wire = 2) > 1 }) strict += DUPLICATE
        return a
    }

    private fun sparse(level: Int): Proto {
        enter(level)
        val s = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        if (chance(10, 9)) parts += { it.message(1, tensor(level + 1)) }
        if (chance(10, 8)) parts += { it.message(2, tensor(level + 1)) }
        if (chance(2)) parts += { packedOrNot(it, 3, 0) }
        if (allowStrict && chance(10)) parts += { it.message(1 + r.nextInt(2), tensor(level + 1)) }
        unknownFields(parts, walked = true)
        parts.shuffle(r)
        for (p in parts) p(s)
        if (countField(s.bytes(), 1, wire = 2) > 1 || countField(s.bytes(), 2, wire = 2) > 1) strict += DUPLICATE
        return s
    }

    private fun function(level: Int): Proto {
        enter(level)
        val f = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        parts += { it.string(1, "f") }
        if (chance(2)) parts += { it.string(10, "local") }
        repeat(r.nextInt(3)) { parts += { it.message(7, node(level + 1, budget = 1)) } }
        repeat(r.nextInt(2)) { parts += { it.message(11, attribute(level + 1, budget = 1)) } }
        if (chance(3)) parts += { it.message(9, opset()) }
        if (chance(4)) parts += { it.message(12, valueInfo()) }
        unknownFields(parts, walked = true)
        parts.shuffle(r)
        for (p in parts) p(f)
        return f
    }

    private fun training(level: Int): Proto {
        enter(level)
        val t = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        if (chance(2)) parts += { it.message(1, graph(level + 1, main = false, budget = 1)) }
        if (chance(2)) parts += { it.message(2, graph(level + 1, main = false, budget = 1)) }
        if (chance(3)) parts += { it.message(3, Proto().string(1, "k").string(2, "v")) }
        if (allowStrict &&
            chance(5)
        ) {
            parts += { it.message(1 + r.nextInt(2), graph(level + 1, main = false, budget = 0)) }
        }
        parts.shuffle(r)
        for (p in parts) p(t)
        if (countField(t.bytes(), 1, wire = 2) > 1 || countField(t.bytes(), 2, wire = 2) > 1) strict += DUPLICATE
        return t
    }

    /** A chain of If subgraphs down to an attribute around the walker's depth limit (levels 94..106). */
    private fun deepChain(): Proto {
        val attributeLevel = 4 + 3 * (30 + r.nextInt(5)) // 94, 97, 100, 103 or 106
        val withTensor = chance(2)
        var attribute = Proto().string(1, "a")
        if (withTensor) attribute.message(5, tensor(attributeLevel + 1))
        var level = attributeLevel
        enter(level)
        while (level > 4) {
            val graph = Proto().message(1, Proto().string(4, "If").message(5, attribute))
            attribute = Proto().string(1, "g").message(6, graph)
            level -= 3
        }
        return Proto().message(1, Proto().string(4, "If").message(5, attribute))
    }

    // ---- Tensors -------------------------------------------------------------------------------------------------

    private fun tensor(level: Int): Proto {
        enter(level)
        val t = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        if (chance(3, 2)) parts += { packedOrNot(it, 1, 0) }
        if (chance(3, 2)) parts += { it.varint(2, r.nextInt(17).toLong()) }
        if (chance(3, 2)) parts += { it.string(8, name()) }
        if (chance(2)) parts += { it.bytes(9, ByteArray(r.nextInt(16)) { b -> b.toByte() }) }
        if (chance(5)) parts += { packedOrNot(it, 4, 5) }
        if (chance(6)) parts += { packedOrNot(it, 7, 0) }
        if (chance(8)) parts += { packedOrNot(it, 10, 1) }
        if (chance(8)) parts += { it.message(3, Proto().varint(1, 0).varint(2, 1)) }
        if (chance(8)) parts += { it.string(12, "doc") }
        if (chance(10)) parts += { it.message(16, Proto().string(1, "k").string(2, "v")) }
        unknownFields(parts, walked = true)
        // The location: a list of data_location values (protobuf: int32 truncation, closed enum, last valid wins)
        // and external_data entries.
        var entries = 0
        val locations = ArrayList<Long>()
        when {
            allowExternal && allowStrict && chance(3) -> repeat(1 + r.nextInt(2)) {
                locations += listOf(0L, 1L, 1L, 2L, 7L, -1L, (1L shl 32) + 1, 1L shl 32, Long.MIN_VALUE + 1).random(r)
            }

            // Values on which protobuf and the walker agree: 0, or a value that truncates to 1 (EXTERNAL).
            allowExternal && chance(3) -> locations += listOf(0L, 1L, 1L, (1L shl 32) + 1, Long.MIN_VALUE + 1).random(r)

            chance(8) -> locations += 0L
        }
        if (allowExternal && chance(5)) entries = 1 + r.nextInt(2)
        for (v in locations) parts += { it.varint(14, v) }
        repeat(entries) { parts += { it.message(13, Proto().string(1, "location").string(2, "x")) } }
        wrongWire(parts, listOf(2, 8, 13, 14))
        parts.shuffle(r)
        for (p in parts) p(t)
        // What protobuf keeps: the last value that truncates to a valid enum value (0 or 1).
        val bytes = t.bytes()
        val kept = lastDataLocation(bytes)
        val seenEntries = countField(bytes, 13, wire = 2)
        val protobufSees = kept == 1 || seenEntries > 0
        val walkerSees = anyDataLocationNonZero(bytes) || seenEntries > 0
        if (protobufSees) visible = true
        if (walkerSees && !protobufSees) strict += LOCATION_NOT_VISIBLE
        return t
    }

    private fun externalTensorBytes(): ByteArray =
        Proto().string(8, "h").message(13, Proto().string(1, "location").string(2, "x")).varint(14, 1).bytes()

    // ---- Inputs, outputs and types (read by the walker's description) ----------------------------------------------

    private fun valueInfo(): Proto {
        val v = Proto()
        val parts = ArrayList<(Proto) -> Unit>()
        if (chance(10, 9)) parts += { it.string(1, name()) }
        if (chance(10, 9)) repeat(if (chance(5)) 2 else 1) { parts += { it.message(2, typeProto()) } }
        if (chance(6)) parts += { it.string(3, "doc") }
        unknownFields(parts, walked = false)
        if (chance(80)) parts += { it.varint(2, 1) } // a known field with another wire type: unknown to both
        parts.shuffle(r)
        for (p in parts) p(v)
        return v
    }

    private fun typeProto(): Proto {
        val t = Proto()
        repeat(if (chance(4)) 2 else 1) {
            when (r.nextInt(8)) {
                in 0..3 -> t.message(1, tensorType())

                4 -> t.message(4, Proto().also { s -> if (chance(2)) s.message(1, Proto().message(1, tensorType())) })

                5 -> t.message(listOf(5, 7, 8, 9).random(r), Proto())

                6 -> t.string(6, "IMAGE")

                // A oneof member with another wire type: protobuf keeps it as an unknown field.
                else -> t.varint(listOf(1, 4, 5, 7, 8, 9).random(r), 1)
            }
        }
        return t
    }

    private fun tensorType(): Proto {
        val t = Proto()
        if (chance(10, 9)) t.varint(1, listOf(1L, 7L, 11L, 0L, (1L shl 32) + 1, -1L).random(r))
        if (chance(10, 8)) {
            val shape = Proto()
            repeat(r.nextInt(5)) {
                val d = Proto()
                when (r.nextInt(6)) {
                    0, 1, 2 -> d.varint(1, listOf(1L, 3L, 640L, 0L, -1L, Long.MAX_VALUE).random(r))
                    3 -> d.string(2, "h")
                    4 -> d.varint(1, 640).string(2, "h")
                    else -> d.string(2, "w").varint(1, 3)
                }
                if (chance(10)) d.string(3, "DATA_BATCH")
                shape.message(1, d)
            }
            t.message(2, shape)
        }
        return t
    }

    private fun opset(): Proto {
        val o = Proto()
        if (chance(10, 9)) o.string(1, listOf("", "", "ai.onnx.ml", "com.microsoft", "x").random(r))
        if (chance(10, 9)) o.varint(2, listOf(13L, 11L, 1L, -5L).random(r))
        return o
    }

    private fun irVersion(): Long = listOf(8L, 6L, 3L, 0L, -1L, Long.MAX_VALUE).random(r)

    // ---- Helpers --------------------------------------------------------------------------------------------------

    /** Names collide on purpose (initializers listed as inputs); rarely not valid UTF-8. */
    private fun name(): String = listOf("input", "w", "x", "", "a", "ab", "out", "w0").random(r)

    private fun chance(n: Int, k: Int = 1): Boolean = r.nextInt(n) < k

    private fun packedOrNot(p: Proto, field: Int, wire: Int) {
        val count = r.nextInt(4)
        if (chance(2)) {
            val packed = Proto()
            repeat(count) {
                when (wire) {
                    0 -> packed.varintRaw(r.nextInt(1000).toLong())
                    5 -> packed.raw(ByteArray(4))
                    else -> packed.raw(ByteArray(8))
                }
            }
            p.bytes(field, packed.bytes())
        } else {
            repeat(count) {
                when (wire) {
                    0 -> p.varint(field, r.nextInt(1000).toLong())
                    5 -> p.fixed32(field, r.nextInt())
                    else -> p.tag(field, 1).raw(ByteArray(8))
                }
            }
        }
    }

    /**
     * Unknown fields (numbers unknown to both schema versions) with every valid wire type; rarely a group, only in a
     * message the walker walks ([walked]).
     */
    private fun unknownFields(parts: MutableList<(Proto) -> Unit>, walked: Boolean) {
        if (!chance(5)) return
        val field = listOf(100, 101, 999, 4_096, (1 shl 29) - 1).random(r)
        when (r.nextInt(6)) {
            0 -> parts += { it.varint(field, r.nextLong()) }

            1 -> parts += { it.fixed32(field, r.nextInt()) }

            2 -> parts += { it.tag(field, 1).raw(ByteArray(8)) }

            // A whole external tensor as the payload of an unknown field: protobuf never parses it.
            3 -> parts += { it.bytes(field, externalTensorBytes()) }

            4 -> parts += { it.bytes(field, ByteArray(r.nextInt(8))) }

            else -> if (walked && allowStrict && chance(2)) {
                // A well-formed group: protobuf keeps it as an unknown field, the walker refuses group wire types.
                parts += { it.tag(field, 3).varint(1, 5).tag(field, 4) }
                strict += GROUP
            }
        }
    }

    /** Rarely, one known field of [fields] written with a wire type protobuf does not expect for it. */
    private fun wrongWire(parts: MutableList<(Proto) -> Unit>, fields: List<Int>) {
        if (!allowStrict || !chance(15)) return
        val field = fields.random(r)
        parts += { it.fixed32(field, 0) }
        strict += WRONG_WIRE
    }

    companion object {
        const val DEPTH = "depth"
        const val DUPLICATE = "duplicate singular message"
        const val GROUP = "group wire type"
        const val WRONG_WIRE = "known field with another wire type"
        const val LOCATION_NOT_VISIBLE = "data_location value that protobuf drops"

        /** Number of top-level occurrences of [field] (with [wire], or any wire type) in a well-formed message. */
        fun countField(bytes: ByteArray, field: Int, wire: Int? = null): Int {
            var n = 0
            forEachField(bytes) { f, w, _ -> if (f == field && (wire == null || w == wire)) n++ }
            return n
        }

        /** The data_location protobuf keeps: the last varint occurrence whose int32 truncation is 0 or 1. */
        fun lastDataLocation(tensor: ByteArray): Int {
            var kept = 0
            forEachField(tensor) { f, w, v -> if (f == 14 && w == 0 && v.toInt() in 0..1) kept = v.toInt() }
            return kept
        }

        fun anyDataLocationNonZero(tensor: ByteArray): Boolean {
            var any = false
            forEachField(tensor) { f, w, v -> if (f == 14 && w == 0 && v != 0L) any = true }
            return any
        }

        /** Top-level fields of a well-formed message (as this generator writes it): number, wire type, varint value. */
        private fun forEachField(bytes: ByteArray, action: (Int, Int, Long) -> Unit) {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            val groups = ArrayList<Int>()
            while (pos < bytes.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                var value = 0L
                when (wire) {
                    0 -> value = varint()

                    1 -> pos += 8

                    2 -> {
                        val length = varint().toInt() // read first: `pos += varint()` would add to the old position
                        pos += length
                    }

                    3 -> groups += field

                    4 -> groups.removeAt(groups.size - 1)

                    5 -> pos += 4
                }
                if (groups.isEmpty() && wire != 4) action(field, wire, value)
            }
        }
    }
}
