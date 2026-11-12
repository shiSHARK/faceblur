// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.testmodel

/**
 * The hostile and structural models of the pre-parse tests, each with the walker's expected verdict, built at test
 * time. `OnnxPreParseTest` asserts the verdicts; the JVM differential (`PreParseDifferential`) feeds the same models
 * to a full protobuf parser.
 */
internal object HostileModels {
    /**
     * One model and the walker's expected verdict: a finding code, null when the walk accepts it, or [ANY] when only
     * crash-freedom is asserted (truncations that end on a field boundary are valid protobuf).
     */
    class Case(val label: String, val bytes: ByteArray, val expected: String?)

    const val ANY = "ANY"

    const val EXTERNAL_DATA = "EXTERNAL_DATA"
    const val NOT_ONNX = "NOT_ONNX"
    const val NESTING_TOO_DEEP = "NESTING_TOO_DEEP"

    val input: Proto get() = Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 640, 640))
    val output: Proto get() = Onnx.valueInfo("out", Onnx.FLOAT, listOf(1, 10, 5))

    fun graphWith(
        nodes: List<Proto> = emptyList(),
        initializers: List<Proto> = emptyList(),
        extra: (Proto) -> Unit = {
        },
    ) = Onnx.graph(nodes, initializers, listOf(input), listOf(output)).also(extra)

    fun modelWith(graph: Proto, extra: (Proto) -> Unit = {}) = Onnx.model(graph, extra = extra).bytes()

    private fun ext(name: String = "w") = Onnx.externalTensor(name, "/etc/x")

    private val plain get() = Onnx.floatTensor("v", listOf(1), floatArrayOf(1f))
    private val idx get() = Onnx.int64Tensor("i", listOf(1), longArrayOf(0))

    private fun sparse(values: Proto, indices: Proto) = Proto().message(1, values).message(2, indices).varint(3, 4)

    /** A polyglot (valid protobuf for the walker, "ORTM" at bytes 4-7) and a valid model with "ORTM" planted there. */
    fun ortIdentifier(): Map<String, ByteArray> {
        // Field 1 = ir_version with a 3-byte varint, then a string field whose bytes 4-7 are "ORTM".
        val polyglot = Proto().raw(byteArrayOf(0x08, 0x88.toByte(), 0x80.toByte(), 0x01))
            .raw("ORTM".encodeToByteArray())
        val planted = Onnx.model(graphWith()).bytes().also { "ORTM".encodeToByteArray().copyInto(it, 4) }
        return mapOf("polyglot" to polyglot.bytes(), "planted identifier" to planted)
    }

    /** One model per carrier position of ADR 0012 decision 4 step 4 item 2, each with an external tensor. */
    fun externalAtEveryPosition(): Map<String, ByteArray> {
        val innerGraph = { Onnx.graph(initializers = listOf(ext()), inputs = emptyList(), outputs = emptyList()) }
        val attrOfType = { field: Int, msg: Proto -> Proto().string(1, "a").message(field, msg).varint(20, 4) }
        val constant = { Onnx.node("Constant", emptyList(), listOf("c"), listOf(Onnx.attrTensor("value", ext()))) }
        val x = { field: Int, msg: Proto -> Onnx.node("X", emptyList(), listOf("o"), listOf(attrOfType(field, msg))) }
        return mapOf(
            "graph initializer" to modelWith(graphWith(initializers = listOf(ext()))),
            "sparse initializer values" to modelWith(graphWith { it.message(15, sparse(ext(), idx)) }),
            "sparse initializer indices" to modelWith(graphWith { it.message(15, sparse(plain, ext())) }),
            "node attribute t" to modelWith(graphWith(listOf(constant()))),
            "node attribute tensors" to
                modelWith(graphWith(listOf(Onnx.node("X", emptyList(), listOf("c"), listOf(attrOfType(10, ext())))))),
            "node attribute g" to
                modelWith(
                    graphWith(listOf(Onnx.node("If", listOf("c"), listOf("o"), listOf(attrOfType(6, innerGraph()))))),
                ),
            "node attribute graphs" to modelWith(graphWith(listOf(x(11, innerGraph())))),
            "node attribute sparse_tensor" to modelWith(graphWith(listOf(x(22, sparse(ext(), idx))))),
            "node attribute sparse_tensors" to modelWith(graphWith(listOf(x(23, sparse(plain, ext()))))),
            "function node" to modelWith(graphWith()) { it.message(25, Proto().string(1, "f").message(7, constant())) },
            "function attribute_proto" to modelWith(graphWith()) {
                it.message(25, Proto().string(1, "f").message(11, Onnx.attrTensor("default", ext())))
            },
            "training_info initialization" to
                modelWith(graphWith()) { it.message(20, Proto().message(1, innerGraph())) },
            "training_info algorithm" to modelWith(graphWith()) { it.message(20, Proto().message(2, innerGraph())) },
            // A Constant with an external tensor inside an If branch inside a model-local function.
            "nested combination" to modelWith(graphWith()) {
                val branch = Onnx.graph(listOf(constant()), inputs = emptyList(), outputs = emptyList())
                val ifNode = Onnx.node("If", listOf("cond"), listOf("o"), listOf(attrOfType(6, branch)))
                it.message(25, Proto().string(1, "f").message(7, ifNode))
            },
        )
    }

    /** The same structures with plain tensors: they load. */
    fun externalControls(): Map<String, ByteArray> = mapOf(
        "plain initializer" to modelWith(graphWith(initializers = listOf(plain))),
        "plain sparse initializer" to modelWith(graphWith { it.message(15, sparse(plain, idx)) }),
    )

    /** Every encoding of the location that the refusal predicate covers, each in a graph initializer. */
    fun locationEncodings(): Map<String, ByteArray> {
        val name = Proto().varint(1, 4).varint(2, 1).string(8, "w")
        val tensors = mapOf(
            "external_data entry with data_location 0" to Onnx.externalTensor("w", "x", dataLocation = 0L),
            "external_data entry without data_location" to Onnx.externalTensor("w", "x", dataLocation = null),
            "data_location 2^32 + 1 (truncates to EXTERNAL)" to Proto().raw(name.bytes()).varint(14, (1L shl 32) + 1),
            "unknown data_location value" to Proto().raw(name.bytes()).varint(14, 7),
            "negative data_location" to Proto().raw(name.bytes()).varint(14, -1L),
            "data_location 1 without entries" to Proto().raw(name.bytes()).varint(14, 1),
            "a second data_location occurrence" to Proto().raw(name.bytes()).varint(14, 1).varint(14, 0),
        )
        val models = LinkedHashMap<String, ByteArray>()
        for ((label, tensor) in tensors) models[label] = modelWith(graphWith(initializers = listOf(tensor)))
        // A tensor in an attribute field that does not match the declared attribute type (INT) is walked too.
        val mismatched = Proto().string(1, "a").varint(20, 2).varint(3, 5).message(5, Onnx.externalTensor("w", "x"))
        models["tensor in an attribute of another declared type"] =
            modelWith(graphWith(listOf(Onnx.node("X", emptyList(), listOf("o"), listOf(mismatched)))))
        return models
    }

    /** data_location 0 alone is the default and loads. */
    fun locationZero(): ByteArray {
        val name = Proto().varint(1, 4).varint(2, 1).string(8, "w")
        return modelWith(graphWith(initializers = listOf(Proto().raw(name.bytes()).varint(14, 0))))
    }

    /** A second occurrence of a singular carrier message (protobuf would merge it): each is `NOT_ONNX`. */
    fun duplicateSingular(): Map<String, ByteArray> {
        val hidden = Onnx.graph(initializers = listOf(ext()), inputs = emptyList(), outputs = emptyList())
        val empty = Onnx.graph(inputs = emptyList(), outputs = emptyList())
        fun attr(fill: (Proto) -> Unit) =
            Onnx.node("X", emptyList(), listOf("o"), listOf(Proto().string(1, "a").also(fill)))
        val models = LinkedHashMap<String, ByteArray>()
        // An external-data initializer in a second `graph` occurrence (the runtime would merge both graphs).
        models["second graph with an external initializer"] = Onnx.model(graphWith()) { it.message(7, hidden) }.bytes()
        val nodes = mapOf(
            "attribute t" to attr { it.message(5, plain).message(5, plain) },
            "attribute g" to attr { it.message(6, empty).message(6, empty) },
            "attribute sparse_tensor" to
                attr { it.message(22, Proto().message(1, plain)).message(22, Proto().message(1, plain)) },
            "sparse values" to attr { it.message(22, Proto().message(1, plain).message(1, plain)) },
            "sparse indices" to attr { it.message(22, Proto().message(2, idx).message(2, idx)) },
        )
        for ((label, node) in nodes) models[label] = modelWith(graphWith(listOf(node)))
        for (field in listOf(1, 2)) {
            models["training info field $field"] =
                Onnx.model(graphWith()) { it.message(20, Proto().message(field, empty).message(field, empty)) }.bytes()
        }
        return models
    }

    /** Repeated fields are walked in every occurrence: the second initializer is the external one. */
    fun repeatedWithExternal(): ByteArray = modelWith(graphWith(initializers = listOf(plain, ext())))

    /** A valid model with one initializer; its truncations are malformed. */
    fun truncationBase(): ByteArray =
        Onnx.model(graphWith(initializers = listOf(Onnx.floatTensor("w", listOf(2), floatArrayOf(1f, 2f))))).bytes()

    /** Malformed protobuf: each is `NOT_ONNX`. */
    fun malformed(): Map<String, ByteArray> = mapOf(
        "known field with the wrong wire type (graph as varint)" to Proto().varint(7, 1).bytes(),
        "known scalar as length-delimited (ir_version)" to Proto().bytes(1, byteArrayOf(1)).bytes(),
        "start group" to Proto().tag(99, 3).bytes(),
        "end group" to Proto().tag(99, 4).bytes(),
        "wire type 6" to Proto().tag(99, 6).bytes(),
        "field number 0" to Proto().tag(0, 0).varintRaw(1).bytes(),
        "length beyond the buffer" to Proto().tag(6, 2).varintRaw(1000).raw(byteArrayOf(1, 2)).bytes(),
        "huge declared length" to Proto().tag(6, 2).varintRaw(Long.MAX_VALUE).bytes(),
        "varint longer than 10 bytes" to Proto().tag(5, 0).raw(ByteArray(11) { 0x80.toByte() }).bytes(),
        "10-byte varint with high bits" to
            Proto().tag(5, 0).raw(ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x7F)).bytes(),
        "fixed32 cut short" to Proto().tag(99, 5).raw(byteArrayOf(1, 2)).bytes(),
        "group inside a node" to modelWith(graphWith(listOf(Proto().tag(77, 3)))),
    )

    /** The payload of an unknown length-delimited field is not parsed (as in protobuf): it is skipped as bytes. */
    fun groupInUnknownPayload(): ByteArray = modelWith(graphWith()) { it.message(99, Proto().tag(3, 3)) }

    /** Unknown fields everywhere and repeated numbers packed and unpacked: accepted. */
    fun unknownAndPacked(): ByteArray {
        val unpacked = Proto().varint(1, 2).varint(1, 3).varint(2, 1).string(8, "w")
        for (k in 0 until 6) unpacked.fixed32(4, (k + 1).toFloat().toBits())
        val packed = Proto().packedVarints(1, listOf(2, 3)).varint(2, 1).string(8, "w2")
        packed.bytes(4, ByteArray(24) { 0 })
        val floatsUnpacked = Proto().string(1, "a").float(7, 1f).float(7, 2f).varint(8, 3).varint(8, 4)
        val floatsPacked = Proto().string(1, "b").bytes(7, ByteArray(8)).packedVarints(8, listOf(3, 4))
        val node = Onnx.node("X", emptyList(), listOf("o"), listOf(floatsUnpacked, floatsPacked))
        return Onnx.model(graphWith(listOf(node), listOf(unpacked, packed)) { it.varint(999, 5).string(1000, "x") }) {
            it.fixed32(500, 1).tag(501, 1).raw(ByteArray(8))
        }.bytes()
    }

    /**
     * A chain of `If` subgraphs whose innermost attribute is at [attributeDepth] (levels: model 1, graph 2, node 3,
     * attribute 4, graph 5, ...; an attribute is at level 4 + 3k), optionally with a tensor one level deeper.
     */
    fun depthChain(attributeDepth: Int, withTensor: Boolean): ByteArray {
        var attribute = Proto().string(1, "a")
        if (withTensor) attribute.message(5, Onnx.floatTensor("t", listOf(1), floatArrayOf(0f)))
        var depth = attributeDepth
        while (depth > 4) {
            val graph = Onnx.graph(
                listOf(Onnx.node("If", emptyList(), listOf("o"), listOf(attribute))),
                inputs = emptyList(),
                outputs = emptyList(),
            )
            attribute = Proto().string(1, "g").message(6, graph)
            depth -= 3
        }
        return modelWith(graphWith(listOf(Onnx.node("If", emptyList(), listOf("o"), listOf(attribute)))))
    }

    /** Merged `type` occurrences, a symbolic last dim, dense and sparse initializers listed as inputs. */
    fun contractMerging(): ByteArray {
        val tensorA = Proto().varint(1, Onnx.INT64.toLong()).message(2, Proto().message(1, Proto().varint(1, 1)))
        val tensorB = Proto().varint(1, Onnx.FLOAT.toLong()).message(2, Proto().message(1, Proto().varint(1, 3)))
        val dimParamLast = Proto().varint(1, 640).string(2, "h")
        val tensorC = Proto().message(2, Proto().message(1, dimParamLast).message(1, Proto().varint(1, 640)))
        val merged = Proto().string(1, "image").message(2, Proto().message(1, tensorA))
            .message(2, Proto().message(1, tensorB)).message(2, Proto().message(1, tensorC))
        val sparse = Proto().message(1, Onnx.floatTensor("s", listOf(1), floatArrayOf(1f)))
            .message(2, Onnx.int64Tensor("i", listOf(1), longArrayOf(0)))
        val g = Onnx.graph(
            initializers = listOf(Onnx.floatTensor("w", listOf(4), FloatArray(4))),
            inputs = listOf(
                merged,
                Onnx.valueInfo("w", Onnx.FLOAT, listOf(4)),
                Onnx.valueInfo("s", Onnx.FLOAT, listOf(4)),
            ),
            outputs = listOf(output),
        ).also { it.message(15, sparse) }
        return Onnx.model(g).bytes()
    }

    /** A later non-tensor value type replaces the tensor type (oneof); a later tensor type starts afresh. */
    fun contractOneof(): ByteArray {
        val tensorB = Proto().varint(1, Onnx.FLOAT.toLong()).message(2, Proto().message(1, Proto().varint(1, 3)))
        val sequenceLast = Proto().string(1, "x").message(2, Proto().message(1, tensorB).message(4, Proto()))
        val tensorAgain = Proto().string(1, "y").message(2, Proto().message(4, Proto()).message(1, tensorB))
        return Onnx.model(Onnx.graph(inputs = listOf(sequenceLast, tensorAgain), outputs = listOf(output))).bytes()
    }

    /**
     * Oneof members with a wire type protobuf does not expect for them (kept as unknown fields): they replace nothing,
     * so "x" stays a tensor of [1, 3] (two merged occurrences) and "y" merges its two tensor types to [3, 640].
     */
    fun contractOneofWireType(): ByteArray {
        val tensor = { dim: Long ->
            Proto().varint(1, Onnx.FLOAT.toLong()).message(2, Proto().message(1, Proto().varint(1, dim)))
        }
        val x = Proto().string(1, "x").message(2, Proto().message(1, tensor(1)).message(1, tensor(3)).varint(4, 1))
        val y = Proto().string(1, "y")
            .message(2, Proto().message(1, tensor(3)).varint(5, 1).fixed32(9, 0).message(1, tensor(640)))
        return Onnx.model(Onnx.graph(inputs = listOf(x, y), outputs = listOf(output))).bytes()
    }

    fun outputs(n: Int): ByteArray = Onnx.model(
        Onnx.graph(
            inputs = listOf(input),
            outputs = List(n) {
                Onnx.valueInfo("o$it", Onnx.FLOAT, listOf(1, 2))
            },
        ),
    )
        .bytes()

    fun opsets(n: Int): ByteArray = Onnx.model(graphWith()) { m ->
        repeat(n - 1) { m.message(8, Proto().string(1, "d$it").varint(2, 1)) }
    }.bytes()

    /** The same domain repeated is one domain (last version wins). */
    fun repeatedOpset(): ByteArray = Onnx.model(graphWith()) { m ->
        repeat(5_000) { m.message(8, Proto().string(1, "").varint(2, 17)) }
    }.bytes()

    fun outputDims(n: Int): ByteArray = Onnx.model(
        Onnx.graph(inputs = listOf(input), outputs = listOf(Onnx.valueInfo("out", Onnx.FLOAT, List(n) { 1 }))),
    ).bytes()

    /** 65 dims on an input that is an initializer (each input is checked). */
    fun wideInitializerInput(): ByteArray = Onnx.model(
        Onnx.graph(
            initializers = listOf(Onnx.floatTensor("w", listOf(1), floatArrayOf(1f))),
            inputs = listOf(input, Onnx.valueInfo("w", Onnx.FLOAT, List(65) { 1 })),
            outputs = listOf(output),
        ),
    ).bytes()

    private val manyNames = List(4_000) { "w$it" }
    private val manyInits get() = manyNames.map { Onnx.floatTensor(it, listOf(1), floatArrayOf(1f)) }
    private val manySparse
        get() = Proto().message(1, Onnx.floatTensor("s", listOf(1), floatArrayOf(1f)))
            .message(2, Onnx.int64Tensor("si", listOf(1), longArrayOf(0))).varint(3, 4)
    private val manyInputs
        get() = manyNames.reversed().map { Onnx.valueInfo(it, Onnx.FLOAT, listOf(1)) } +
            Onnx.valueInfo("s", Onnx.FLOAT, listOf(4)) + input + Onnx.valueInfo("", Onnx.FLOAT, listOf(2))

    /** 4,000 initializer inputs (dense and sparse excluded), then "input" and an unnamed input. */
    fun manyInputs(): ByteArray = Onnx.model(
        Onnx.graph(initializers = manyInits, inputs = manyInputs, outputs = listOf(output)).also {
            it.message(15, manySparse)
        },
    ).bytes()

    /** As [manyInputs] without the unnamed input: one input only, the contract input. */
    fun manyInputsOne(): ByteArray = Onnx.model(
        Onnx.graph(initializers = manyInits, inputs = manyInputs.dropLast(1), outputs = listOf(output))
            .also { it.message(15, manySparse) },
    ).bytes()

    /** Duplicate names: an initializer named like two inputs excludes both; an empty name can be an initializer. */
    fun duplicateNames(): ByteArray = Onnx.model(
        Onnx.graph(
            initializers = listOf(Onnx.floatTensor("", listOf(1), floatArrayOf(1f)), manyInits[0]),
            inputs = listOf(
                Onnx.valueInfo("w0", Onnx.FLOAT, listOf(1)),
                input,
                Onnx.valueInfo("w0", Onnx.FLOAT, listOf(1)),
                Onnx.valueInfo("", Onnx.FLOAT, listOf(1)),
            ),
            outputs = listOf(output),
        ),
    ).bytes()

    /** Names that differ only in the last byte or in length are different. */
    fun nearNames(): ByteArray = Onnx.model(
        Onnx.graph(
            initializers = listOf(Onnx.floatTensor("ab", listOf(1), floatArrayOf(1f))),
            inputs = listOf(
                Onnx.valueInfo("a", Onnx.FLOAT, listOf(1)),
                Onnx.valueInfo("ac", Onnx.FLOAT, listOf(1)),
                Onnx.valueInfo("ab", Onnx.FLOAT, listOf(1)),
            ),
            outputs = listOf(output),
        ),
    ).bytes()

    /** Every model above with its expected verdict (every truncation of [truncationBase] included). */
    fun all(): List<Case> {
        val cases = ArrayList<Case>()
        fun add(group: String, models: Map<String, ByteArray>, expected: String?) {
            for ((label, bytes) in models) cases += Case("$group: $label", bytes, expected)
        }
        add("ORT identifier", ortIdentifier(), NOT_ONNX)
        add("external position", externalAtEveryPosition(), EXTERNAL_DATA)
        add("control", externalControls(), null)
        add("location", locationEncodings(), EXTERNAL_DATA)
        cases += Case("location: data_location 0", locationZero(), null)
        add("duplicate singular", duplicateSingular(), NOT_ONNX)
        cases += Case("repeated: second initializer external", repeatedWithExternal(), EXTERNAL_DATA)
        add("malformed", malformed(), NOT_ONNX)
        val base = truncationBase()
        cases += Case("truncation: none", base, null)
        for (n in 1 until base.size - 1) cases += Case("truncation: $n bytes", base.copyOf(n), ANY)
        cases += Case("truncation: last byte cut", base.copyOf(base.size - 1), NOT_ONNX)
        cases += Case("unknown: group in an unknown payload", groupInUnknownPayload(), null)
        cases += Case("unknown: unknown fields, packed and unpacked", unknownAndPacked(), null)
        cases += Case("depth: attribute at 100", depthChain(100, withTensor = false), null)
        cases += Case("depth: tensor at 101", depthChain(100, withTensor = true), NESTING_TOO_DEEP)
        cases += Case("depth: attribute at 103", depthChain(103, withTensor = false), NESTING_TOO_DEEP)
        cases += Case("contract: merged types", contractMerging(), null)
        cases += Case("contract: oneof", contractOneof(), null)
        cases += Case("contract: oneof member with another wire type", contractOneofWireType(), null)
        cases += Case("limit: 1,024 outputs", outputs(1_024), null)
        cases += Case("limit: 1,025 outputs", outputs(1_025), NOT_ONNX)
        cases += Case("limit: 1,024 opset domains", opsets(1_024), null)
        cases += Case("limit: 1,025 opset domains", opsets(1_025), NOT_ONNX)
        cases += Case("limit: one domain 5,000 times", repeatedOpset(), null)
        cases += Case("limit: 64 dims", outputDims(64), null)
        cases += Case("limit: 65 dims", outputDims(65), NOT_ONNX)
        cases += Case("limit: 65 dims on an initializer input", wideInitializerInput(), NOT_ONNX)
        cases += Case("names: many inputs", manyInputs(), null)
        cases += Case("names: many inputs, one kept", manyInputsOne(), null)
        cases += Case("names: duplicates", duplicateNames(), null)
        cases += Case("names: near", nearNames(), null)
        cases += Case("synthetic YuNet layout", Onnx.yunetModel(640, 480), null)
        return cases
    }
}
