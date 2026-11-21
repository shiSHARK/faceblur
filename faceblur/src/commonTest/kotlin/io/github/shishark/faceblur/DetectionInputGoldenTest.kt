// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

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

/**
 * Golden detector input: every tensor the library hands the model (the contract run at creation and every pass of
 * `detect`: tiles, the scaled global pass, letterbox padding), the faces that come back and the masked pixels must
 * stay bit-identical. The digests were recorded before the detection input was reworked for speed. Runs on every
 * platform of the common tests (pure library arithmetic, the same values everywhere).
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

    private fun yunet(): Map<String, String> {
        val runner = TensorFaceRunner(160, 128)
        val profile = yunetProfile(160, 128)
        val out = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            val config = FaceMaskerConfig(MaskingConfig(style = style))
            FaceMasker.forTest(testYuNetModel(profile), config, RunnerRuntime(runner)).use { masker ->
                for (p in pictures) out["yunet ${p.name} $style"] = run(masker, runner, p)
            }
        }
        // The first input of each masker is its contract run on mid-grey.
        out["yunet contract run"] = runner.inputs.first()
        return out
    }

    /** A generic one-row model whose row comes from the tensor hash, so a changed input changes the face. */
    private class GenericRunner(override val inputWidth: Int, override val inputHeight: Int) : TensorRunner {
        val inputs: MutableList<String> = ArrayList()

        override fun initialize() = Unit

        override fun close() = Unit

        override fun run(input: FloatArray): Map<String, FloatArray> {
            val hash = Golden.fnv(input)
            inputs += hash
            val v = hash.takeLast(4).toInt(16)
            val left = (v % 60).toFloat()
            val top = (v / 60 % 50).toFloat()
            return mapOf("det" to floatArrayOf(left, top, left + 40f, top + 44f, 0.9f))
        }
    }

    private fun generic(): Map<String, String> {
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
        val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes))
        val runner = GenericRunner(128, 128)
        val out = LinkedHashMap<String, String>()
        val runtime = RunnerRuntime(runner, mapOf("det" to longArrayOf(1, 1, 5)))
        FaceMasker.forTest(model, FaceMaskerConfig(), runtime).use { masker ->
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
