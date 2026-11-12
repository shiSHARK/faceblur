// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.model

import io.github.shishark.faceblur.FaceMaskException

/** One graph input or output as the model file declares it. */
internal class DeclaredTensor(
    val name: String,
    /** True when the last `TypeProto.value` set is a tensor type. */
    val isTensor: Boolean,
    /** ONNX `TensorProto.DataType` (0 when not set). */
    val elementType: Int,
    /** The dimensions (null = symbolic or unset); null when the shape is not declared. */
    val dims: List<Long?>?,
)

/** What the pre-parse reads from a model: the report fields and the graph's inputs and outputs. */
internal class OnnxDescription(
    val irVersion: Long,
    val opsetImports: Map<String, Long>,
    /** Graph inputs that are not initializers, in file order. */
    val inputs: List<DeclaredTensor>,
    val outputs: List<DeclaredTensor>,
)

/**
 * The bounded streaming walk of an ONNX protobuf (ADR 0012 decision 4 step 4), the one mandatory check before any
 * byte reaches a runtime. It refuses ([FaceMaskException.ModelRejected]):
 * - the ORT flatbuffer format identifier at bytes 4-7 (`NOT_ONNX`);
 * - a `TensorProto` anywhere (every carrier position of [Fields], at any depth, in any combination, every occurrence
 *   of repeated fields, every attribute field regardless of the declared attribute type) with any `data_location` whose
 *   full 64-bit value is not 0, or with any `external_data` entry (`EXTERNAL_DATA`);
 * - a second occurrence of a singular carrier message (protobuf would merge it), a known field with an unexpected
 *   wire type, a group wire type, a length beyond the buffer, a malformed varint or tag (`NOT_ONNX`);
 * - message nesting deeper than [MAX_DEPTH] levels (`NESTING_TOO_DEEP`; the walk never stops descending and accepts).
 *
 * - more than [MAX_GRAPH_INPUTS] main-graph inputs (initializers listed as inputs included), [MAX_GRAPH_OUTPUTS] graph
 *   outputs, [MAX_OPSET_DOMAINS] operator-set domains or [MAX_DIMS] dimensions in one declared shape (`NOT_ONNX`;
 *   limits on the description, far above what a face detector declares).
 *
 * Repeated numeric fields are accepted packed and unpacked. Unknown fields with a valid wire type are skipped within
 * bounds. For the fields read for the contract the last occurrence wins and nested singular messages merge, as in the
 * runtime's parser. Time grows with the file size times at most the logarithm of the number of graph inputs. Memory
 * is bounded by the nesting depth and the limits above, plus a sorted table of a few bytes per graph-input name
 * (initializer names are matched against it without making strings): the walk keeps no node or initializer data,
 * and of the inputs that are not initializers it keeps only the first two (the contract needs exactly one).
 */
internal object OnnxPreParse {
    /** Deepest accepted message nesting; the model message is level 1. */
    const val MAX_DEPTH: Int = 100

    /** The ORT flatbuffer format identifier ("ORTM") that ONNX Runtime looks for at bytes 4-7. */
    private val ORT_IDENTIFIER = byteArrayOf(0x4F, 0x52, 0x54, 0x4D)

    fun parse(bytes: ByteArray): OnnxDescription {
        if (bytes.size >= 8 && (0 until 4).all { bytes[4 + it] == ORT_IDENTIFIER[it] }) reject(NOT_ONNX)
        val state = WalkState(bytes)
        state.walk(Fields.MODEL, 0, bytes.size, depth = 1)
        val graph = state.mainGraph
        val io = if (graph == null) null else readGraphIo(bytes, graph.first, graph.second)
        return OnnxDescription(
            irVersion = state.irVersion,
            opsetImports = state.opsets,
            inputs = io?.first.orEmpty(),
            outputs = io?.second.orEmpty(),
        )
    }

    // ---- Security walk ------------------------------------------------------------------------------------------

    private class WalkState(val bytes: ByteArray) {
        var irVersion = 0L
        val opsets = LinkedHashMap<String, Long>()
        var mainGraph: IntPair? = null

        fun walk(message: Message, start: Int, end: Int, depth: Int) {
            if (depth > MAX_DEPTH) reject(NESTING_TOO_DEEP)
            val r = Reader(bytes, start, end)
            // Singular carrier fields seen in this message (their numbers are below 64: one bit each).
            var seenSingular = 0L
            var externalData = false
            var dataLocationNonZero = false
            while (r.more()) {
                val (field, wire) = r.tag()
                val spec = message.fields[field]
                if (spec == null) {
                    r.skip(wire)
                    continue
                }
                if (wire !in spec.wireTypes) reject(NOT_ONNX)
                when (spec.kind) {
                    Kind.MESSAGE -> {
                        val (s, e) = r.lengthDelimited()
                        if (!spec.repeated) {
                            val bit = 1L shl field
                            if (seenSingular and bit != 0L) reject(NOT_ONNX)
                            seenSingular = seenSingular or bit
                        }
                        val child = spec.message ?: error("message spec without type")
                        if (message === Fields.MODEL && field == MODEL_GRAPH) mainGraph = IntPair(s, e)
                        walk(child, s, e, depth + 1)
                    }

                    Kind.SCALAR -> {
                        val v = r.scalar(wire)
                        if (message === Fields.MODEL && field == MODEL_IR_VERSION) irVersion = v
                        if (message === Fields.TENSOR && field == TENSOR_DATA_LOCATION && v != 0L) {
                            dataLocationNonZero = true
                        }
                    }

                    Kind.REPEATED_SCALAR -> if (wire == WIRE_LEN) r.lengthDelimited() else r.scalar(wire)

                    Kind.BYTES -> {
                        val (s, e) = r.lengthDelimited()
                        if (message === Fields.TENSOR && field == TENSOR_EXTERNAL_DATA) externalData = true
                        if (message === Fields.MODEL && field == MODEL_OPSET) readOpset(s, e)
                    }
                }
            }
            if (externalData || dataLocationNonZero) reject(EXTERNAL_DATA)
        }

        private fun readOpset(start: Int, end: Int) {
            val r = Reader(bytes, start, end)
            var domain = ""
            var version = 0L
            while (r.more()) {
                val (field, wire) = r.tag()
                when {
                    field == 1 && wire == WIRE_LEN -> domain = r.string()
                    field == 2 && wire == WIRE_VARINT -> version = r.scalar(wire)
                    else -> r.skip(wire)
                }
            }
            if (domain !in opsets && opsets.size == MAX_OPSET_DOMAINS) reject(NOT_ONNX)
            opsets[domain] = version
        }
    }

    // ---- Graph inputs and outputs (main graph only) --------------------------------------------------------------

    /**
     * The graph's inputs that are not initializers (at most the first two: the contract needs exactly one, so a
     * second one already decides) and its outputs. Initializer names are matched against a sorted table of the
     * input names, which refers to the names inside [bytes] (no strings): n log n name comparisons for n inputs, and
     * a few bytes per input name. More than [MAX_GRAPH_INPUTS] inputs, more than [MAX_GRAPH_OUTPUTS] outputs or more
     * than [MAX_DIMS] dimensions in one declared shape is `NOT_ONNX` (no face detector needs them).
     */
    private fun readGraphIo(bytes: ByteArray, start: Int, end: Int): Pair<List<DeclaredTensor>, List<DeclaredTensor>> {
        val outputs = ArrayList<DeclaredTensor>()
        val names = NameTable(bytes)
        var inputCount = 0
        // Pass 1: every input name into the table (each input checked), every output read.
        var r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            when (field) {
                GRAPH_INPUT -> {
                    if (inputCount == MAX_GRAPH_INPUTS) reject(NOT_ONNX)
                    val (s, e) = r.lengthDelimited()
                    names.add(readValueInfo(bytes, s, e, keep = false).nameAt)
                    inputCount++
                }

                GRAPH_OUTPUT -> {
                    val (s, e) = r.lengthDelimited()
                    if (outputs.size == MAX_GRAPH_OUTPUTS) reject(NOT_ONNX)
                    outputs += readValueInfo(bytes, s, e, keep = true).tensor!!
                }

                else -> r.skip(wire)
            }
        }
        // Pass 2: graph inputs that are also initializers are not model inputs (never fed).
        if (inputCount > 0) {
            r = Reader(bytes, start, end)
            while (r.more()) {
                val (field, wire) = r.tag()
                when (field) {
                    GRAPH_INITIALIZER -> {
                        val (s, e) = r.lengthDelimited()
                        names.mark(lastStringAt(bytes, s, e, TENSOR_NAME))
                    }

                    GRAPH_SPARSE_INITIALIZER -> {
                        val (s, e) = r.lengthDelimited()
                        names.mark(sparseNameAt(bytes, s, e))
                    }

                    else -> r.skip(wire)
                }
            }
        }
        // Pass 3: the inputs in file order; the first two that are not initializers are kept.
        val inputs = ArrayList<DeclaredTensor>(2)
        r = Reader(bytes, start, end)
        while (r.more() && inputs.size < 2) {
            val (field, wire) = r.tag()
            if (field != GRAPH_INPUT) {
                r.skip(wire)
                continue
            }
            val (s, e) = r.lengthDelimited()
            val nameAt = readValueInfo(bytes, s, e, keep = false).nameAt
            if (!names.isMarked(nameAt)) inputs += readValueInfo(bytes, s, e, keep = true).tensor!!
        }
        return inputs to outputs
    }

    /**
     * The position of the name of a sparse initializer: its `values.name`, with merged `values` occurrences (the last
     * name wins); [NO_NAME] when it has none.
     */
    private fun sparseNameAt(bytes: ByteArray, start: Int, end: Int): Int {
        var name = NO_NAME
        val r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == SPARSE_VALUES && wire == WIRE_LEN) {
                val (s, e) = r.lengthDelimited()
                val at = lastStringAt(bytes, s, e, TENSOR_NAME)
                if (at != NO_NAME) name = at
            } else {
                r.skip(wire)
            }
        }
        return name
    }

    /** The position of the length varint of the last string field [wanted], or [NO_NAME]. */
    private fun lastStringAt(bytes: ByteArray, start: Int, end: Int, wanted: Int): Int {
        var at = NO_NAME
        val r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == wanted && wire == WIRE_LEN) at = r.position()
            r.skip(wire)
        }
        return at
    }

    /**
     * Accumulates a `TypeProto.Tensor` across merged occurrences (scalars: last wins; dims: concatenated); the dims
     * are stored only when [keep].
     */
    private class TensorTypeAcc(keep: Boolean) {
        var elementType = 0
        var hasShape = false
        var dimCount = 0
        val dims: ArrayList<Long?>? = if (keep) ArrayList() else null

        fun addDim(value: Long?) {
            if (++dimCount > MAX_DIMS) reject(NOT_ONNX)
            dims?.add(value)
        }
    }

    /** A read `ValueInfoProto`: the position of its name (see [lastStringAt]) and, when kept, the tensor. */
    private class ValueInfo(val nameAt: Int, val tensor: DeclaredTensor?)

    /** Reads and checks one `ValueInfoProto`; builds the [DeclaredTensor] (strings, dims) only when [keep]. */
    private fun readValueInfo(bytes: ByteArray, start: Int, end: Int, keep: Boolean): ValueInfo {
        var nameAt = NO_NAME
        var isTensor = false
        var acc = TensorTypeAcc(keep)
        val r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == VALUE_INFO_NAME && wire == WIRE_LEN -> {
                    nameAt = r.position()
                    r.lengthDelimited()
                }

                field == VALUE_INFO_TYPE && wire == WIRE_LEN -> {
                    val (s, e) = r.lengthDelimited()
                    val t = Reader(bytes, s, e)
                    while (t.more()) {
                        val (tf, tw) = t.tag()
                        when {
                            tf == TYPE_TENSOR && tw == WIRE_LEN -> {
                                if (!isTensor) acc = TensorTypeAcc(keep)
                                isTensor = true
                                val (ts, te) = t.lengthDelimited()
                                mergeTensorType(bytes, ts, te, acc)
                            }

                            // Another member of the oneof replaces the tensor type; with another wire type protobuf
                            // keeps it as an unknown field and the tensor type stays.
                            tf in TYPE_OTHER_VALUES && tw == WIRE_LEN -> {
                                isTensor = false
                                t.skip(tw)
                            }

                            else -> t.skip(tw)
                        }
                    }
                }

                else -> r.skip(wire)
            }
        }
        if (!keep) return ValueInfo(nameAt, null)
        val name = if (nameAt == NO_NAME) "" else Reader(bytes, nameAt, end).string()
        val dims = if (acc.hasShape) acc.dims!!.toList() else null
        return ValueInfo(nameAt, DeclaredTensor(name, isTensor, acc.elementType, dims))
    }

    private fun mergeTensorType(bytes: ByteArray, start: Int, end: Int, acc: TensorTypeAcc) {
        val r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == TENSOR_TYPE_ELEM && wire == WIRE_VARINT -> acc.elementType = r.scalar(wire).toInt()

                field == TENSOR_TYPE_SHAPE && wire == WIRE_LEN -> {
                    acc.hasShape = true
                    val (s, e) = r.lengthDelimited()
                    val shape = Reader(bytes, s, e)
                    while (shape.more()) {
                        val (sf, sw) = shape.tag()
                        if (sf == SHAPE_DIM && sw == WIRE_LEN) {
                            val (ds, de) = shape.lengthDelimited()
                            acc.addDim(readDimension(bytes, ds, de))
                        } else {
                            shape.skip(sw)
                        }
                    }
                }

                else -> r.skip(wire)
            }
        }
    }

    /**
     * A set of the distinct names at positions in [bytes] (each position is the length varint of a string field;
     * [NO_NAME] is the empty name), each with a mark. The positions are sorted by the name bytes (merge sort, so the
     * cost cannot be steered by chosen names: at most n log n comparisons, each bounded by the shorter name) and
     * looked up by binary search; no strings are made. Memory: two ints per added name while sorting, one after.
     */
    internal class NameTable(private val bytes: ByteArray) {
        /** Name comparisons of the sort (and its duplicate removal): at most n·⌈log₂ n⌉ for n names. */
        var sortComparisons: Long = 0L
            private set

        /** The most name comparisons one lookup ([mark] or [isMarked]) made: at most ⌈log₂(n + 1)⌉. */
        var maxLookupComparisons: Int = 0
            private set

        private var added = IntArray(16)
        private var count = 0
        private var sorted: IntArray? = null
        private var emptyName = false
        private var emptyMarked = false

        /** Number of distinct names (valid after the first [mark] or [isMarked]). */
        val size: Int get() = count + (if (emptyName) 1 else 0)

        fun add(at: Int) {
            check(sorted == null) { "add after lookup" }
            if (isEmpty(at)) {
                emptyName = true
                return
            }
            if (count == added.size) added = added.copyOf(added.size * 2)
            added[count++] = at
        }

        fun mark(at: Int) {
            if (isEmpty(at)) {
                if (emptyName) emptyMarked = true
                return
            }
            val table = table()
            val i = search(table, at)
            if (i >= 0 && table[i] >= 0) table[i] = table[i].inv()
        }

        fun isMarked(at: Int): Boolean {
            if (isEmpty(at)) return emptyMarked
            val table = table()
            val i = search(table, at)
            return i >= 0 && table[i] < 0
        }

        /** The distinct positions sorted by name (a marked entry is stored inverted, so it is negative). */
        private fun table(): IntArray {
            sorted?.let { return it }
            var a = added.copyOf(count)
            added = IntArray(0)
            var tmp = IntArray(a.size)
            var width = 1
            while (width < a.size) {
                var lo = 0
                while (lo < a.size) {
                    val mid = minOf(lo + width, a.size)
                    val hi = minOf(lo + 2 * width, a.size)
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) {
                        sortComparisons++
                        tmp[k++] = if (compare(a[i], a[j]) <= 0) a[i++] else a[j++]
                    }
                    while (i < mid) tmp[k++] = a[i++]
                    while (j < hi) tmp[k++] = a[j++]
                    lo = hi
                }
                val t = a
                a = tmp
                tmp = t
                width *= 2
            }
            var n = 0
            for (i in a.indices) {
                if (n > 0) sortComparisons++
                if (n == 0 || compare(a[n - 1], a[i]) != 0) a[n++] = a[i]
            }
            count = n
            return a.copyOf(n).also { sorted = it }
        }

        private fun search(table: IntArray, at: Int): Int {
            var lo = 0
            var hi = table.size - 1
            var comparisons = 0
            try {
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val v = table[mid]
                    comparisons++
                    val c = compare(if (v < 0) v.inv() else v, at)
                    when {
                        c < 0 -> lo = mid + 1
                        c > 0 -> hi = mid - 1
                        else -> return mid
                    }
                }
                return -1
            } finally {
                if (comparisons > maxLookupComparisons) maxLookupComparisons = comparisons
            }
        }

        private fun isEmpty(at: Int): Boolean = at == NO_NAME || range(at).let { it.first == it.second }

        private fun range(at: Int): IntPair = Reader(bytes, at, bytes.size).lengthDelimited()

        /** Compares the names at [a] and [b] as unsigned bytes. */
        private fun compare(a: Int, b: Int): Int {
            val (as1, ae) = range(a)
            val (bs, be) = range(b)
            val la = ae - as1
            val lb = be - bs
            for (k in 0 until minOf(la, lb)) {
                val d = (bytes[as1 + k].toInt() and 0xFF) - (bytes[bs + k].toInt() and 0xFF)
                if (d != 0) return d
            }
            return la - lb
        }
    }

    /** `Dimension.value` is a oneof: the last of `dim_value` and `dim_param` set wins. */
    private fun readDimension(bytes: ByteArray, start: Int, end: Int): Long? {
        var value: Long? = null
        val r = Reader(bytes, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == DIM_VALUE && wire == WIRE_VARINT -> value = r.scalar(wire)

                field == DIM_PARAM && wire == WIRE_LEN -> {
                    r.lengthDelimited()
                    value = null
                }

                else -> r.skip(wire)
            }
        }
        return value
    }

    // ---- Protobuf reading -----------------------------------------------------------------------------------------

    /** Reads one message range; every length is checked against the remaining bytes. */
    private class Reader(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun more(): Boolean = pos < end

        fun tag(): IntPair {
            val t = varint()
            val wire = (t and 7L).toInt()
            val field = t ushr 3
            if (field < 1L || field > MAX_FIELD) reject(NOT_ONNX)
            if (wire == WIRE_START_GROUP || wire == WIRE_END_GROUP || wire > WIRE_FIXED32) reject(NOT_ONNX)
            return IntPair(field.toInt(), wire)
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) reject(NOT_ONNX)
                val byte = b[pos++].toInt() and 0xFF
                if (shift == 63 && byte > 1) reject(NOT_ONNX)
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) reject(NOT_ONNX)
            }
        }

        fun lengthDelimited(): IntPair {
            val len = varint()
            if (len < 0L || len > (end - pos).toLong()) reject(NOT_ONNX)
            val s = pos
            pos += len.toInt()
            return IntPair(s, pos)
        }

        fun string(): String {
            val (s, e) = lengthDelimited()
            return b.decodeToString(s, e)
        }

        /** The current position (the start of the next value). */
        fun position(): Int = pos

        fun scalar(wire: Int): Long = when (wire) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> fixed(8)
            WIRE_FIXED32 -> fixed(4)
            else -> reject(NOT_ONNX)
        }

        private fun fixed(n: Int): Long {
            if (end - pos < n) reject(NOT_ONNX)
            var v = 0L
            for (i in 0 until n) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += n
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT, WIRE_FIXED64, WIRE_FIXED32 -> scalar(wire)
                WIRE_LEN -> lengthDelimited()
                else -> reject(NOT_ONNX)
            }
        }
    }

    /** Most graph outputs a model may declare (a face detector has a few). */
    const val MAX_GRAPH_OUTPUTS: Int = 1_024

    /**
     * Largest number of main-graph inputs, initializers listed as inputs included (ADR 0013 decision 5). It caps the
     * sorted name table and its memory, so the pre-parse time grows about linearly with the file size.
     */
    const val MAX_GRAPH_INPUTS: Int = 65_536

    /** Most distinct operator-set domains a model may import. */
    const val MAX_OPSET_DOMAINS: Int = 1_024

    /** Most dimensions in one declared input or output shape. */
    const val MAX_DIMS: Int = 64

    private const val NO_NAME = -1

    internal const val NOT_ONNX = "NOT_ONNX"
    internal const val NESTING_TOO_DEEP = "NESTING_TOO_DEEP"
    internal const val EXTERNAL_DATA = "EXTERNAL_DATA"

    private fun reject(code: String): Nothing = throw FaceMaskException.ModelRejected(listOf(code))

    private const val MAX_FIELD = (1L shl 29) - 1
    private const val MODEL_IR_VERSION = 1
    private const val MODEL_GRAPH = 7
    private const val MODEL_OPSET = 8
    private const val GRAPH_INITIALIZER = 5
    private const val GRAPH_INPUT = 11
    private const val GRAPH_OUTPUT = 12
    private const val GRAPH_SPARSE_INITIALIZER = 15
    private const val TENSOR_NAME = 8
    private const val TENSOR_EXTERNAL_DATA = 13
    private const val TENSOR_DATA_LOCATION = 14
    private const val SPARSE_VALUES = 1
    private const val VALUE_INFO_NAME = 1
    private const val VALUE_INFO_TYPE = 2
    private const val TYPE_TENSOR = 1

    /** The other members of the `TypeProto.value` oneof (sequence, map, sparse tensor, opaque, optional). */
    private val TYPE_OTHER_VALUES = setOf(4, 5, 7, 8, 9)
    private const val TENSOR_TYPE_ELEM = 1
    private const val TENSOR_TYPE_SHAPE = 2
    private const val SHAPE_DIM = 1
    private const val DIM_VALUE = 1
    private const val DIM_PARAM = 2
}

/** Two ints in one long (tag: field and wire type; range: start and end), so the walk allocates no pair objects. */
@kotlin.jvm.JvmInline
internal value class IntPair private constructor(private val packed: Long) {
    constructor(first: Int, second: Int) : this((first.toLong() shl 32) or (second.toLong() and 0xFFFFFFFFL))

    val first: Int get() = (packed ushr 32).toInt()
    val second: Int get() = packed.toInt()

    operator fun component1(): Int = first

    operator fun component2(): Int = second
}

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_START_GROUP = 3
internal const val WIRE_END_GROUP = 4
internal const val WIRE_FIXED32 = 5

/** How the walker treats a known field. */
internal enum class Kind {
    /** A message that can contain a tensor: walked recursively. */
    MESSAGE,

    /** A singular varint or fixed-width value. */
    SCALAR,

    /** A repeated numeric value: packed (length-delimited) or unpacked. */
    REPEATED_SCALAR,

    /** A string, bytes or a message that cannot contain a tensor: skipped as bytes. */
    BYTES,
}

internal class FieldSpec(
    val kind: Kind,
    val wireTypes: Set<Int>,
    val message: Message? = null,
    val repeated: Boolean = false,
)

/** A message type of the walker's field table. */
internal class Message(val name: String) {
    var fields: Map<Int, FieldSpec> = emptyMap()
        internal set
}

/**
 * The walker's field table: the union of the `onnx.proto` files vendored by ONNX Runtime 1.20.0 (onnx 1.16.1) and
 * 1.31.0 (onnx 1.22.0), for the messages that can contain a tensor and the fields with a fixed wire type. Every
 * other field is skipped within bounds. A unit test pins this table; re-check it at every runtime version change.
 */
internal object Fields {
    val MODEL = Message("ModelProto")
    val GRAPH = Message("GraphProto")
    val NODE = Message("NodeProto")
    val ATTRIBUTE = Message("AttributeProto")
    val TENSOR = Message("TensorProto")
    val SPARSE_TENSOR = Message("SparseTensorProto")
    val FUNCTION = Message("FunctionProto")
    val TRAINING_INFO = Message("TrainingInfoProto")

    private val len = setOf(WIRE_LEN)

    private fun msg(m: Message, repeated: Boolean) = FieldSpec(Kind.MESSAGE, len, m, repeated)

    private fun scalar(wire: Int) = FieldSpec(Kind.SCALAR, setOf(wire))

    private fun repeatedScalar(wire: Int) = FieldSpec(Kind.REPEATED_SCALAR, setOf(wire, WIRE_LEN))

    private val bytes = FieldSpec(Kind.BYTES, len)

    init {
        MODEL.fields = mapOf(
            1 to scalar(WIRE_VARINT), // ir_version
            2 to bytes, // producer_name
            3 to bytes, // producer_version
            4 to bytes, // domain
            5 to scalar(WIRE_VARINT), // model_version
            6 to bytes, // doc_string
            7 to msg(GRAPH, repeated = false), // graph
            8 to bytes, // opset_import
            14 to bytes, // metadata_props
            20 to msg(TRAINING_INFO, repeated = true), // training_info
            25 to msg(FUNCTION, repeated = true), // functions
            26 to bytes, // configuration (onnx 1.22.0)
        )
        GRAPH.fields = mapOf(
            1 to msg(NODE, repeated = true), // node
            2 to bytes, // name
            5 to msg(TENSOR, repeated = true), // initializer
            10 to bytes, // doc_string
            11 to bytes, // input
            12 to bytes, // output
            13 to bytes, // value_info
            14 to bytes, // quantization_annotation
            15 to msg(SPARSE_TENSOR, repeated = true), // sparse_initializer
            16 to bytes, // metadata_props
        )
        NODE.fields = mapOf(
            1 to bytes, // input
            2 to bytes, // output
            3 to bytes, // name
            4 to bytes, // op_type
            5 to msg(ATTRIBUTE, repeated = true), // attribute
            6 to bytes, // doc_string
            7 to bytes, // domain
            8 to bytes, // overload
            9 to bytes, // metadata_props
            10 to bytes, // device_configurations (onnx 1.22.0)
        )
        ATTRIBUTE.fields = mapOf(
            1 to bytes, // name
            2 to scalar(WIRE_FIXED32), // f
            3 to scalar(WIRE_VARINT), // i
            4 to bytes, // s
            5 to msg(TENSOR, repeated = false), // t
            6 to msg(GRAPH, repeated = false), // g
            7 to repeatedScalar(WIRE_FIXED32), // floats
            8 to repeatedScalar(WIRE_VARINT), // ints
            9 to bytes, // strings
            10 to msg(TENSOR, repeated = true), // tensors
            11 to msg(GRAPH, repeated = true), // graphs
            13 to bytes, // doc_string
            14 to bytes, // tp
            15 to bytes, // type_protos
            20 to scalar(WIRE_VARINT), // type
            21 to bytes, // ref_attr_name
            22 to msg(SPARSE_TENSOR, repeated = false), // sparse_tensor
            23 to msg(SPARSE_TENSOR, repeated = true), // sparse_tensors
        )
        TENSOR.fields = mapOf(
            1 to repeatedScalar(WIRE_VARINT), // dims
            2 to scalar(WIRE_VARINT), // data_type
            3 to bytes, // segment
            4 to repeatedScalar(WIRE_FIXED32), // float_data
            5 to repeatedScalar(WIRE_VARINT), // int32_data
            6 to bytes, // string_data
            7 to repeatedScalar(WIRE_VARINT), // int64_data
            8 to bytes, // name
            9 to bytes, // raw_data
            10 to repeatedScalar(WIRE_FIXED64), // double_data
            11 to repeatedScalar(WIRE_VARINT), // uint64_data
            12 to bytes, // doc_string
            13 to bytes, // external_data
            14 to scalar(WIRE_VARINT), // data_location
            16 to bytes, // metadata_props
        )
        SPARSE_TENSOR.fields = mapOf(
            1 to msg(TENSOR, repeated = false), // values
            2 to msg(TENSOR, repeated = false), // indices
            3 to repeatedScalar(WIRE_VARINT), // dims
        )
        FUNCTION.fields = mapOf(
            1 to bytes, // name
            4 to bytes, // input
            5 to bytes, // output
            6 to bytes, // attribute
            7 to msg(NODE, repeated = true), // node
            8 to bytes, // doc_string
            9 to bytes, // opset_import
            10 to bytes, // domain
            11 to msg(ATTRIBUTE, repeated = true), // attribute_proto
            12 to bytes, // value_info
            13 to bytes, // overload
            14 to bytes, // metadata_props
        )
        TRAINING_INFO.fields = mapOf(
            1 to msg(GRAPH, repeated = false), // initialization
            2 to msg(GRAPH, repeated = false), // algorithm
            3 to bytes, // initialization_binding
            4 to bytes, // update_binding
        )
    }

    /** Every message of the table. */
    val ALL: List<Message> = listOf(MODEL, GRAPH, NODE, ATTRIBUTE, TENSOR, SPARSE_TENSOR, FUNCTION, TRAINING_INFO)

    init {
        // The walk records singular carrier fields in a 64-bit mask.
        for (m in ALL) for ((n, f) in m.fields) check(f.repeated || f.kind != Kind.MESSAGE || n in 1..63)
    }
}
