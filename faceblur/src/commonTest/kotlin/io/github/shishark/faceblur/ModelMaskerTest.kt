// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetProfile
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `FaceMasker(model, config)`: creation steps 5-7, reports, effective expansion and warnings (ADR 0012). */
class ModelMaskerTest {
    private fun image(w: Int = 640, h: Int = 480) = IntArrayPixels(w, h, gradientPixels(w, h))

    /** A runtime whose session wraps [FakeSession] with [change] applied to every run's outputs. */
    private fun changed(
        runner: TensorRunner = OneFaceRunner(),
        change: (Map<String, PassOutput>) -> Map<String, PassOutput>,
    ) = ModelRuntime { model, _ ->
        val base = FakeSession(runner, model)
        object : ModelSession by base {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ) = change(base.run(input, inputShape, outputNames, maxElements, timeoutMillis))
        }
    }

    private fun rejected(runtime: ModelRuntime, model: DetectorModel = testYuNetModel()): List<String> =
        assertFailsWith<FaceMaskException.ModelRejected> {
            FaceMasker.forTest(model, FaceMaskerConfig(), runtime)
        }.findings

    @Test
    fun creationRefusesWhatTheRuntimeOrTheContractRunShows() {
        assertEquals(listOf("RUNTIME_REJECTED"), rejected(ModelRuntime { _, _ -> throw SessionRejected() }))
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            FaceMasker.forTest(
                testYuNetModel(),
                FaceMaskerConfig(),
                ModelRuntime { _, _ ->
                    throw RuntimeOptionFailed()
                },
            )
        }
        // The runtime's view of the inputs and outputs differs from the file (names; types and shapes where reported).
        fun reporting(
            inputs: (List<SessionTensor>) -> List<SessionTensor>,
            outputs: (List<SessionTensor>) -> List<SessionTensor> = { it },
        ) = ModelRuntime { model, _ ->
            val base = FakeSession(OneFaceRunner(), model)
            object : ModelSession by base {
                override val inputs = inputs(base.inputs)
                override val outputs = outputs(base.outputs)
            }
        }
        assertEquals(listOf("IO_MISMATCH"), rejected(reporting({ listOf(SessionTensor("other", 1, it[0].shape)) })))
        assertEquals(listOf("IO_MISMATCH"), rejected(reporting({ listOf(SessionTensor("input", 11, it[0].shape)) })))
        assertEquals(
            listOf("IO_MISMATCH"),
            rejected(
                reporting({
                    listOf(SessionTensor("input", 1, listOf(1L, 3L, 320L, 320L)))
                }),
            ),
        )
        assertEquals(listOf("IO_MISMATCH"), rejected(reporting({ it }, { it.drop(1) })))
        // Names only (as on iOS): accepted when the names match.
        FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(),
            reporting({ l ->
                l.map { SessionTensor(it.name, null, null) }
            }),
        ).close()
        // Contract run: shape, type, values, timeout.
        assertEquals(listOf("CONTRACT_OUTPUT"), rejected(changed { m -> m - "cls_8" }))
        assertEquals(
            listOf("CONTRACT_OUTPUT"),
            rejected(
                changed { m ->
                    m.mapValues { PassOutput(11, it.value.shape, null) }
                },
            ),
        )
        assertEquals(listOf("CONTRACT_VALUES"), rejected(changed(OneFaceRunner(nan = true)) { it }))
        val slow = ModelRuntime { model, _ ->
            object : ModelSession by FakeSession(OneFaceRunner(), model) {
                override fun run(
                    input: FloatArray,
                    inputShape: LongArray,
                    outputNames: List<String>,
                    maxElements: Long,
                    timeoutMillis: Long,
                ): Map<String, PassOutput> {
                    assertEquals(30_000L, timeoutMillis, "the contract run is limited to 30 s")
                    throw SessionTimeout()
                }
            }
        }
        assertEquals(listOf("TIMEOUT"), rejected(slow))
    }

    @Test
    fun aFailedCreationClosesTheSession() {
        var closed = 0
        val runner = object : TensorRunner by OneFaceRunner(nan = true) {
            override fun close() {
                closed++
            }
        }
        rejected(RunnerRuntime(runner))
        assertEquals(1, closed)
    }

    @Test
    fun theRunTimeoutFollowsTheContractRunAndARunTimeoutKeepsTheInstanceUsable() {
        var timeouts = 0
        val seen = ArrayList<Long>()
        val runtime = ModelRuntime { model, _ ->
            val base = FakeSession(OneFaceRunner(), model)
            object : ModelSession by base {
                override fun run(
                    input: FloatArray,
                    inputShape: LongArray,
                    outputNames: List<String>,
                    maxElements: Long,
                    timeoutMillis: Long,
                ): Map<String, PassOutput> {
                    seen += timeoutMillis
                    if (timeouts > 0) {
                        timeouts--
                        throw SessionTimeout()
                    }
                    return base.run(input, inputShape, outputNames, maxElements, timeoutMillis)
                }
            }
        }
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime)
        timeouts = 1
        val img = image()
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        assertContentEquals(before, img.data)
        assertEquals(1, masker.maskInPlace(img).masks.size, "the next call on the same instance succeeds")
        assertEquals(30_000L, seen.first())
        assertTrue(seen.drop(1).all { it >= 2_000L }, "per-pass timeout max(2 s, 20 × the contract run): $seen")
        masker.close()
    }

    @Test
    fun theReportCarriesTheRuntimeAndLibraryVersions() {
        val model = testYuNetModel()
        FaceMasker.forTest(model, FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { m ->
            assertEquals(model.info, m.modelInfo.model)
            assertEquals("test-runtime", m.modelInfo.runtimeVersion)
            assertEquals("CPU", m.modelInfo.executionProvider)
            assertEquals(FaceBlurInfo.VERSION, m.modelInfo.faceBlurVersion)
            assertTrue(m.modelInfo.toString().startsWith("MaskerModelInfo(model=ModelInfo(sha256="))
            assertEquals(m.modelInfo, MaskerModelInfo(model.info, "test-runtime", "CPU", FaceBlurInfo.VERSION))
            assertEquals(
                m.modelInfo.hashCode(),
                MaskerModelInfo(model.info, "test-runtime", "CPU", FaceBlurInfo.VERSION).hashCode(),
            )
        }
    }

    @Test
    fun detectedFacesUseTheLargerOfTheProfileMinimumAndTheMaskingConfig() {
        val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, ANY_SHA256, 2.0f, 2.2f)
        val model = testYuNetModel(profile)
        FaceMasker.forTest(model, FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { m ->
            val d = m.detect(image())
            assertEquals(2.0f, d.masking.expandX)
            assertEquals(2.2f, d.masking.expandY)
            val face = d.faces.single()
            val mask = d.toMaskPlan().masks.single()
            assertEquals(2.0f * face.width / 2f, mask.radiusX, 1e-3f)
            assertEquals(2.2f * face.height / 2f, mask.radiusY, 1e-3f)
            // maskInPlace writes exactly the plan of detect.
            assertEquals(d.toMaskPlan().masks, m.maskInPlace(image()).masks)
            // A manual mask keeps the geometry the app passes (the MaskingConfig default, not the profile minimum).
            val manual = Mask.manual(10f, 10f, 110f, 110f)
            assertEquals(1.6f * 50f, manual.radiusX)
            assertEquals(1.8f * 50f, manual.radiusY)
            assertEquals(manual, Mask.manual(10f, 10f, 110f, 110f, MaskingConfig()))
        }
        // A larger MaskingConfig wins over the profile minimum, per axis.
        FaceMasker.forTest(
            model,
            FaceMaskerConfig(MaskingConfig(expandX = 2.5f, expandY = 2.1f)),
            RunnerRuntime(OneFaceRunner()),
        ).use { m ->
            val d = m.detect(image())
            assertEquals(2.5f, d.masking.expandX)
            assertEquals(2.2f, d.masking.expandY)
        }
        // The default profile minimum equals the default MaskingConfig: detected faces get 1.6 / 1.8.
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { m ->
            val d = m.detect(image())
            assertEquals(1.6f, d.masking.expandX)
            assertEquals(1.8f, d.masking.expandY)
        }
    }

    @Test
    fun resultsCarryWarningsForTheirOwnWorkingSize() {
        // A 300 × 300 YuNet-layout profile is not a multiple of 32; use a generic 300 × 300 model with one face row.
        val scaling = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val format = DetectionFormat.GenericDetections(
            "det",
            0,
            null,
            4,
            RowLayout.ROWS,
            BoxFormat.XYXY,
            CoordinateSpace.INPUT_PIXELS,
            ColorOrder.BGR,
            TensorLayout.NCHW,
            scaling,
        )
        val profile = DetectorProfile(format, 300, 300, 0.5f, ANY_SHA256)
        val bytes = Onnx.genericModel(
            Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 300, 300)),
            listOf(Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, 1, 5))),
        )
        val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes))
        assertEquals(listOf("SMALL_INPUT"), model.info.warnings, "computed for 3,500 px")
        val runner = object : TensorRunner {
            override val inputWidth = 300
            override val inputHeight = 300

            override fun initialize() = Unit

            override fun close() = Unit

            override fun run(input: FloatArray) = mapOf("det" to floatArrayOf(10f, 10f, 60f, 60f, 0.9f))
        }
        val runtime = RunnerRuntime(runner, mapOf("det" to longArrayOf(1, 1, 5)))
        FaceMasker.forTest(model, FaceMaskerConfig(), runtime).use { m ->
            val big = m.detect(IntArrayPixels(3500, 100))
            assertEquals(listOf("SMALL_INPUT"), big.warnings)
            assertEquals(emptyList(), m.detect(IntArrayPixels(1750, 100)).warnings, "gap side about 271 px")
            assertEquals(listOf("SMALL_INPUT"), m.maskInPlace(IntArrayPixels(3500, 100)).warnings)
            val applied = m.applyMasks(IntArrayPixels(3500, 100), big.toMaskPlan())
            assertEquals(emptyList(), applied.warnings, "no detection ran")
        }
    }
}
