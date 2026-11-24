// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetProfile
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden detector input: every tensor the library hands the model (the contract run at creation and every pass of
 * `detect`: tiles, the scaled global pass, letterbox padding), the faces that come back and the masked pixels must
 * stay bit-identical. The digests were recorded before the detection input was reworked for speed. Runs on every
 * platform of the common tests (pure library arithmetic, the same values everywhere).
 *
 * The digests pin every pass of one session in the order it ran them, so they are taken with one runtime thread (one
 * session). With the defaults (two sessions, two passes at a time) the same pictures must give the same faces and the
 * same masked pixels from exactly the same inputs, the same number of passes and the same contract run; only the order
 * in which the two sessions start their runs may differ.
 *
 * YuNet layout: a 160 × 128 profile, so that small pictures already take several tiles and a scaled, padded global
 * pass. Generic layout: NHWC, RGB, divisor and per-channel mean and standard deviation (the path that is not
 * reworked, checked with the same rule).
 */
class DetectionInputGoldenTest {
    private class Picture(val name: String, val w: Int, val h: Int, val seed: Int)

    /** Several tiles and a scaled global pass; smaller than the input on both sides; exactly the input size. */
    private val pictures = listOf(
        Picture("517x389", 517, 389, 3),
        Picture("100x90", 100, 90, 7),
        Picture("160x128", 160, 128, 9),
    )

    private fun run(masker: FaceMasker, runner: TensorFaceRunner, p: Picture): String {
        val image = IntArrayPixels(p.w, p.h, Golden.photoLike(p.w, p.h, p.seed))
        val start = runner.inputs.size
        val found = masker.maskInPlace(image)
        val inputs = runner.inputs.subList(start, runner.inputs.size).joinToString(",")
        return Golden.combined(listOf(inputs, Golden.boxes(found.faces), Golden.fnv(image.data)))
    }

    /** The detection settings of the digests; with [FaceMaskerConfig.onnxThreads] 1 (one session). */
    private val pinned = DetectionSettings(minScore = 0.5f)

    private fun yunet(): Map<String, String> {
        val runner = TensorFaceRunner(160, 128)
        val profile = yunetProfile(160, 128)
        val out = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            val config = FaceMaskerConfig(MaskingConfig(style = style), onnxThreads = 1)
            FaceMasker.forTest(testYuNetModel(profile), config, RunnerRuntime(runner), pinned).use { masker ->
                for (p in pictures) out["yunet ${p.name} $style"] = run(masker, runner, p)
            }
        }
        // The first input of each masker is its contract run on mid-grey.
        out["yunet contract run"] = runner.inputs.first()
        return out
    }

    /** A generic one-row model whose row comes from the tensor hash, so a changed input changes the face. */
    private class GenericRunner(override val inputWidth: Int, override val inputHeight: Int) : TensorRunner {
        private val recorded = RecordedInputs()
        val inputs: List<String> get() = recorded.list

        override fun initialize() = Unit

        override fun close() = Unit

        override fun run(input: FloatArray): Map<String, FloatArray> {
            val hash = Golden.fnv(input)
            recorded.add(hash)
            val v = hash.takeLast(4).toInt(16)
            val left = (v % 60).toFloat()
            val top = (v / 60 % 50).toFloat()
            return mapOf("det" to floatArrayOf(left, top, left + 40f, top + 44f, 0.9f))
        }
    }

    private fun genericModel(): DetectorModel {
        val scaling = PixelScaling(255f, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
        val format = DetectionFormat.GenericDetections(
            "det",
            0,
            null,
            4,
            RowLayout.ROWS,
            BoxFormat.XYXY,
            CoordinateSpace.INPUT_PIXELS,
            ColorOrder.RGB,
            TensorLayout.NHWC,
            scaling,
        )
        val profile = DetectorProfile(format, 128, 128, 0.5f, ANY_SHA256)
        val bytes = Onnx.genericModel(
            Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 128, 128, 3)),
            listOf(Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, 1, 5))),
        )
        return DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes))
    }

    private fun generic(): Map<String, String> {
        val model = genericModel()
        val runner = GenericRunner(128, 128)
        val out = LinkedHashMap<String, String>()
        val runtime = RunnerRuntime(runner, mapOf("det" to longArrayOf(1, 1, 5)))
        FaceMasker.forTest(model, FaceMaskerConfig(onnxThreads = 1), runtime, pinned).use { masker ->
            for (p in pictures) {
                val image = IntArrayPixels(p.w, p.h, Golden.photoLike(p.w, p.h, p.seed))
                val start = runner.inputs.size
                val found = masker.detect(image)
                val inputs = runner.inputs.subList(start, runner.inputs.size).joinToString(",")
                out["generic ${p.name}"] = Golden.combined(listOf(inputs, Golden.boxes(found.faces)))
            }
        }
        out["generic contract run"] = runner.inputs.first()
        return out
    }

    @Test
    fun theDetectorInputsFacesAndMasksOfTheYuNetLayoutAreUnchanged() {
        val actual = yunet()
        assertEquals(EXPECTED_YUNET, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    @Test
    fun theDetectorInputsAndFacesOfTheGenericLayoutAreUnchanged() {
        val actual = generic()
        assertEquals(
            EXPECTED_GENERIC,
            actual,
            actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," },
        )
    }

    /**
     * The defaults (automatic threads: two sessions; the production detection settings) against the pinned run: the
     * same faces and masked pixels from exactly the same inputs (as a multiset: the two sessions run at the same time)
     * and the same number of passes. The bigger pictures open the second session, so the check cannot pass on one
     * session. The pinned run itself is held to the recorded digests above.
     */
    @Test
    fun theDefaultsOnTwoSessionsGiveThePinnedFacesAndMasksFromTheSameInputs() {
        var twoSessions = 0
        for (style in MaskStyle.entries) {
            for (p in pictures) {
                val want = pinnedCall(style, p)
                val runner = TensorFaceRunner(160, 128)
                val runtime = RunnerRuntime(runner)
                val config = FaceMaskerConfig(MaskingConfig(style = style))
                val got = FaceMasker.forTest(testYuNetModel(yunetProfile(160, 128)), config, runtime).use {
                    call(it, runner.inputs::size, { runner.inputs }, p)
                }
                val label = "${p.name} $style"
                assertEquals(want.faces, got.faces, "$label faces")
                assertEquals(want.masks, got.masks, "$label masks")
                assertEquals(want.pixels, got.pixels, "$label masked pixels")
                assertEquals(want.inputs.sorted(), got.inputs.sorted(), "$label: the same inputs")
                assertEquals(want.passes, got.passes, "$label passes")
                assertEquals(got.passes, got.inputs.size, "$label: one input per pass")
                if (runtime.opened == 2) twoSessions++
            }
        }
        assertTrue(twoSessions > 0, "the pictures use the second session")
    }

    private fun pinnedCall(style: MaskStyle, p: Picture): Call {
        val runner = TensorFaceRunner(160, 128)
        val one = FaceMaskerConfig(MaskingConfig(style = style), onnxThreads = 1)
        return FaceMasker.forTest(testYuNetModel(yunetProfile(160, 128)), one, RunnerRuntime(runner), pinned).use {
            call(it, runner.inputs::size, { runner.inputs }, p)
        }
    }

    private class Call(
        val faces: List<FaceBox>,
        val masks: List<Mask>,
        val pixels: String,
        val inputs: List<String>,
        val passes: Int,
    )

    private fun call(masker: FaceMasker, size: () -> Int, inputs: () -> List<String>, p: Picture): Call {
        val image = IntArrayPixels(p.w, p.h, Golden.photoLike(p.w, p.h, p.seed))
        val start = size()
        val found = masker.maskInPlace(image)
        // The first input is the contract run at creation (mid-grey); a second session opened during the call runs it
        // too, and it is no pass of the picture.
        val contract = inputs().first()
        return Call(
            found.faces,
            found.masks,
            Golden.fnv(image.data),
            inputs().drop(start).filter { it != contract },
            found.timings.passes,
        )
    }

    /**
     * The synthetic picture and fixed plan of the Android device test, applied through the common route: the same
     * digests as the `Bitmap` route of the device and host tests, so the device test's values are checked on every
     * platform here.
     */
    @Test
    fun theDevicePictureGivesTheSameMaskedPixelsOnTheCommonRoute() {
        val actual = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            val config = FaceMaskerConfig(MaskingConfig(style = style))
            FaceMasker.forTest(testYuNetModel(), config, RunnerRuntime(TensorFaceRunner())).use { masker ->
                val image = IntArrayPixels(
                    Golden.DEVICE_W,
                    Golden.DEVICE_H,
                    Golden.photoLike(Golden.DEVICE_W, Golden.DEVICE_H, Golden.DEVICE_SEED),
                )
                masker.applyMasks(image, Golden.devicePlan())
                actual["applyMasks synthetic $style"] = Golden.fnv(image.data)
            }
        }
        assertEquals(EXPECTED_DEVICE_PICTURE, actual, actual.toString())
    }

    private companion object {
        /** The values of the Android device and host tests (GoldenOutputDeviceTest, GoldenOutputAndroidTest). */
        val EXPECTED_DEVICE_PICTURE: Map<String, String> = mapOf(
            "applyMasks synthetic GRADIENT_OVAL" to "962233205ec3d9ff",
            "applyMasks synthetic SMOOTH_FILL" to "8e670f9ad7cc518c",
            "applyMasks synthetic SOLID" to "85089264dfa22c5d",
        )

        val EXPECTED_YUNET: Map<String, String> = mapOf(
            "yunet 517x389 GRADIENT_OVAL" to "d044c141294cc8fb8f7bae14922535f99c82b8fa1960d29b42e4abd326a48eb8",
            "yunet 100x90 GRADIENT_OVAL" to "0eaa0ff2023b22f68c1e52ae7a3ee88da5058c090daa893a5c47ea0c6a8dee66",
            "yunet 160x128 GRADIENT_OVAL" to "a9d508a9ee9913478edab58eea65d1c6c0117edeed1d22eb2aa2c994396e81db",
            "yunet 517x389 SMOOTH_FILL" to "5abd35f26d9fbaaacb26ff3efec65ff2647f41a4e63b44875fe99d0d832ece32",
            "yunet 100x90 SMOOTH_FILL" to "e61f72a64d0d26938cc853f95956eb56153c4aaf720de893c87518c5ce38ae3a",
            "yunet 160x128 SMOOTH_FILL" to "6b32989b4ba620e7821884c4cad2aa888fbb1c797d49854c6e06aa937e78681b",
            "yunet 517x389 SOLID" to "0d924f1dff5192bdbbc3fc2616c953023e4f4d5b3c654a5e3faa66efca3cbe65",
            "yunet 100x90 SOLID" to "fa49ab106242dd2faa893aa515b1f59652715179e206dec54b2697c089bb9d06",
            "yunet 160x128 SOLID" to "c1cf18658559311e3fbbb7b97145d6843b6629d5adc6ac63ee5efe4541e8a646",
            "yunet contract run" to "b7819adc5b9ce325",
        )

        val EXPECTED_GENERIC: Map<String, String> = mapOf(
            "generic 517x389" to "9fd0c8851d5ebc0dd223225644be5390fb9d6d5451cfa6ab4fdaa48d458bf77c",
            "generic 100x90" to "20f3ba7214756f538f9c0d27efa1682dfc48cec5333741032d018900089d2920",
            "generic 160x128" to "aa8b78656c55cdc5ce3c57bc7bafaffde3e5ab4e92a14d23f02bcc253ebf402b",
            "generic contract run" to "ddcc55ea96952325",
        )
    }
}
