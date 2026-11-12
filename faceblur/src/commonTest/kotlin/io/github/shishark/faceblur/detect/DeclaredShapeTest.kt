// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FakeDetectionImage
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The runtime output shapes against the shapes the model file declares (ADR 0012 decision 4 step 7 and decision 5:
 * names, types and shapes as declared; dynamic row count with K as declared). A transposed or widened output must
 * fail as a shape error at the contract run and on every later pass, never pass with fewer faces.
 */
class DeclaredShapeTest {
    private val scaling = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

    private fun format(
        scores: String? = null,
        scoreColumn: Int = 4,
        boxColumn: Int = 0,
        rows: RowLayout = RowLayout.ROWS,
    ) = DetectionFormat.GenericDetections(
        "det",
        boxColumn,
        scores,
        scoreColumn,
        rows,
        BoxFormat.XYXY,
        CoordinateSpace.NORMALIZED,
        ColorOrder.BGR,
        TensorLayout.NCHW,
        scaling,
    )

    private fun model(
        f: DetectionFormat,
        vararg outputs: List<Any>,
        names: List<String> = listOf("det", "scores"),
    ): DetectorModel {
        val bytes = Onnx.genericModel(
            Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 640, 640)),
            outputs.mapIndexed { i, dims -> Onnx.valueInfo(names[i], Onnx.FLOAT, dims) },
        )
        return DetectorModel.load(
            ModelSource.fromBytes(bytes),
            DetectorProfile(f, 640, 640, 0.5f, ANY_SHA256).pinnedTo(bytes),
        )
    }

    /** Eight rows of five values; row 0 is a face at (0.1, 0.1, 0.3, 0.3) with score 0.9, the rest score 0. */
    private val eightRows = FloatArray(40).also { d ->
        d[0] = 0.1f
        d[1] = 0.1f
        d[2] = 0.3f
        d[3] = 0.3f
        d[4] = 0.9f
    }

    /** A runtime whose output shapes the test can change after the contract run. */
    private class Runner(var data: Map<String, FloatArray>) : TensorRunner {
        override val inputWidth = 640
        override val inputHeight = 640

        override fun initialize() = Unit

        override fun run(input: FloatArray) = data

        override fun close() = Unit
    }

    private fun runtime(runner: Runner, shapes: MutableMap<String, LongArray>) = RunnerRuntime(runner, shapes)

    @Test
    fun aTransposedOrWidenedOutputIsRejectedAtCreation() {
        val static = model(format(), listOf(1, 8, 5))
        val dynamic = model(format(), listOf(1, "n", 5))
        // Control: the declared shape at run time gives the face.
        FaceMasker.forTest(static, FaceMaskerConfig(), runtime(Runner(mapOf("det" to eightRows)), shapes(1, 8, 5)))
            .use { assertEquals(1, it.detect(IntArrayPixels(640, 640)).faces.size, "control") }
        FaceMasker.forTest(dynamic, FaceMaskerConfig(), runtime(Runner(mapOf("det" to eightRows)), shapes(1, 8, 5)))
            .use { assertEquals(1, it.detect(IntArrayPixels(640, 640)).faces.size, "control, symbolic N") }
        val cases =
            listOf(
                Triple(static, shapes(1, 5, 8), "static, transposed"),
                Triple(dynamic, shapes(1, 5, 8), "symbolic N, transposed"),
                Triple(dynamic, shapes(1, 4, 10), "symbolic N, K wider than declared"),
                Triple(dynamic, shapes(1, 10, 4), "symbolic N, K narrower than declared"),
                Triple(static, shapes(8, 5), "rank 2 instead of 3"),
                Triple(static, shapes(1, 1, 8, 5), "rank 4 instead of 3"),
                Triple(static, shapes(1, 8, 5, 1), "static, rank 4 with a trailing 1"),
            )
        for ((m, shape, what) in cases) {
            val e = assertFailsWith<FaceMaskException.ModelRejected>(what) {
                FaceMasker.forTest(m, FaceMaskerConfig(), runtime(Runner(mapOf("det" to eightRows)), shape))
            }
            assertEquals(listOf("CONTRACT_OUTPUT"), e.findings, what)
        }
    }

    /** Five rows of five values; row 0 is a face at (0.1, 0.1, 0.3, 0.3) with score 0.9. */
    private val fiveRows = eightRows.copyOf(25)

    /**
     * Only the rank differs: the model declares [n, 5] (rank 2) and the runtime gives [1, 5, 5] (rank 3, a layout the
     * generic decoder would accept), so every declared dimension is equal position by position. Refused at creation
     * and on a later pass; a comparison that let a larger rank through would accept both.
     */
    @Test
    fun aRuntimeRankAboveTheDeclaredRankIsRefused() {
        val rank2 = model(format(), listOf("n", 5))
        val shapes = shapes(5, 5)
        val masker = FaceMasker.forTest(rank2, FaceMaskerConfig(), runtime(Runner(mapOf("det" to fiveRows)), shapes))
        val img = IntArrayPixels(640, 640, gradientPixels(640, 640))
        assertEquals(1, masker.detect(img).faces.size, "control: the declared rank")
        shapes["det"] = longArrayOf(1, 5, 5)
        val before = img.data.copyOf()
        val pass = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("OUTPUT_SHAPE"), pass.findings)
        assertContentEquals(before, img.data)
        masker.close()
        val e = assertFailsWith<FaceMaskException.ModelRejected> {
            FaceMasker.forTest(rank2, FaceMaskerConfig(), runtime(Runner(mapOf("det" to fiveRows)), shapes(1, 5, 5)))
        }
        assertEquals(listOf("CONTRACT_OUTPUT"), e.findings)
    }

    @Test
    fun aShapeThatChangesAfterCreationFailsThePassWithTheImageUnchanged() {
        val m = model(format(), listOf(1, "n", 5))
        val shapes = shapes(1, 8, 5)
        val masker = FaceMasker.forTest(m, FaceMaskerConfig(), runtime(Runner(mapOf("det" to eightRows)), shapes))
        val img = IntArrayPixels(640, 640, gradientPixels(640, 640))
        val before = img.data.copyOf()
        val badShapes = listOf(
            longArrayOf(1, 5, 8),
            longArrayOf(1, 4, 10),
            longArrayOf(2, 4, 5),
            longArrayOf(40),
            longArrayOf(1, 8, 5, 1),
        )
        for (bad in badShapes) {
            shapes["det"] = bad
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid>(bad.toList().toString()) {
                masker.maskInPlace(img)
            }
            assertEquals(listOf("OUTPUT_SHAPE"), e.findings, bad.toList().toString())
            assertContentEquals(before, img.data)
        }
        // Control: a different row count is allowed where N is symbolic.
        shapes["det"] = longArrayOf(1, 8, 5)
        assertEquals(1, masker.maskInPlace(img).masks.size)
        masker.close()
    }

    @Test
    fun boxesAndScoresWithDifferentRuntimeRowCountsFail() {
        // Both outputs have a symbolic N: only the per-pass equality of N guards the decoder.
        for (rows in listOf(RowLayout.ROWS, RowLayout.COLUMNS)) {
            val boxesDims: List<Any> = if (rows == RowLayout.ROWS) listOf(1, "n", 4) else listOf(1, 4, "n")
            val scoresDims: List<Any> = if (rows == RowLayout.ROWS) listOf(1, "m", 1) else listOf(1, 1, "m")
            val f = format(scores = "scores", scoreColumn = 0, rows = rows)
            val m = model(f, boxesDims, scoresDims)
            fun shape(n: Long, k: Long) = if (rows == RowLayout.ROWS) longArrayOf(1, n, k) else longArrayOf(1, k, n)
            // Two boxes (0.1, 0.1, 0.3, 0.3), laid out by rows or by columns.
            val boxes = FloatArray(8) { if ((if (rows == RowLayout.ROWS) it % 4 else it / 2) < 2) 0.1f else 0.3f }
            val shapes = mutableMapOf("det" to shape(2, 4), "scores" to shape(2, 1))
            val runner = Runner(mapOf("det" to boxes, "scores" to floatArrayOf(0.9f, 0.1f)))
            val masker = FaceMasker.forTest(m, FaceMaskerConfig(), runtime(runner, shapes))
            val img = IntArrayPixels(640, 640, gradientPixels(640, 640))
            assertEquals(1, masker.detect(img).faces.size, "control $rows")
            runner.data = mapOf("det" to boxes, "scores" to floatArrayOf(0.9f, 0.1f, 0.9f))
            shapes["scores"] = shape(3, 1)
            val before = img.data.copyOf()
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid>("$rows") { masker.maskInPlace(img) }
            assertEquals(listOf("OUTPUT_SHAPE"), e.findings, "$rows")
            assertContentEquals(before, img.data)
            masker.close()
        }
    }

    /**
     * The per-pass column checks also hold on their own (a detector without declared shapes): a runtime K too
     * small for the score column, and a box column whose `+ 3` would overflow Int, are OUTPUT_SHAPE, never an index
     * fault.
     */
    @Test
    fun theColumnChecksHoldWithoutDeclaredShapes() {
        fun session(out: Map<String, PassOutput>) = object : ModelSession {
            override val runtimeVersion = "test"
            override val executionProvider = "CPU"
            override val inputs = emptyList<SessionTensor>()
            override val outputs = emptyList<SessionTensor>()

            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ) = out

            override fun close() = Unit
        }
        val boxes = PassOutput(Onnx.FLOAT, longArrayOf(1, 1, 4), floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f))
        val twoScores = PassOutput(Onnx.FLOAT, longArrayOf(1, 1, 2), floatArrayOf(0.1f, 0.9f))
        val scoresColumn1 = format(scores = "scores", scoreColumn = 1)
        val ok = ModelDetector(session(mapOf("det" to boxes, "scores" to twoScores)), profile(scoresColumn1))
        assertEquals(1, ok.detect(FakeDetectionImage(640, 640), 0.5f).size, "control")
        val oneScore = PassOutput(Onnx.FLOAT, longArrayOf(1, 1, 1), floatArrayOf(0.9f))
        val narrow = ModelDetector(session(mapOf("det" to boxes, "scores" to oneScore)), profile(scoresColumn1))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            assertFailsWith<OutputCheckFailed> { narrow.detect(FakeDetectionImage(640, 640), 0.5f) }.findings,
        )
        val huge = format(scores = "scores", scoreColumn = 0, boxColumn = Int.MAX_VALUE - 1)
        val overflow = ModelDetector(session(mapOf("det" to boxes, "scores" to oneScore)), profile(huge))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            assertFailsWith<OutputCheckFailed> { overflow.detect(FakeDetectionImage(640, 640), 0.5f) }.findings,
        )
        // Through the library, the declared K (2^31 + 3) cannot be produced: creation fails as a shape error.
        val big = model(huge, listOf(1, "n", 2_147_483_651L), listOf(1, "n", 1))
        val e = assertFailsWith<FaceMaskException.ModelRejected> {
            FaceMasker.forTest(
                big,
                FaceMaskerConfig(),
                runtime(
                    Runner(mapOf("det" to floatArrayOf(0.1f, 0.1f, 0.3f, 0.3f), "scores" to floatArrayOf(0.9f))),
                    mutableMapOf("det" to longArrayOf(1, 1, 4), "scores" to longArrayOf(1, 1, 1)),
                ),
            )
        }
        assertEquals(listOf("CONTRACT_OUTPUT"), e.findings)
    }

    private fun profile(f: DetectionFormat) = DetectorProfile(f, 640, 640, 0.5f, ANY_SHA256)

    private fun shapes(vararg dims: Long) = mutableMapOf("det" to dims)
}
