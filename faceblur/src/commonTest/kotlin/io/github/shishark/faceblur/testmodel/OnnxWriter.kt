// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.testmodel

/**
 * The one test-source ONNX writer: a minimal protobuf encoder plus helpers for the ONNX messages the tests need.
 * It builds the hostile models, the structural test models and (with [DerivedModels]) the generic-format variants of
 * the reference model, all at test time. Nothing it builds is ever committed, cached or uploaded.
 */
internal class Proto {
    private val out = ArrayList<Byte>()

    fun bytes(): ByteArray = out.toByteArray()

    /** Appends raw bytes as they are (for hand-made encodings). */
    fun raw(b: ByteArray): Proto = apply { for (x in b) out += x }

    fun tag(field: Int, wire: Int): Proto = varintRaw((field.toLong() shl 3) or wire.toLong())

    fun varintRaw(value: Long): Proto = apply {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out += v.toByte()
                return@apply
            }
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
    }

    fun varint(field: Int, value: Long): Proto = tag(field, 0).varintRaw(value)

    fun fixed32(field: Int, bits: Int): Proto = tag(field, 5).apply {
        for (i in 0 until 4) out += (bits ushr (8 * i)).toByte()
    }

    fun float(field: Int, value: Float): Proto = fixed32(field, value.toBits())

    fun bytes(field: Int, value: ByteArray): Proto = tag(field, 2).varintRaw(value.size.toLong()).raw(value)

    fun string(field: Int, value: String): Proto = bytes(field, value.encodeToByteArray())

    fun message(field: Int, value: Proto): Proto = bytes(field, value.bytes())

    /** A packed repeated varint field. */
    fun packedVarints(field: Int, values: List<Long>): Proto {
        val p = Proto()
        for (v in values) p.varintRaw(v)
        return bytes(field, p.bytes())
    }
}

/** ONNX message helpers (field numbers of onnx.proto). */
internal object Onnx {
    const val FLOAT = 1
    const val INT64 = 7
    const val DOUBLE = 11

    /** A `ValueInfoProto` with a tensor type; dims are Long (static) or String (symbolic). */
    fun valueInfo(name: String, elemType: Int, dims: List<Any>?): Proto {
        val tensor = Proto().varint(1, elemType.toLong())
        if (dims != null) {
            val shape = Proto()
            for (d in dims) {
                shape.message(
                    1,
                    when (d) {
                        is String -> Proto().string(2, d)
                        is Number -> Proto().varint(1, d.toLong())
                        else -> error("dim must be a number or a string")
                    },
                )
            }
            tensor.message(2, shape)
        }
        return Proto().string(1, name).message(2, Proto().message(1, tensor))
    }

    /** A float `TensorProto` with raw data. */
    fun floatTensor(name: String, dims: List<Long>, values: FloatArray): Proto {
        val raw = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val b = v.toBits()
            for (k in 0 until 4) raw[i * 4 + k] = (b ushr (8 * k)).toByte()
        }
        val t = Proto()
        for (d in dims) t.varint(1, d)
        return t.varint(2, FLOAT.toLong()).string(8, name).bytes(9, raw)
    }

    /** An int64 `TensorProto` with raw data. */
    fun int64Tensor(name: String, dims: List<Long>, values: LongArray): Proto {
        val raw = ByteArray(values.size * 8)
        for ((i, v) in values.withIndex()) for (k in 0 until 8) raw[i * 8 + k] = (v ushr (8 * k)).toByte()
        val t = Proto()
        for (d in dims) t.varint(1, d)
        return t.varint(2, INT64.toLong()).string(8, name).bytes(9, raw)
    }

    /** A tensor that references external data (location + data_location = EXTERNAL). */
    fun externalTensor(name: String, location: String, dataLocation: Long? = 1L): Proto {
        val t = Proto().varint(1, 4).varint(2, FLOAT.toLong()).string(8, name)
        t.message(13, Proto().string(1, "location").string(2, location))
        if (dataLocation != null) t.varint(14, dataLocation)
        return t
    }

    fun attrInt(name: String, value: Long): Proto = Proto().string(1, name).varint(3, value).varint(20, 2)

    fun attrTensor(name: String, tensor: Proto): Proto = Proto().string(1, name).message(5, tensor).varint(20, 4)

    fun node(op: String, inputs: List<String>, outputs: List<String>, attributes: List<Proto> = emptyList()): Proto {
        val n = Proto()
        for (i in inputs) n.string(1, i)
        for (o in outputs) n.string(2, o)
        n.string(4, op)
        for (a in attributes) n.message(5, a)
        return n
    }

    /** A `GraphProto` from parts (nodes, initializers, inputs, outputs). */
    fun graph(
        nodes: List<Proto> = emptyList(),
        initializers: List<Proto> = emptyList(),
        inputs: List<Proto>,
        outputs: List<Proto>,
        name: String = "g",
    ): Proto {
        val g = Proto()
        for (n in nodes) g.message(1, n)
        g.string(2, name)
        for (i in initializers) g.message(5, i)
        for (i in inputs) g.message(11, i)
        for (o in outputs) g.message(12, o)
        return g
    }

    /** A `ModelProto` (IR 8, default-domain opset 13 unless given). */
    fun model(graph: Proto?, opset: Long = 13, irVersion: Long = 8, extra: (Proto) -> Unit = {}): Proto {
        val m = Proto().varint(1, irVersion).string(2, "faceblur-test")
        m.message(8, Proto().string(1, "").varint(2, opset))
        if (graph != null) m.message(7, graph)
        extra(m)
        return m
    }

    /** A structurally valid YuNet-layout model (no nodes) with a [w] × [h] input. */
    fun yunetModel(w: Int = 640, h: Int = 640, extraOutputs: List<Proto> = emptyList()): ByteArray {
        val outputs = ArrayList<Proto>()
        for (s in intArrayOf(8, 16, 32)) {
            val n = (w / s) * (h / s)
            outputs += valueInfo("cls_$s", FLOAT, listOf(1, n, 1))
            outputs += valueInfo("obj_$s", FLOAT, listOf(1, n, 1))
            outputs += valueInfo("bbox_$s", FLOAT, listOf(1, n, 4))
            outputs += valueInfo("kps_$s", FLOAT, listOf(1, n, 10))
        }
        outputs += extraOutputs
        val g = graph(inputs = listOf(valueInfo("input", FLOAT, listOf(1, 3, h, w))), outputs = outputs)
        return model(g).bytes()
    }

    /** A structurally valid generic model: input [1, 3, h, w] (or NHWC) and the given outputs. */
    fun genericModel(input: Proto, outputs: List<Proto>): ByteArray =
        model(graph(inputs = listOf(input), outputs = outputs)).bytes()
}
