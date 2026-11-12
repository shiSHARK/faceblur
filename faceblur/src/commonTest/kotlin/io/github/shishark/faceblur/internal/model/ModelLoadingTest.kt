// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.model

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.Proto
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `DetectorProfile`, `PixelScaling`, `DetectorModel.load`: ranges, read, pin, contract, report, warnings. */
class ModelLoadingTest {
    private val yunet = DetectionFormat.YuNetLayout()
    private val scaling = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

    private fun generic(
        boxes: String = "det",
        boxColumn: Int = 0,
        scores: String? = null,
        scoreColumn: Int = 4,
        rows: RowLayout = RowLayout.ROWS,
        layout: TensorLayout = TensorLayout.NCHW,
        nms: Boolean = false,
    ) = DetectionFormat.GenericDetections(
        boxes,
        boxColumn,
        scores,
        scoreColumn,
        rows,
        BoxFormat.XYXY,
        CoordinateSpace.INPUT_PIXELS,
        ColorOrder.RGB,
        layout,
        scaling,
        nms,
    )

    private fun parameter(block: () -> Any): String =
        assertFailsWith<FaceMaskException.InvalidConfiguration> { block() }.parameter

    @Test
    fun everyProfileBoundIsCheckedWithTheNextValueOutside() {
        assertEquals("scoreThreshold", parameter { DetectorProfile(yunet, 640, 640, 0f, ANY_SHA256) })
        assertEquals("scoreThreshold", parameter { DetectorProfile(yunet, 640, 640, 1f, ANY_SHA256) })
        assertEquals("scoreThreshold", parameter { DetectorProfile(yunet, 640, 640, Float.NaN, ANY_SHA256) })
        assertEquals("scoreThreshold", parameter { DetectorProfile(yunet, 640, 640, -0.1f, ANY_SHA256) })
        assertEquals("scoreThreshold", parameter { DetectorProfile(yunet, 640, 640, 1.2f, ANY_SHA256) })
        assertEquals(
            "scoreThreshold",
            parameter {
                DetectorProfile(yunet, 640, 640, Float.POSITIVE_INFINITY, ANY_SHA256)
            },
        )
        DetectorProfile(yunet, 640, 640, Float.MIN_VALUE, ANY_SHA256)
        DetectorProfile(yunet, 640, 640, 1f.nextDown(), ANY_SHA256)
        DetectorProfile(yunet, 640, 640, 0.001f, ANY_SHA256)
        DetectorProfile(yunet, 640, 640, 0.999f, ANY_SHA256)
        assertEquals("inputWidth", parameter { DetectorProfile(generic(), 127, 640, 0.5f, ANY_SHA256) })
        assertEquals("inputWidth", parameter { DetectorProfile(generic(), 1281, 640, 0.5f, ANY_SHA256) })
        assertEquals("inputHeight", parameter { DetectorProfile(generic(), 640, 127, 0.5f, ANY_SHA256) })
        assertEquals("inputHeight", parameter { DetectorProfile(generic(), 640, 1281, 0.5f, ANY_SHA256) })
        DetectorProfile(generic(), 128, 1280, 0.5f, ANY_SHA256)
        DetectorProfile(generic(), 1280, 128, 0.5f, ANY_SHA256)
        DetectorProfile(generic(), 641, 479, 0.5f, ANY_SHA256)
        assertEquals(
            "inputWidth",
            parameter {
                DetectorProfile(yunet, 641, 640, 0.5f, ANY_SHA256)
            },
            "YuNet sides are multiples of 32",
        )
        assertEquals("inputHeight", parameter { DetectorProfile(yunet, 640, 470, 0.5f, ANY_SHA256) })
        DetectorProfile(yunet, 128, 1280, 0.5f, ANY_SHA256)
        assertEquals(
            "minMaskExpandX",
            parameter {
                DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 1.6f.nextDown())
            },
        )
        assertEquals("minMaskExpandX", parameter { DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 3.01f) })
        assertEquals(
            "minMaskExpandY",
            parameter {
                DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 1.6f, 1.8f.nextDown())
            },
        )
        assertEquals(
            "minMaskExpandY",
            parameter {
                DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 1.6f, 3f.nextUp())
            },
        )
        assertEquals("minMaskExpandX", parameter { DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, Float.NaN) })
        DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 3f, 3f)
        val sha = "0123456789abcdefABCDEF0123456789abcdef0123456789abcdef0123456789"
        DetectorProfile(yunet, 640, 640, 0.5f, expectedSha256 = sha)
        assertEquals(
            "expectedSha256",
            parameter {
                DetectorProfile(yunet, 640, 640, 0.5f, expectedSha256 = sha.drop(1))
            },
        )
        assertEquals(
            "expectedSha256",
            parameter {
                DetectorProfile(
                    yunet,
                    640,
                    640,
                    0.5f,
                    expectedSha256 =
                    sha.dropLast(1) + "g",
                )
            },
        )
        assertEquals("expectedSha256", parameter { DetectorProfile(yunet, 640, 640, 0.5f, expectedSha256 = "$sha ") })
        // ADR 0002: empty, 63 (above) and 65 digits, a prefix, and characters around 64 digits are all refused.
        val refused = listOf(
            "" to "empty",
            "${sha}0" to "65 digits",
            "sha256:$sha" to "prefix 'sha256:'",
            "0x${sha.drop(2)}" to "prefix '0x' (64 characters)",
            " ${sha.drop(1)}" to "leading space (64 characters)",
            "$sha\n" to "trailing line break",
            "${sha.take(32)} ${sha.drop(33)}" to "space inside (64 characters)",
        )
        for ((value, what) in refused) {
            assertEquals(
                "expectedSha256",
                parameter { DetectorProfile(yunet, 640, 640, 0.5f, expectedSha256 = value) },
                what,
            )
        }
    }

    @Test
    fun theJavaBuilderHasNoDefaultForTheRequiredValues() {
        assertEquals("format", parameter { DetectorProfile.Builder().build() })
        assertEquals("inputWidth", parameter { DetectorProfile.Builder().format(yunet).build() })
        assertEquals(
            "scoreThreshold",
            parameter { DetectorProfile.Builder().format(yunet).inputSize(640, 640).build() },
        )
        assertEquals(
            "expectedSha256",
            parameter { DetectorProfile.Builder().format(yunet).inputSize(640, 640).scoreThreshold(0.5f).build() },
            "the pin is required",
        )
        val built = DetectorProfile.Builder().format(yunet).inputSize(640, 640).scoreThreshold(0.5f)
            .minMaskExpandX(2.0f).minMaskExpandY(2.2f).expectedSha256(ANY_SHA256).build()
        assertEquals(DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 2.0f, 2.2f), built)
        assertEquals(built.hashCode(), DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256, 2.0f, 2.2f).hashCode())
        assertNotEquals(built, DetectorProfile(yunet, 640, 640, 0.5f, ANY_SHA256))
        assertTrue(built.toString().contains("scoreThreshold=0.5"))
    }

    @Test
    fun genericFormatAndScalingValuesAreChecked() {
        assertEquals("boxesOutput", parameter { generic(boxes = "") })
        assertEquals("scoresOutput", parameter { generic(scores = "") })
        assertEquals("boxColumn", parameter { generic(boxColumn = -1) })
        assertEquals("scoreColumn", parameter { generic(scoreColumn = -1) })
        assertEquals("divisor", parameter { PixelScaling(0f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f)) })
        assertEquals(
            "divisor",
            parameter {
                PixelScaling(Float.NaN, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
            },
        )
        assertEquals("mean", parameter { PixelScaling(1f, floatArrayOf(0f, 0f), floatArrayOf(1f, 1f, 1f)) })
        assertEquals("mean", parameter { PixelScaling(1f, floatArrayOf(0f, Float.NaN, 0f), floatArrayOf(1f, 1f, 1f)) })
        assertEquals("std", parameter { PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 1f)) })
        assertEquals("std", parameter { PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, -1f, 1f)) })
        // The arrays are copied: later changes change nothing.
        val mean = floatArrayOf(127f, 127f, 127f)
        val std = floatArrayOf(128f, 128f, 128f)
        val s = PixelScaling(1f, mean, std)
        mean[0] = 0f
        std[1] = 1f
        assertEquals(listOf(127f, 127f, 127f), s.mean)
        assertEquals(listOf(128f, 128f, 128f), s.std)
        assertEquals(s, PixelScaling(1f, floatArrayOf(127f, 127f, 127f), floatArrayOf(128f, 128f, 128f)))
        assertEquals(generic(), generic())
        assertEquals(generic().hashCode(), generic().hashCode())
        assertNotEquals(generic(), generic(nms = true))
        assertEquals(DetectionFormat.YuNetLayout(), yunet)
        assertTrue(generic().toString().startsWith("GenericDetections("))
    }

    @Test
    fun theSizeCapIsInclusiveAndChecked() {
        val model = Onnx.yunetModel()
        assertEquals("maxModelBytes", parameter { loadPinned(model, yunetProfile(), 0) })
        assertEquals(
            "maxModelBytes",
            parameter {
                loadPinned(model, yunetProfile(), 2_147_483_640L)
            },
        )
        loadPinned(model, yunetProfile(), model.size.toLong())
        val tooLarge = assertFailsWith<FaceMaskException.ModelRejected> {
            loadPinned(model, yunetProfile(), model.size - 1L)
        }
        assertEquals(listOf("TOO_LARGE"), tooLarge.findings)
        val empty =
            assertFailsWith<FaceMaskException.ModelRejected> {
                loadPinned(ByteArray(0), yunetProfile())
            }
        assertEquals(listOf("EMPTY"), empty.findings)
        assertTrue(empty.message!!.endsWith("EMPTY"))
    }

    @Test
    fun aPinIsCheckedBeforeParsingAndIgnoresCase() {
        val model = Onnx.yunetModel()
        val sha = Sha256.hex(model)
        var parsed = 0
        val counting: (ByteArray) -> OnnxDescription = {
            parsed++
            OnnxPreParse.parse(it)
        }
        val wrong = sha.replaceRange(0, 1, if (sha[0] == '0') "1" else "0")
        assertFailsWith<FaceMaskException.ModelIntegrity> {
            ModelLoader.load(ModelSource.fromBytes(model), yunetProfile(sha = wrong), 1_000_000, counting)
        }
        assertEquals(0, parsed, "the parser never saw a model that failed its pin")
        val loaded = ModelLoader.load(
            ModelSource.fromBytes(model),
            yunetProfile(sha = sha.uppercase()),
            1_000_000,
            counting,
        )
        assertEquals(1, parsed)
        assertEquals(sha, loaded.info.sha256)
        assertEquals(model.size.toLong(), loaded.info.sizeBytes)
        // Order: size cap, then hash, then pre-parse, then runtime. A model over the cap is not hashed against the
        // pin (TOO_LARGE, not ModelIntegrity); a model with another hash reaches neither the parser nor a runtime.
        parsed = 0
        assertEquals(
            listOf("TOO_LARGE"),
            assertFailsWith<FaceMaskException.ModelRejected> {
                ModelLoader.load(ModelSource.fromBytes(model), yunetProfile(sha = wrong), model.size - 1L, counting)
            }.findings,
        )
        val runtime = io.github.shishark.faceblur.detect.RunnerRuntime(io.github.shishark.faceblur.OneFaceRunner())
        assertFailsWith<FaceMaskException.ModelIntegrity> {
            val m = ModelLoader.load(ModelSource.fromBytes(model), yunetProfile(sha = wrong), 1_000_000, counting)
            io.github.shishark.faceblur.FaceMasker.forTest(m, io.github.shishark.faceblur.FaceMaskerConfig(), runtime)
        }
        assertEquals(0, parsed, "parser counter")
        assertEquals(0, runtime.opened, "runtime counter")
        // One changed byte anywhere is a different model.
        val changed = model.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<FaceMaskException.ModelIntegrity> {
            ModelLoader.load(ModelSource.fromBytes(changed), yunetProfile(sha = sha), 1_000_000, counting)
        }
        assertEquals(0, parsed)
    }

    @Test
    fun theBytesAreCopiedOnceAndLaterChangesChangeNothing() {
        val model = Onnx.yunetModel()
        val loaded = loadPinned(model, yunetProfile())
        val sha = loaded.info.sha256
        val snapshot = loaded.verifiedBytes().copyOf()
        model.fill(0)
        assertEquals(sha, loaded.info.sha256)
        assertContentEquals(snapshot, loaded.verifiedBytes())
        assertEquals(sha, Sha256.hex(loaded.verifiedBytes()))
        // The platform form is made once and shared.
        var made = 0
        val a = loaded.platformForm {
            made++
            it.size
        }
        val b = loaded.platformForm {
            made++
            -1
        }
        assertEquals(a, b)
        assertEquals(1, made)
    }

    @Test
    fun aFileSourceWithoutAPlatformReaderFailsWithoutThePath() {
        FaceBlurRuntime.installModelFiles(null)
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE) // no reader at all, also on a platform with a default
        val e =
            try {
                assertFailsWith<FaceMaskException.IoFailed> {
                    DetectorModel.load(ModelSource.fromFile("/canary/secret-model-name.onnx"), yunetProfile())
                }
            } finally {
                FaceBlurRuntime.overrideDefaults(null)
            }
        assertTrue("canary" !in e.message!! && "secret" !in e.message!!, e.message)
        // A reader failure that is not typed becomes IoFailed without the reader's text or cause.
        FaceBlurRuntime.installModelFiles { path, _ -> throw IllegalStateException("cannot open $path") }
        try {
            val f = assertFailsWith<FaceMaskException.IoFailed> {
                DetectorModel.load(ModelSource.fromFile("/canary/secret-model-name.onnx"), yunetProfile())
            }
            assertTrue("canary" !in f.message!!, f.message)
            assertEquals(null, f.cause)
            FaceBlurRuntime.installModelFiles { _, max -> ByteArray(max.toInt() + 1) }
            val big = assertFailsWith<FaceMaskException.ModelRejected> {
                DetectorModel.load(ModelSource.fromFile("x"), yunetProfile(), 10)
            }
            assertEquals(listOf("TOO_LARGE"), big.findings)
            val model = Onnx.yunetModel()
            FaceBlurRuntime.installModelFiles { _, _ -> model }
            assertEquals(
                loadPinned(model, yunetProfile()).info,
                DetectorModel.load(ModelSource.fromFile("x"), yunetProfile()).info,
                "bytes and file give the same report",
            )
        } finally {
            FaceBlurRuntime.installModelFiles(null)
        }
        assertEquals("ModelSource(file)", ModelSource.fromFile("/canary").toString())
        assertEquals("ModelSource(bytes)", ModelSource.fromBytes(ByteArray(1)).toString())
    }

    private fun load(bytes: ByteArray, profile: DetectorProfile): List<String> =
        assertFailsWith<FaceMaskException.ModelRejected> {
            loadPinned(bytes, profile)
        }.findings

    /** The YuNet profile, pinned by default to the generated YuNet-layout model of that size. */
    private fun yunetProfile(w: Int = 640, h: Int = 640, sha: String = Sha256.hex(Onnx.yunetModel(w, h))) =
        DetectorProfile(yunet, w, h, 0.5f, expectedSha256 = sha)

    /** Loads [bytes] with [profile] pinned to them (the test approves the model it built). */
    private fun loadPinned(
        bytes: ByteArray,
        profile: DetectorProfile,
        maxModelBytes: Long = DetectorModel.DEFAULT_MAX_MODEL_BYTES,
    ): DetectorModel = DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes), maxModelBytes)

    private val floatInput = Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 640, 640))

    @Test
    fun theInputContractNamesTheDeclarationThatFailed() {
        fun withInputs(vararg inputs: Proto): ByteArray {
            val out = ArrayList<Proto>()
            for (s in intArrayOf(8, 16, 32)) {
                val n = (640 / s) * (640 / s)
                out += Onnx.valueInfo("cls_$s", Onnx.FLOAT, listOf(1, n, 1))
                out += Onnx.valueInfo("obj_$s", Onnx.FLOAT, listOf(1, n, 1))
                out += Onnx.valueInfo("bbox_$s", Onnx.FLOAT, listOf(1, n, 4))
            }
            return Onnx.model(Onnx.graph(inputs = inputs.toList(), outputs = out)).bytes()
        }
        assertEquals(listOf("INPUT_COUNT"), load(withInputs(), yunetProfile()))
        assertEquals(
            listOf("INPUT_COUNT"),
            load(withInputs(floatInput, Onnx.valueInfo("b", Onnx.FLOAT, listOf(1))), yunetProfile()),
        )
        assertEquals(
            listOf("INPUT_TYPE"),
            load(withInputs(Onnx.valueInfo("input", Onnx.DOUBLE, listOf(1, 3, 640, 640))), yunetProfile()),
        )
        assertEquals(
            listOf("INPUT_SHAPE"),
            load(withInputs(Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 640))), yunetProfile()),
        )
        assertEquals(
            listOf("INPUT_SHAPE"),
            load(withInputs(Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 480, 640))), yunetProfile()),
        )
        assertEquals(
            listOf("INPUT_SHAPE"),
            load(withInputs(Onnx.valueInfo("input", Onnx.FLOAT, listOf(2, 3, 640, 640))), yunetProfile()),
        )
        assertEquals(listOf("INPUT_SHAPE"), load(withInputs(Onnx.valueInfo("input", Onnx.FLOAT, null)), yunetProfile()))
        // Symbolic dimensions accept the profile's size; an initializer input is not counted.
        loadPinned(
            withInputs(Onnx.valueInfo("input", Onnx.FLOAT, listOf("n", 3, "h", "w"))),
            yunetProfile(),
        )
        val withInit = Onnx.model(
            Onnx.graph(
                initializers = listOf(Onnx.floatTensor("w", listOf(1), floatArrayOf(1f))),
                inputs = listOf(floatInput, Onnx.valueInfo("w", Onnx.FLOAT, listOf(1))),
                outputs = OnnxPreParse.parse(withInputs(floatInput)).outputs.map {
                    Onnx.valueInfo(it.name, Onnx.FLOAT, it.dims!!.map { d -> d!! })
                },
            ),
        ).bytes()
        assertEquals(
            listOf("input"),
            loadPinned(withInit, yunetProfile()).info.inputs.map {
                it.name
            },
        )
    }

    @Test
    fun theOutputContractNamesTheDeclarationThatFailed() {
        val missing = Onnx.model(
            Onnx.graph(
                inputs = listOf(floatInput),
                outputs = listOf(Onnx.valueInfo("cls_8", Onnx.FLOAT, listOf(1, 6400, 1))),
            ),
        ).bytes()
        assertEquals(listOf("OUTPUT_MISSING"), load(missing, yunetProfile()))
        val wrongType = Onnx.yunetModel().let {
            Onnx.model(
                Onnx.graph(
                    inputs = listOf(floatInput),
                    outputs = OnnxPreParse.parse(it).outputs.map { o ->
                        Onnx.valueInfo(
                            o.name,
                            if (o.name ==
                                "obj_16"
                            ) {
                                Onnx.DOUBLE
                            } else {
                                Onnx.FLOAT
                            },
                            o.dims!!.map { d -> d!! },
                        )
                    },
                ),
            ).bytes()
        }
        assertEquals(listOf("OUTPUT_TYPE"), load(wrongType, yunetProfile()))
        val wrongShape = Onnx.yunetModel().let {
            Onnx.model(
                Onnx.graph(
                    inputs = listOf(floatInput),
                    outputs = OnnxPreParse.parse(it).outputs.map { o ->
                        Onnx.valueInfo(
                            o.name,
                            Onnx.FLOAT,
                            if (o.name ==
                                "bbox_8"
                            ) {
                                listOf(1, 6400, 5)
                            } else {
                                o.dims!!.map { d -> d!! }
                            },
                        )
                    },
                ),
            ).bytes()
        }
        assertEquals(listOf("OUTPUT_SHAPE"), load(wrongShape, yunetProfile()))
        // Generic: K static, boxColumn + 3 < K, scoreColumn < K, score column outside the box columns, equal N.
        fun genericModel(vararg outs: Proto) = Onnx.genericModel(floatInput, outs.toList())
        val det = Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, 100, 5))
        val p = { f: DetectionFormat -> DetectorProfile(f, 640, 640, 0.5f, ANY_SHA256) }
        loadPinned(genericModel(det), p(generic()))
        assertEquals(listOf("OUTPUT_SHAPE"), load(genericModel(det), p(generic(boxColumn = 2, scoreColumn = 0))))
        assertEquals(listOf("OUTPUT_SHAPE"), load(genericModel(det), p(generic(scoreColumn = 5))))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            load(genericModel(det), p(generic(boxColumn = 1, scoreColumn = 3))),
            "score inside the box",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            load(genericModel(Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, "n", "k"))), p(generic())),
            "K must be static",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            load(genericModel(Onnx.valueInfo("det", Onnx.FLOAT, listOf(2, 100, 5))), p(generic())),
        )
        loadPinned(
            genericModel(Onnx.valueInfo("det", Onnx.FLOAT, listOf("n", 5))),
            p(generic(nms = true)),
        )
        loadPinned(
            genericModel(Onnx.valueInfo("det", Onnx.FLOAT, listOf(5, 100))),
            p(generic(rows = RowLayout.COLUMNS)),
        )
        val boxes = Onnx.valueInfo("boxes", Onnx.FLOAT, listOf(1, 100, 4))
        val scores = Onnx.valueInfo("scores", Onnx.FLOAT, listOf(1, 100, 2))
        val two = generic(boxes = "boxes", scores = "scores", scoreColumn = 1)
        loadPinned(genericModel(boxes, scores), p(two))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            load(genericModel(boxes, Onnx.valueInfo("scores", Onnx.FLOAT, listOf(1, 99, 2))), p(two)),
        )
        assertEquals(listOf("OUTPUT_MISSING"), load(genericModel(boxes), p(two)))
        val nhwc = Onnx.genericModel(Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 480, 640, 3)), listOf(det))
        loadPinned(
            nhwc,
            DetectorProfile(generic(layout = TensorLayout.NHWC), 640, 480, 0.5f, ANY_SHA256),
        )
        assertEquals(listOf("INPUT_SHAPE"), load(nhwc, DetectorProfile(generic(), 640, 480, 0.5f, ANY_SHA256)))
    }

    @Test
    fun theReportIsCompleteAndReadOnly() {
        val m = loadPinned(Onnx.yunetModel(640, 480), yunetProfile(640, 480))
        assertEquals(8L, m.info.irVersion)
        assertEquals(mapOf("" to 13L), m.info.opsetImports)
        assertEquals("FLOAT", m.info.inputs.single().elementType)
        assertEquals(listOf<Long?>(1, 3, 480, 640), m.info.inputs.single().shape)
        assertEquals(12, m.info.outputs.size)
        assertEquals(emptyList(), m.info.warnings)
        assertTrue(m.toString().startsWith("DetectorModel(profile="))
        assertEquals(
            m.info,
            loadPinned(Onnx.yunetModel(640, 480), yunetProfile(640, 480)).info,
        )
        assertEquals("FLOAT", ModelLoader.elementTypeName(1))
        assertEquals("INT2", ModelLoader.elementTypeName(26))
        assertEquals("UNKNOWN_99", ModelLoader.elementTypeName(99))
    }

    @Test
    fun theSmallInputWarningScalesWithTheWorkingSize() {
        fun warnings(w: Int, h: Int) = loadPinned(
            Onnx.genericModel(
                Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, h, w)),
                listOf(Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, 100, 5))),
            ),
            DetectorProfile(generic(), w, h, 0.5f, ANY_SHA256),
        ).info.warnings
        assertEquals(listOf("SMALL_INPUT"), warnings(320, 240))
        assertEquals(emptyList(), warnings(640, 480))
        assertEquals(listOf("SMALL_INPUT"), warnings(640, 383))
        assertEquals(emptyList(), warnings(640, 384))
        val p300 = DetectorProfile(generic(), 300, 300, 0.5f, ANY_SHA256)
        assertEquals(listOf("SMALL_INPUT"), Warnings.forWorkingSide(p300, 3500))
        assertEquals(emptyList(), Warnings.forWorkingSide(p300, 1750), "gap side about 271 px at 1,750 px")
        val p384 = DetectorProfile(generic(), 384, 384, 0.5f, ANY_SHA256)
        assertEquals(emptyList(), Warnings.forWorkingSide(p384, 3500))
        assertEquals(listOf("SMALL_INPUT"), Warnings.forWorkingSide(p384, 8000), "gap side about 580 px at 8,000 px")
        val p272 = DetectorProfile(generic(), 272, 272, 0.5f, ANY_SHA256)
        val p271 = DetectorProfile(generic(), 271, 271, 0.5f, ANY_SHA256)
        assertEquals(emptyList(), Warnings.forWorkingSide(p272, 1750), "384 × sqrt(0.5) = 271.5")
        assertEquals(listOf("SMALL_INPUT"), Warnings.forWorkingSide(p271, 1750))
    }
}
