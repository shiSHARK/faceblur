// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.model

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.testmodel.HostileModels
import io.github.shishark.faceblur.testmodel.Onnx
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pre-parse walker (ADR 0012 decision 4 step 4). Every model here is generated at test time. */
class OnnxPreParseTest {
    private fun codes(bytes: ByteArray): List<String> =
        assertFailsWith<FaceMaskException.ModelRejected> { OnnxPreParse.parse(bytes) }.findings

    @Test
    fun aValidModelIsReadWithItsInputsOutputsAndReport() {
        val d = OnnxPreParse.parse(Onnx.yunetModel(640, 480))
        assertEquals(8, d.irVersion)
        assertEquals(mapOf("" to 13L), d.opsetImports)
        assertEquals(listOf("input"), d.inputs.map { it.name })
        assertEquals(listOf(1L, 3L, 480L, 640L), d.inputs.single().dims)
        assertEquals(12, d.outputs.size)
        assertEquals(listOf(1L, 300L, 4L), d.outputs.single { it.name == "bbox_32" }.dims)
    }

    @Test
    fun theOrtFormatIdentifierIsRefusedEvenInAValidOnnxFile() {
        for ((label, bytes) in HostileModels.ortIdentifier()) assertEquals(listOf("NOT_ONNX"), codes(bytes), label)
        OnnxPreParse.parse(Onnx.model(HostileModels.graphWith()).bytes()) // control
    }

    /** One model per carrier position of ADR 0012 decision 4 step 4 item 2, each with an external tensor. */
    @Test
    fun externalDataIsRefusedAtEveryPosition() {
        val cases = HostileModels.externalAtEveryPosition()
        assertEquals(14, cases.size)
        for ((name, bytes) in cases) assertEquals(listOf("EXTERNAL_DATA"), codes(bytes), name)
        // Control: the same structures with plain tensors load.
        for (bytes in HostileModels.externalControls().values) OnnxPreParse.parse(bytes)
    }

    @Test
    fun theRefusalPredicateCoversEveryEncodingOfTheLocation() {
        val cases = HostileModels.locationEncodings()
        assertEquals(8, cases.size)
        for ((label, bytes) in cases) assertEquals(listOf("EXTERNAL_DATA"), codes(bytes), label)
        // data_location 0 alone is the default and loads.
        OnnxPreParse.parse(HostileModels.locationZero())
    }

    @Test
    fun aRepeatedSingularMessageIsRefusedBecauseProtobufWouldMergeIt() {
        val cases = HostileModels.duplicateSingular()
        assertEquals(8, cases.size)
        for ((label, bytes) in cases) assertEquals(listOf("NOT_ONNX"), codes(bytes), label)
        // Repeated fields are walked in every occurrence: the second initializer is the external one.
        assertEquals(listOf("EXTERNAL_DATA"), codes(HostileModels.repeatedWithExternal()))
    }

    @Test
    fun malformedProtobufIsNotOnnxAndNeverCrashes() {
        val good = HostileModels.truncationBase()
        // Every truncation of a valid model.
        for (n in 1 until good.size) {
            val cut = good.copyOf(n)
            val result = runCatching { OnnxPreParse.parse(cut) }
            val e = result.exceptionOrNull()
            assertTrue(e == null || e is FaceMaskException.ModelRejected, "cut at $n: $e")
        }
        assertEquals(listOf("NOT_ONNX"), codes(good.copyOf(good.size - 1)))
        val cases = HostileModels.malformed()
        assertEquals(12, cases.size)
        for ((label, bytes) in cases) assertEquals(listOf("NOT_ONNX"), codes(bytes), label)
        // The payload of an unknown length-delimited field is not parsed (as in protobuf): it is skipped as bytes.
        OnnxPreParse.parse(HostileModels.groupInUnknownPayload())
    }

    @Test
    fun unknownFieldsAreSkippedAndRepeatedNumbersArePackedOrUnpacked() {
        val d = OnnxPreParse.parse(HostileModels.unknownAndPacked())
        assertEquals(listOf("input"), d.inputs.map { it.name })
    }

    @Test
    fun nestingDeeperThan100LevelsIsRefused() {
        // Levels: model 1, graph 2, node 3, attribute 4, graph 5, ... An attribute is at level 4 + 3k.
        OnnxPreParse.parse(HostileModels.depthChain(100, withTensor = false)) // the attribute at level 100 is accepted
        // Its tensor is level 101.
        assertEquals(listOf("NESTING_TOO_DEEP"), codes(HostileModels.depthChain(100, withTensor = true)))
        assertEquals(listOf("NESTING_TOO_DEEP"), codes(HostileModels.depthChain(103, withTensor = false)))
    }

    @Test
    fun theContractFieldsFollowTheRuntimeParser() {
        // elem_type twice (last wins), the shape in two `type` occurrences (merged: dims appended), a dim whose oneof
        // ends with dim_param (symbolic), a graph input that is an initializer (not a model input), a sparse one too.
        val d = OnnxPreParse.parse(HostileModels.contractMerging())
        val image = d.inputs.single()
        assertEquals("image", image.name)
        assertEquals(Onnx.FLOAT, image.elementType)
        assertEquals(listOf(1L, 3L, null, 640L), image.dims)
        // A later non-tensor value type replaces the tensor type (oneof), a later tensor type starts afresh.
        val d2 = OnnxPreParse.parse(HostileModels.contractOneof())
        assertEquals(listOf(false, true), d2.inputs.map { it.isTensor })
        assertEquals(listOf(3L), d2.inputs[1].dims)
        // A oneof member with another wire type is an unknown field for protobuf: the tensor type stays and merges.
        val d3 = OnnxPreParse.parse(HostileModels.contractOneofWireType())
        assertEquals(listOf(true, true), d3.inputs.map { it.isTensor })
        assertEquals(listOf(listOf(1L, 3L), listOf(3L, 640L)), d3.inputs.map { it.dims })
    }

    /** The field table equals the union of onnx 1.16.1 and 1.22.0 for the carrier messages (checked by hand). */
    @Test
    fun theFieldTableIsPinned() {
        fun table(m: Message) = m.fields.entries.sortedBy { it.key }.joinToString(" ") { (n, f) ->
            "$n:" + when (f.kind) {
                Kind.MESSAGE -> f.message!!.name + if (f.repeated) "*" else "!"
                Kind.SCALAR -> "s" + f.wireTypes.single()
                Kind.REPEATED_SCALAR -> "r" + (f.wireTypes - WIRE_LEN).single()
                Kind.BYTES -> "b"
            }
        }
        val expected = mapOf(
            "ModelProto" to
                "1:s0 2:b 3:b 4:b 5:s0 6:b 7:GraphProto! 8:b 14:b 20:TrainingInfoProto* 25:FunctionProto* 26:b",
            "GraphProto" to "1:NodeProto* 2:b 5:TensorProto* 10:b 11:b 12:b 13:b 14:b 15:SparseTensorProto* 16:b",
            "NodeProto" to "1:b 2:b 3:b 4:b 5:AttributeProto* 6:b 7:b 8:b 9:b 10:b",
            "AttributeProto" to "1:b 2:s5 3:s0 4:b 5:TensorProto! 6:GraphProto! 7:r5 8:r0 9:b 10:TensorProto* " +
                "11:GraphProto* 13:b 14:b 15:b 20:s0 21:b 22:SparseTensorProto! 23:SparseTensorProto*",
            "TensorProto" to "1:r0 2:s0 3:b 4:r5 5:r0 6:b 7:r0 8:b 9:b 10:r1 11:r0 12:b 13:b 14:s0 16:b",
            "SparseTensorProto" to "1:TensorProto! 2:TensorProto! 3:r0",
            "FunctionProto" to "1:b 4:b 5:b 6:b 7:NodeProto* 8:b 9:b 10:b 11:AttributeProto* 12:b 13:b 14:b",
            "TrainingInfoProto" to "1:GraphProto! 2:GraphProto! 3:b 4:b",
        )
        assertEquals(expected, Fields.ALL.associate { it.name to table(it) })
    }

    /** Fixed-seed fuzzing for crash-freedom: every mutation either parses or is refused with a finding code. */
    @Test
    fun randomMutationsNeverCrashTheWalker() {
        val base = Onnx.yunetModel()
        val random = Random(20261102)
        repeat(3_000) { round ->
            val m = base.copyOf()
            repeat(1 + random.nextInt(4)) {
                when (random.nextInt(3)) {
                    0 -> m[random.nextInt(m.size)] = random.nextInt(256).toByte()
                    1 -> m[random.nextInt(m.size)] = 0x80.toByte()
                    else -> m[random.nextInt(m.size)] = (m[random.nextInt(m.size)].toInt() xor 0x08).toByte()
                }
            }
            val e = runCatching { OnnxPreParse.parse(m) }.exceptionOrNull()
            assertTrue(e == null || e is FaceMaskException.ModelRejected, "round $round: $e")
        }
    }

    /** The limits on the description (outputs, opset domains, dims) refuse beyond, accept at the limit. */
    @Test
    fun theDescriptionLimitsRefuseOnlyBeyondThem() {
        assertEquals(1_024, OnnxPreParse.parse(HostileModels.outputs(1_024)).outputs.size)
        assertEquals(listOf("NOT_ONNX"), codes(HostileModels.outputs(1_025)))
        assertEquals(1_024, OnnxPreParse.parse(HostileModels.opsets(1_024)).opsetImports.size)
        assertEquals(listOf("NOT_ONNX"), codes(HostileModels.opsets(1_025)))
        // The same domain repeated is one domain (last version wins).
        assertEquals(mapOf("" to 17L), OnnxPreParse.parse(HostileModels.repeatedOpset()).opsetImports)
        assertEquals(64, OnnxPreParse.parse(HostileModels.outputDims(64)).outputs.single().dims!!.size)
        assertEquals(listOf("NOT_ONNX"), codes(HostileModels.outputDims(65)))
        // Also on an input, and on an input that is an initializer (each input is checked).
        assertEquals(listOf("NOT_ONNX"), codes(HostileModels.wideInitializerInput()))
    }

    /** Many inputs: initializer inputs are matched by name; only the first two other inputs are kept. */
    @Test
    fun manyInputsAreMatchedByNameAndOnlyTwoOthersAreKept() {
        val d = OnnxPreParse.parse(HostileModels.manyInputs())
        assertEquals(listOf("input", ""), d.inputs.map { it.name }, "initializers (dense and sparse) excluded")
        // One input only: the contract input.
        assertEquals(listOf("input"), OnnxPreParse.parse(HostileModels.manyInputsOne()).inputs.map { it.name })
        // Duplicate names: an initializer named like two inputs excludes both; an empty name can be an initializer.
        assertEquals(listOf("input"), OnnxPreParse.parse(HostileModels.duplicateNames()).inputs.map { it.name })
        // Names that differ only in the last byte or in length are different.
        assertEquals(listOf("a", "ac"), OnnxPreParse.parse(HostileModels.nearNames()).inputs.map { it.name })
    }

    /** Every model of the shared hostile set gets its expected verdict (the set the JVM differential uses). */
    @Test
    fun theSharedHostileSetGetsItsExpectedVerdicts() {
        val cases = HostileModels.all()
        for (c in cases) {
            val result = runCatching { OnnxPreParse.parse(c.bytes) }
            val e = result.exceptionOrNull()
            val actual = if (e ==
                null
            ) {
                null
            } else {
                assertIs<FaceMaskException.ModelRejected>(e, c.label).findings.single()
            }
            if (c.expected != HostileModels.ANY) assertEquals(c.expected, actual, c.label)
        }
        assertTrue(cases.count { it.expected == null } >= 15 && cases.count { it.expected == "EXTERNAL_DATA" } == 23)
    }
}
