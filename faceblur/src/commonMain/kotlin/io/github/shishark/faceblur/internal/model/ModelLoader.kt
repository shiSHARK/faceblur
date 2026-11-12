// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.model

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelInfo
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorInfo
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.isFatal
import kotlin.math.sqrt

/** `DetectorModel.load` (ADR 0012 decision 4, steps 2-4): read, pin, pre-parse, contract of inputs and outputs. */
internal object ModelLoader {
    /** Largest accepted `maxModelBytes`: the largest array size the JVM allocates reliably (2^31 - 9). */
    const val MAX_MODEL_BYTES_LIMIT: Long = 2_147_483_639L

    fun load(
        source: ModelSource,
        profile: DetectorProfile,
        maxModelBytes: Long,
        parser: (ByteArray) -> OnnxDescription = OnnxPreParse::parse,
    ): DetectorModel {
        if (maxModelBytes !in 1L..MAX_MODEL_BYTES_LIMIT) {
            throw FaceMaskException.InvalidConfiguration("maxModelBytes", "1..$MAX_MODEL_BYTES_LIMIT")
        }
        val bytes = read(source, maxModelBytes)
        if (bytes.isEmpty()) throw FaceMaskException.ModelRejected(listOf(EMPTY))
        if (bytes.size.toLong() > maxModelBytes) throw FaceMaskException.ModelRejected(listOf(TOO_LARGE))
        return try {
            check(bytes, profile, parser)
        } catch (e: FaceMaskException) {
            throw e
        } catch (e: Throwable) {
            // ADR 0002 decision 9: a library bug while hashing, walking or checking the untrusted bytes becomes
            // Internal; only the JVM's fatal errors pass unchanged (on iOS an undeclared exception would end the app).
            if (isFatal(e)) throw e
            throw FaceMaskException.Internal("the model check failed unexpectedly", e)
        }
    }

    /** SHA-256 of [bytes] in lower-case hex: the installed platform digest, else the common implementation. */
    fun sha256Hex(bytes: ByteArray): String {
        val platform = FaceBlurRuntime.digest() ?: return Sha256.hex(bytes)
        val d = platform.sha256(bytes)
        check(d.size == SHA256_BYTES) { "the platform digest returned ${d.size} bytes" }
        return Sha256.toHex(d)
    }

    private const val SHA256_BYTES = 32

    /** Steps 3-4: pin, pre-parse and the contract of inputs and outputs on the library-owned [bytes]. */
    private fun check(
        bytes: ByteArray,
        profile: DetectorProfile,
        parser: (ByteArray) -> OnnxDescription,
    ): DetectorModel {
        val sha = sha256Hex(bytes)
        if (profile.expectedSha256 != sha) {
            throw FaceMaskException.ModelIntegrity("the model's SHA-256 differs from the profile's expectedSha256")
        }
        val description = parser(bytes)
        val findings = contractFindings(description, profile)
        if (findings.isNotEmpty()) throw FaceMaskException.ModelRejected(findings)
        val info =
            ModelInfo(
                sha256 = sha,
                sizeBytes = bytes.size.toLong(),
                irVersion = description.irVersion,
                opsetImports = description.opsetImports,
                inputs = description.inputs.map { it.toTensorInfo() },
                outputs = description.outputs.map { it.toTensorInfo() },
                warnings = Warnings.forWorkingSide(profile, Warnings.REFERENCE_WORKING_SIDE),
            )
        return DetectorModel.create(bytes, description, profile, info)
    }

    /**
     * The bytes of [source] in one library-owned array: a source array is size-checked and then copied once; a file
     * is read by the platform's reader, at most [max] + 1 bytes.
     */
    private fun read(source: ModelSource, max: Long): ByteArray {
        val array = source.bytes
        if (array != null) {
            if (array.size.toLong() > max) throw FaceMaskException.ModelRejected(listOf(TOO_LARGE))
            return array.copyOf()
        }
        val path = source.path ?: throw FaceMaskException.IoFailed("no model source")
        val reader =
            FaceBlurRuntime.modelFiles()
                ?: throw FaceMaskException.IoFailed(
                    "reading model files is not available on this platform; use ModelSource.fromBytes",
                )
        return try {
            reader.read(path, max)
        } catch (e: FaceMaskException) {
            throw e
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            // The platform's message may contain the path: it is never chained or copied.
            throw FaceMaskException.IoFailed("the model file could not be read")
        }
    }

    /** The contract of the model's inputs and outputs against [profile] (step 4); empty when it holds. */
    fun contractFindings(d: OnnxDescription, profile: DetectorProfile): List<String> {
        val findings = ArrayList<String>()
        val input = d.inputs.singleOrNull()
        if (input == null) {
            findings += INPUT_COUNT
        } else {
            if (!input.isTensor || input.elementType != FLOAT) findings += INPUT_TYPE
            val layout = (profile.format as? DetectionFormat.GenericDetections)?.tensorLayout ?: TensorLayout.NCHW
            val expected: List<Long> =
                if (layout == TensorLayout.NCHW) {
                    listOf(1L, 3L, profile.inputHeight.toLong(), profile.inputWidth.toLong())
                } else {
                    listOf(1L, profile.inputHeight.toLong(), profile.inputWidth.toLong(), 3L)
                }
            if (!compatible(input.dims, expected)) findings += INPUT_SHAPE
        }
        for (need in requiredOutputs(profile)) {
            val out = d.outputs.lastOrNull { it.name == need.name }
            if (out == null) {
                findings += OUTPUT_MISSING
                continue
            }
            if (!out.isTensor || out.elementType != FLOAT) findings += OUTPUT_TYPE
            if (!need.accepts(out.dims)) findings += OUTPUT_SHAPE
        }
        val format = profile.format
        if (format is DetectionFormat.GenericDetections) {
            val single = format.scoresOutput == null || format.scoresOutput == format.boxesOutput
            if (single && format.scoreColumn >= format.boxColumn && format.scoreColumn <= format.boxColumn + 3) {
                findings += OUTPUT_SHAPE
            }
            if (!single) {
                val boxes = d.outputs.lastOrNull { it.name == format.boxesOutput }?.dims
                val scores = d.outputs.lastOrNull { it.name == format.scoresOutput }?.dims
                val nb = rowCount(boxes, format.rows)
                val ns = rowCount(scores, format.rows)
                if (nb != null && ns != null && nb != ns) findings += OUTPUT_SHAPE
            }
        }
        return findings.distinct()
    }

    /** An output the format needs, with its shape rule. */
    class RequiredOutput(val name: String, val accepts: (List<Long?>?) -> Boolean)

    fun requiredOutputs(profile: DetectorProfile): List<RequiredOutput> = when (val f = profile.format) {
        is DetectionFormat.YuNetLayout -> YuNetShapes.STRIDES.flatMap { s ->
            val n = YuNetShapes.anchors(profile, s).toLong()
            listOf(
                RequiredOutput("cls_$s") { yunetShape(it, n, 1) },
                RequiredOutput("obj_$s") { yunetShape(it, n, 1) },
                RequiredOutput("bbox_$s") { yunetShape(it, n, 4) },
            )
        }

        is DetectionFormat.GenericDetections -> {
            val boxesNeed = f.boxColumn.toLong() + 4
            val single = f.scoresOutput == null || f.scoresOutput == f.boxesOutput
            val boxesK = if (single) maxOf(boxesNeed, f.scoreColumn.toLong() + 1) else boxesNeed
            val list = mutableListOf(RequiredOutput(f.boxesOutput) { k(it, f.rows)?.let { k -> k >= boxesK } == true })
            if (!single) {
                val scoresK = f.scoreColumn.toLong() + 1
                list += RequiredOutput(f.scoresOutput) { k(it, f.rows)?.let { k -> k >= scoresK } == true }
            }
            list
        }
    }

    /** YuNet outputs: [1, N, K] with K static; N and the batch dimension static-or-symbolic, equal when static. */
    private fun yunetShape(dims: List<Long?>?, n: Long, k: Long): Boolean =
        dims != null && dims.size == 3 && dims[2] == k && (dims[0] == null || dims[0] == 1L) &&
            (dims[1] == null || dims[1] == n)

    /** The static column count K of a generic output, or null when the rank or K does not fit. */
    fun k(dims: List<Long?>?, rows: RowLayout): Long? {
        val core = generic2d(dims) ?: return null
        return if (rows == RowLayout.ROWS) core.second else core.first
    }

    private fun rowCount(dims: List<Long?>?, rows: RowLayout): Long? {
        val core = generic2d(dims) ?: return null
        return if (rows == RowLayout.ROWS) core.first else core.second
    }

    /** The two inner dimensions of a rank-2 or rank-3 output ([1, a, b] or [a, b]). */
    private fun generic2d(dims: List<Long?>?): Pair<Long?, Long?>? = when {
        dims == null -> null
        dims.size == 2 -> dims[0] to dims[1]
        dims.size == 3 && (dims[0] == null || dims[0] == 1L) -> dims[1] to dims[2]
        else -> null
    }

    private fun compatible(dims: List<Long?>?, expected: List<Long>): Boolean =
        dims != null && dims.size == expected.size && dims.indices.all { dims[it] == null || dims[it] == expected[it] }

    private fun DeclaredTensor.toTensorInfo() = TensorInfo(
        name,
        if (isTensor) elementTypeName(elementType) else "NOT_A_TENSOR",
        dims ?: emptyList(),
    )

    /** ONNX `TensorProto.DataType` names (the union of onnx 1.16.1 and 1.22.0). */
    fun elementTypeName(type: Int): String = ELEMENT_TYPES.getOrNull(type) ?: "UNKNOWN_$type"

    private val ELEMENT_TYPES = listOf(
        "UNDEFINED", "FLOAT", "UINT8", "INT8", "UINT16", "INT16", "INT32", "INT64", "STRING", "BOOL", "FLOAT16",
        "DOUBLE", "UINT32", "UINT64", "COMPLEX64", "COMPLEX128", "BFLOAT16", "FLOAT8E4M3FN", "FLOAT8E4M3FNUZ",
        "FLOAT8E5M2", "FLOAT8E5M2FNUZ", "UINT4", "INT4", "FLOAT4E2M1", "FLOAT8E8M0", "UINT2", "INT2",
    )

    /** ONNX element type of float32. */
    const val FLOAT = 1

    const val EMPTY = "EMPTY"
    const val TOO_LARGE = "TOO_LARGE"
    const val NOT_REGULAR_FILE = "NOT_REGULAR_FILE"
    const val INPUT_COUNT = "INPUT_COUNT"
    const val INPUT_TYPE = "INPUT_TYPE"
    const val INPUT_SHAPE = "INPUT_SHAPE"
    const val OUTPUT_MISSING = "OUTPUT_MISSING"
    const val OUTPUT_TYPE = "OUTPUT_TYPE"
    const val OUTPUT_SHAPE = "OUTPUT_SHAPE"
}

/** YuNet output geometry: anchors per stride on the input padded to a multiple of 32, as OpenCV computes them. */
internal object YuNetShapes {
    val STRIDES = intArrayOf(8, 16, 32)

    fun anchors(profile: DetectorProfile, stride: Int): Int =
        (profile.inputWidth / stride) * (profile.inputHeight / stride)
}

/** Non-blocking warnings (ADR 0012 decisions 3 and 7). */
internal object Warnings {
    const val SMALL_INPUT = "SMALL_INPUT"

    /**
     * The small-input gap side at the reference working size, from tile geometry (ADR 0012 decision 3): the largest
     * face that lies whole in a tile is about a quarter of the input side s, and the global pass shrinks it to about
     * s² / (4 × working size) px; at 384 px and a 3,500 px working size that is about 10.5 px, the lower end of the
     * face size a small-face detector needs.
     */
    const val SMALL_INPUT_GAP_SIDE = 384.0

    /** The reference working long side of [SMALL_INPUT_GAP_SIDE], and the default file-mode working size. */
    const val REFERENCE_WORKING_SIDE = 3500

    /** Warning codes for a call whose working image has the long side [longSide]. */
    fun forWorkingSide(profile: DetectorProfile, longSide: Int): List<String> {
        val gap = SMALL_INPUT_GAP_SIDE * sqrt(longSide.coerceAtLeast(0) / REFERENCE_WORKING_SIDE.toDouble())
        val smaller = minOf(profile.inputWidth, profile.inputHeight)
        return if (smaller < gap) listOf(SMALL_INPUT) else emptyList()
    }
}
