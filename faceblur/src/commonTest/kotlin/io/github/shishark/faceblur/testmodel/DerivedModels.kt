// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.testmodel

/**
 * Derived generic-format test models built from the reference YuNet model at test time (ADR 0009 tier G). The YuNet
 * decode is appended to the graph as ONNX operators, so the model has one output of decoded detections
 * ([1, 8400, 15] for a 640 × 640 input, in the row order of FaceBlur's YuNet decoder: strides 8, 16, 32, row-major),
 * without NMS. Every source node and initializer is copied byte for byte ([sourcePartsPreserved] checks it).
 *
 * Variants:
 * - [Variant.G0]: columns x1, y1, x2, y2, score, 10 landmark values; input as the source (BGR, 0..255, NCHW).
 * - [Variant.G2]: as G0, but the input is RGB, NHWC, values 0..1 (the graph converts them back first).
 * - [Variant.G3]: permuted columns and another box format: score, x1, y1, width, height, landmarks.
 * - G1 ([buildG1], ADR 0012 P5): the G0 rows followed by the built-in ONNX `NonMaxSuppression` operator inside the
 *   graph, so the output has a dynamic row count ([1, n, 15]). G1 is not a [Variant]: the pinned-model build step
 *   rebuilds every [Variant], and G1 has its own pin in the parity test.
 *
 * These models are test fixtures, never committed, cached or published ("test fixture, not for distribution").
 */
internal object DerivedModels {
    enum class Variant { G0, G2, G3 }

    /** Deliberate builder faults for the mutation controls (P6). */
    enum class Mutant { NONE, GRID_OFFSET, SWAPPED_COLUMNS }

    const val OUTPUT = "detections"

    /** IoU threshold of G1's in-graph NMS: the same value as FaceBlur's in-pass NMS. */
    const val G1_IOU_THRESHOLD = 0.3f

    /**
     * Score cut-off of G1's in-graph NMS: below the profile threshold 0.5 of the parity test, so the graph drops no
     * box that the profile keeps (greedy NMS in descending score order: a box is only suppressed by higher scores).
     */
    const val G1_SCORE_THRESHOLD = 0.25f

    fun build(
        source: ByteArray,
        variant: Variant = Variant.G0,
        mutant: Mutant = Mutant.NONE,
        side: Int = 640,
    ): ByteArray = build(source, variant, mutant, side, null)

    /**
     * G1: the G0 graph with `NonMaxSuppression` (corner boxes, IoU above [iouThreshold] suppressed, scores above
     * [scoreThreshold] kept, at most every row) and a `Gather` of the selected rows as its only output [OUTPUT].
     * [scoreThreshold] above the profile threshold is the negative control of P5 (the graph then drops faces).
     */
    fun buildG1(
        source: ByteArray,
        scoreThreshold: Float = G1_SCORE_THRESHOLD,
        iouThreshold: Float = G1_IOU_THRESHOLD,
        side: Int = 640,
    ): ByteArray = build(source, Variant.G0, Mutant.NONE, side, floatArrayOf(scoreThreshold, iouThreshold))

    /** [graphNms] = null for the variants, or (score threshold, IoU threshold) of G1's in-graph NMS. */
    private fun build(
        source: ByteArray,
        variant: Variant,
        mutant: Mutant,
        side: Int,
        graphNms: FloatArray?,
    ): ByteArray {
        val model = Fields(source)
        val graph = Fields(model.single(MODEL_GRAPH))
        val inputName = "input"
        val g = Proto()
        // Copy every graph field except the outputs (and, for G2, the image input) verbatim, in order.
        for ((field, wire, bytes) in graph.all) {
            if (field == GRAPH_OUTPUT) continue
            if (variant == Variant.G2 && field == GRAPH_INPUT && nameOf(bytes) == inputName) continue
            g.tag(field, wire).raw(bytes.lengthPrefixedIf(wire))
        }
        val nodes = ArrayList<Proto>()
        val inits = ArrayList<Proto>()
        fun c(name: String, value: Float) = "fbg_$name".also {
            inits +=
                Onnx.floatTensor(it, listOf(1), floatArrayOf(value))
        }
        val zero = c("zero", 0f)
        val one = c("one", 1f)
        val half = c("half", 0.5f)
        if (variant == Variant.G2) {
            val perm = Proto().string(1, "perm").packedVarints(8, listOf(0, 3, 1, 2)).varint(20, 7)
            nodes += Onnx.node("Transpose", listOf("fbg_rgb_nhwc"), listOf("fbg_rgb_nchw"), listOf(perm))
            inits += Onnx.int64Tensor("fbg_bgr_index", listOf(3), longArrayOf(2, 1, 0))
            nodes +=
                Onnx.node(
                    "Gather",
                    listOf("fbg_rgb_nchw", "fbg_bgr_index"),
                    listOf("fbg_bgr01"),
                    listOf(Onnx.attrInt("axis", 1)),
                )
            nodes += Onnx.node("Mul", listOf("fbg_bgr01", c("v255", 255f)), listOf(inputName))
            g.message(GRAPH_INPUT, Onnx.valueInfo("fbg_rgb_nhwc", Onnx.FLOAT, listOf(1, side, side, 3)))
        }
        for (k in 0 until 4) inits += Onnx.int64Tensor("fbg_i$k", listOf(1), longArrayOf(k.toLong()))
        val perStride = ArrayList<String>()
        for (s in intArrayOf(8, 16, 32)) {
            val cols = side / s
            val n = cols * cols
            val offset = if (mutant == Mutant.GRID_OFFSET) 1f else 0f
            inits += Onnx.floatTensor("fbg_gx$s", listOf(1, n.toLong(), 1), FloatArray(n) { (it % cols) + offset })
            inits += Onnx.floatTensor("fbg_gy$s", listOf(1, n.toLong(), 1), FloatArray(n) { (it / cols).toFloat() })
            val stride = c("s$s", s.toFloat())
            fun t(name: String) = "fbg_${name}_$s"
            fun op(type: String, inputs: List<String>, out: String, attrs: List<Proto> = emptyList()) =
                t(out).also { nodes += Onnx.node(type, inputs, listOf(it), attrs) }
            val cls = op("Min", listOf(op("Max", listOf("cls_$s", zero), "cls0"), one), "cls")
            val obj = op("Min", listOf(op("Max", listOf("obj_$s", zero), "obj0"), one), "obj")
            val score = op("Sqrt", listOf(op("Mul", listOf(cls, obj), "prod")), "score")
            val d = (0 until 4).map { k ->
                op("Gather", listOf("bbox_$s", "fbg_i$k"), "d$k", listOf(Onnx.attrInt("axis", 2)))
            }
            val cx = op("Mul", listOf(op("Add", listOf("fbg_gx$s", d[0]), "gx"), stride), "cx")
            val cy = op("Mul", listOf(op("Add", listOf("fbg_gy$s", d[1]), "gy"), stride), "cy")
            val w = op("Mul", listOf(op("Exp", listOf(d[2]), "ew"), stride), "w")
            val h = op("Mul", listOf(op("Exp", listOf(d[3]), "eh"), stride), "h")
            val x1 = op("Sub", listOf(cx, op("Mul", listOf(w, half), "hw")), "x1")
            val y1 = op("Sub", listOf(cy, op("Mul", listOf(h, half), "hh")), "y1")
            val x2 = op("Add", listOf(x1, w), "x2")
            val y2 = op("Add", listOf(y1, h), "y2")
            val columns =
                when (variant) {
                    Variant.G3 -> listOf(score, x1, y1, w, h, "kps_$s")

                    else -> if (mutant ==
                        Mutant.SWAPPED_COLUMNS
                    ) {
                        listOf(y1, x1, x2, y2, score, "kps_$s")
                    } else {
                        listOf(x1, y1, x2, y2, score, "kps_$s")
                    }
                }
            perStride += op("Concat", columns, "rows", listOf(Onnx.attrInt("axis", 2)))
        }
        val total = intArrayOf(8, 16, 32).sumOf { (side / it) * (side / it) }
        val rows = if (graphNms == null) OUTPUT else "fbg_all_rows"
        nodes += Onnx.node("Concat", perStride, listOf(rows), listOf(Onnx.attrInt("axis", 1)))
        if (graphNms != null) {
            // NonMaxSuppression (center_point_box 0) takes corners as [y1, x1, y2, x2] and scores as [1, 1, n].
            inits += Onnx.int64Tensor("fbg_nms_box_cols", listOf(4), longArrayOf(1, 0, 3, 2))
            inits += Onnx.int64Tensor("fbg_nms_score_col", listOf(1), longArrayOf(4))
            inits += Onnx.int64Tensor("fbg_nms_max", listOf(1), longArrayOf(total.toLong()))
            inits += Onnx.floatTensor("fbg_nms_iou", listOf(1), floatArrayOf(graphNms[1]))
            inits += Onnx.floatTensor("fbg_nms_score", listOf(1), floatArrayOf(graphNms[0]))
            inits += Onnx.int64Tensor("fbg_nms_box_index", emptyList(), longArrayOf(2))
            val axis2 = listOf(Onnx.attrInt("axis", 2))
            nodes += Onnx.node("Gather", listOf(rows, "fbg_nms_box_cols"), listOf("fbg_nms_boxes"), axis2)
            nodes += Onnx.node("Gather", listOf(rows, "fbg_nms_score_col"), listOf("fbg_nms_scores_t"), axis2)
            val perm = Proto().string(1, "perm").packedVarints(8, listOf(0, 2, 1)).varint(20, 7)
            nodes += Onnx.node("Transpose", listOf("fbg_nms_scores_t"), listOf("fbg_nms_scores"), listOf(perm))
            nodes +=
                Onnx.node(
                    "NonMaxSuppression",
                    listOf("fbg_nms_boxes", "fbg_nms_scores", "fbg_nms_max", "fbg_nms_iou", "fbg_nms_score"),
                    listOf("fbg_nms_selected"),
                )
            nodes +=
                Onnx.node(
                    "Gather",
                    listOf("fbg_nms_selected", "fbg_nms_box_index"),
                    listOf("fbg_nms_rows"),
                    listOf(Onnx.attrInt("axis", 1)),
                )
            nodes += Onnx.node("Gather", listOf(rows, "fbg_nms_rows"), listOf(OUTPUT), listOf(Onnx.attrInt("axis", 1)))
        }
        for (n in nodes) g.message(GRAPH_NODE, n)
        for (i in inits) g.message(GRAPH_INITIALIZER, i)
        val rowDim: Any = if (graphNms == null) total else "n"
        g.message(GRAPH_OUTPUT, Onnx.valueInfo(OUTPUT, Onnx.FLOAT, listOf(1, rowDim, 15)))
        val m = Proto()
        for ((field, wire, bytes) in model.all) {
            if (field ==
                MODEL_GRAPH
            ) {
                m.message(MODEL_GRAPH, g)
            } else {
                m.tag(field, wire).raw(bytes.lengthPrefixedIf(wire))
            }
        }
        m.string(MODEL_DOC, "test fixture, not for distribution")
        return m.bytes()
    }

    /** True when every node and initializer of [source]'s graph occurs byte for byte, in order, in [derived]. */
    fun sourcePartsPreserved(source: ByteArray, derived: ByteArray): Boolean {
        fun parts(model: ByteArray) = Fields(Fields(model).single(MODEL_GRAPH)).all.filter {
            it.first == GRAPH_NODE ||
                it.first == GRAPH_INITIALIZER
        }
        val src = parts(source)
        val out = parts(derived)
        return out.size >= src.size &&
            src.indices.all { src[it].first == out[it].first && src[it].third.contentEquals(out[it].third) }
    }

    /** Number of source nodes and initializers. */
    fun counts(source: ByteArray): Pair<Int, Int> {
        val g = Fields(Fields(source).single(MODEL_GRAPH)).all
        return g.count { it.first == GRAPH_NODE } to g.count { it.first == GRAPH_INITIALIZER }
    }

    private fun nameOf(valueInfo: ByteArray): String? =
        Fields(valueInfo).all.lastOrNull { it.first == 1 && it.second == 2 }?.third?.decodeToString()

    private fun ByteArray.lengthPrefixedIf(wire: Int): ByteArray =
        if (wire == 2) Proto().varintRaw(size.toLong()).raw(this).bytes() else this

    /** The top-level fields of one message: (field, wire type, payload; for varint and fixed fields the raw bytes). */
    private class Fields(private val b: ByteArray) {
        val all: List<Triple<Int, Int, ByteArray>> = parse()

        fun single(field: Int): ByteArray = all.single { it.first == field }.third

        private fun parse(): List<Triple<Int, Int, ByteArray>> {
            val out = ArrayList<Triple<Int, Int, ByteArray>>()
            var p = 0
            fun varint(): Long {
                var r = 0L
                var shift = 0
                while (true) {
                    val x = b[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
            }
            while (p < b.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                val start = p
                when (wire) {
                    0 -> varint()

                    1 -> p += 8

                    5 -> p += 4

                    2 -> {
                        val len = varint().toInt()
                        out += Triple(field, wire, b.copyOfRange(p, p + len))
                        p += len
                        continue
                    }

                    else -> error("unsupported wire type $wire")
                }
                out += Triple(field, wire, b.copyOfRange(start, p))
            }
            return out
        }
    }

    private const val MODEL_DOC = 6
    private const val MODEL_GRAPH = 7
    private const val GRAPH_NODE = 1
    private const val GRAPH_INITIALIZER = 5
    private const val GRAPH_INPUT = 11
    private const val GRAPH_OUTPUT = 12
}
