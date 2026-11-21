// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.applyMasks
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.maskInPlace
import io.github.shishark.faceblur.maskJpeg
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals

/**
 * Golden output on the phone, recorded ON THE TEST PHONE from the code before the detection input and the mask stage
 * were reworked for speed, and compared exactly afterwards:
 * - a synthetic picture masked with a fixed plan in every style: the pixels of the in-memory `Bitmap` route
 *   (`applyMasks`), and the JPEG bytes of the file route without a model (`PlanMasker.maskFile`, the picture written
 *   as a lossless PNG first);
 * - with the reference model, on the 22 public fixtures (`testdata/pd`, placed by the harness from
 *   config/device-tests/fixtures.txt) and on one composite of six of them (1,920 × 1,280 px: tiles plus a scaled,
 *   padded global pass): the JPEG bytes of `maskJpeg` (fixtures) and `maskFile` (composite) in every style, every box
 *   value exactly, every tensor handed to the model, and the pixels `maskInPlace` leaves in a `Bitmap` in every style.
 * The values are the test phone's own (its decoders, encoder and ONNX Runtime with XNNPACK), recorded on it from commit
 * 4c45f1a; the keys and digests have the format of the common and JVM golden tests so the two can be compared. At
 * recording the synthetic pixels and every model input equalled the common and JVM values; the face counts equalled
 * the JVM's, while the exact box values (and so the masked pixels) differ: the model's arithmetic differs between
 * ONNX Runtime with XNNPACK on the phone and the JVM's CPU runtime. Digests only, nothing of a picture is logged.
 */
@RunWith(AndroidJUnit4::class)
class GoldenOutputDeviceTest : TimeLimitedDeviceTest() {
    private fun model(): DetectorModel = DetectorModel.load(
        ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
        DeviceFixtures.yunetProfile(),
    )

    /** A session that records the FNV-1a digest of every input tensor and passes the run on unchanged. */
    private class RecordingSession(private val inner: ModelSession, private val seen: MutableList<String>) :
        ModelSession by inner {
        override fun run(
            input: FloatArray,
            inputShape: LongArray,
            outputNames: List<String>,
            maxElements: Long,
            timeoutMillis: Long,
        ): Map<String, PassOutput> {
            seen += fnv(input)
            return inner.run(input, inputShape, outputNames, maxElements, timeoutMillis)
        }
    }

    private fun verify(actual: Map<String, String>, expected: Map<String, String>) {
        for ((k, v) in actual) Log.i(TAG, "golden \"$k\" to \"$v\",")
        assertEquals(expected, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    @Test
    fun theMaskedBitmapAndPlanJpegOfEveryStyleAreUnchangedOnThePhone() {
        DeviceFixtures.logHeader()
        val dir = DeviceFixtures.scratch("golden-synthetic")
        try {
            val source = File(dir, "synthetic.png")
            val picture = syntheticBitmap()
            source.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val model = model()
            val actual = LinkedHashMap<String, String>()
            for (style in MaskStyle.entries) {
                FaceMasker(model, FaceMaskerConfig(MaskingConfig(style = style))).use { masker ->
                    val bitmap = syntheticBitmap()
                    masker.applyMasks(bitmap, plan())
                    actual["applyMasks synthetic $style"] = fnv(pixels(bitmap))
                }
                val planMasker = PlanMasker(MaskingConfig(style = style))
                val plan = planMasker.planFile(source.path).withMasks(plan().masks)
                val output = File(dir, "plan-$style.jpg")
                planMasker.maskFile(source.path, OutputTarget.NewFile(output.path), plan)
                actual["planMasker maskFile synthetic $style"] = sha256(output.readBytes())
            }
            verify(actual, EXPECTED_SYNTHETIC)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theReferenceModelGivesTheRecordedJpegsBoxesInputsAndPixelsOfThePublicFixturesOnThePhone() {
        DeviceFixtures.logHeader()
        val dir = DeviceFixtures.scratch("golden-fixtures")
        try {
            val names = FIXTURES.map { "pd/$it" }
            val bytes = FIXTURES.map { DeviceFixtures.file(it).readBytes() }
            val compositeFile = File(dir, "composite.png")
            compositeFile.outputStream().use { check(composite(bytes).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val model = model()
            val inputs = ArrayList<String>()
            val runtime = ModelRuntime { m, threads -> RecordingSession(AndroidModelRuntime.open(m, threads), inputs) }
            val actual = LinkedHashMap<String, String>()
            for (style in MaskStyle.entries) {
                val config = FaceMaskerConfig(MaskingConfig(style = style))
                val first = style == MaskStyle.entries.first()
                FaceMasker(model, config).use { masker ->
                    val jpegs = names.indices.map { i ->
                        val sink = ByteArrayOutputStream()
                        val result = masker.maskJpeg(ByteArrayInputStream(bytes[i]), sink)
                        if (first) actual["${names[i]} maskJpeg boxes"] = boxes(result.faces)
                        "${names[i]} ${sha256(sink.toByteArray())}"
                    }
                    actual["maskJpeg $style"] = combined(jpegs)
                    val output = File(dir, "composite-$style.jpg")
                    val result = masker.maskFile(compositeFile.path, OutputTarget.NewFile(output.path))
                    if (first) actual["$COMPOSITE maskFile boxes"] = boxes(result.faces)
                    actual["maskFile $COMPOSITE $style"] = sha256(output.readBytes())
                }
                FaceMasker.forTest(model, config, runtime).use { masker ->
                    val images = names.indices.map { i -> names[i] to { decoded(bytes[i]) } } +
                        (COMPOSITE to { composite(bytes) })
                    val masked = images.map { (name, image) ->
                        val bitmap = image()
                        val start = inputs.size
                        val result = masker.maskInPlace(bitmap)
                        if (first) {
                            actual["$name boxes"] = boxes(result.faces)
                            val passes = inputs.subList(start, inputs.size).joinToString(",")
                            actual["$name inputs"] = combined(listOf(passes))
                        }
                        "$name ${fnv(pixels(bitmap))}"
                    }
                    actual["masked $style"] = combined(masked)
                }
            }
            verify(actual, EXPECTED_FIXTURES)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "FaceBlurGolden"
        const val W = 1000
        const val H = 750
        const val SEED = 21
        const val COMPOSITE = "composite 1920x1280"
        const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL
        const val FNV_PRIME: Long = 0x100000001b3L

        /** The public fixtures of config/device-tests/fixtures.txt (testdata/pd), in name order. */
        val FIXTURES: List<String> = listOf(
            "pd-p01-s025-x00938-y00610.png",
            "pd-p01-s035-x01339-y00967.png",
            "pd-p01-s050-x02090-y01537.png",
            "pd-p01-s075-x03369-y02443.png",
            "pd-p01-s075-x04860-y02556.png",
            "pd-p01-s100-x05759-y03433.png",
            "pd-p03-s025-x00000-y00014.png",
            "pd-p03-s035-x00277-y00161.png",
            "pd-p03-s050-x00532-y00367.png",
            "pd-p03-s075-x01154-y00684.png",
            "pd-p03-s100-x01141-y01015.png",
            "pd-p06-s026-x00000-y00191.png",
            "pd-p06-s035-x00145-y00379.png",
            "pd-p06-s050-x00258-y00663.png",
            "pd-p06-s075-x00950-y01178.png",
            "pd-p06-s100-x01377-y01454.png",
            "pd-p06-single-face-s100-x00200-y01600.png",
            "pd-p07-s026-x00183-y00018.png",
            "pd-p07-s035-x00442-y00052.png",
            "pd-p07-s050-x00635-y00212.png",
            "pd-p07-s075-x01273-y00525.png",
            "pd-p07-s100-x01616-y01074.png",
        )

        val EXPECTED_SYNTHETIC: Map<String, String> = mapOf(
            "applyMasks synthetic GRADIENT_OVAL" to "962233205ec3d9ff",
            "planMasker maskFile synthetic GRADIENT_OVAL" to
                "09f2ddd13bb1bebb98d488da07019ee451075f9d7e993b07d28e9eba467f9708",
            "applyMasks synthetic SMOOTH_FILL" to "8e670f9ad7cc518c",
            "planMasker maskFile synthetic SMOOTH_FILL" to
                "52c8bf1fbb30ffc1a1c0c677cf89ebe56d64eed36ff087aa3705915966e90405",
            "applyMasks synthetic SOLID" to "85089264dfa22c5d",
            "planMasker maskFile synthetic SOLID" to "b773a42e59bc5ec76092917afb9216d516b6d16db5caac21bbbe8fdb2523a75c",
        )

        val EXPECTED_FIXTURES: Map<String, String> = mapOf(
            "pd/pd-p01-s025-x00938-y00610.png maskJpeg boxes" to
                "11 fbcc17f57f352ba883dc8ab380a2b27169996c10137ebb13889a74b8371e8b1e",
            "pd/pd-p01-s035-x01339-y00967.png maskJpeg boxes" to
                "10 b088ce9618600ac6591c25bf207b4ea406cc7833b9bb798665589c1c0efad4b8",
            "pd/pd-p01-s050-x02090-y01537.png maskJpeg boxes" to
                "7 fe3a696d9faa4e2b33859858e4b68ceee293c07aa9258be57e22b7af77f09118",
            "pd/pd-p01-s075-x03369-y02443.png maskJpeg boxes" to
                "5 959fb24d770fb939210a0e045df3f564e028284b0fcf17f1ecec6d3924e8408e",
            "pd/pd-p01-s075-x04860-y02556.png maskJpeg boxes" to
                "2 96099d2cdac913da50381c22c6138a057a0be923eb24a20924fcb7ebad5c60bb",
            "pd/pd-p01-s100-x05759-y03433.png maskJpeg boxes" to
                "4 d4540901e0d1060cbf79f42779824f8c263977f2d0799c7c78264c4b9ccf797b",
            "pd/pd-p03-s025-x00000-y00014.png maskJpeg boxes" to
                "5 75d2ba029ef9512e9c97d633f844bc639bfd61163e73f3a6b1ccca8136efe51b",
            "pd/pd-p03-s035-x00277-y00161.png maskJpeg boxes" to
                "5 931f12594bb9c5566d8aa1885b5acb9608b1fc6ef32231f8f06f21d26948af0b",
            "pd/pd-p03-s050-x00532-y00367.png maskJpeg boxes" to
                "5 e8c90ee7abf6c9b940e6e561c83b5c004434caf1096ab3ca34b6132e9b6a8d04",
            "pd/pd-p03-s075-x01154-y00684.png maskJpeg boxes" to
                "3 795f9c5c1d61a268482cbb0eb48791cf4f10bd4bbc4be4660b0affcc6db1e22e",
            "pd/pd-p03-s100-x01141-y01015.png maskJpeg boxes" to
                "2 3fc9460adb84ab1f3b858d55725cf319c6102cda0505380c9516caa3f0594ff4",
            "pd/pd-p06-s026-x00000-y00191.png maskJpeg boxes" to
                "6 23831ffbb9c1bf628559e424a810670ea4a3c34e472c7f64adb7429794948b14",
            "pd/pd-p06-s035-x00145-y00379.png maskJpeg boxes" to
                "6 b7a200014f17f37076076a60916c6fd711557b0728a9ddf13b651e77c185c2d6",
            "pd/pd-p06-s050-x00258-y00663.png maskJpeg boxes" to
                "5 09de5cbaa2e0ca7c8b9f7e573441f0bda101d99a9e58e928dad6efa0ee03892b",
            "pd/pd-p06-s075-x00950-y01178.png maskJpeg boxes" to
                "4 f31cbfac3cee37e6dc6d7461c7770736f273acf897fbddd9fa963ff08aee770b",
            "pd/pd-p06-s100-x01377-y01454.png maskJpeg boxes" to
                "2 7a0a4819bc269111be9ffc30e830c929013e0c3a988f1b1420bdd35f6c2f69e9",
            "pd/pd-p06-single-face-s100-x00200-y01600.png maskJpeg boxes" to
                "1 73ea394c4e33f161b2ebecb2f2f46a14b504eba8657f0fa0cc88843df07730e6",
            "pd/pd-p07-s026-x00183-y00018.png maskJpeg boxes" to
                "9 fc8cba042dc481323341bba0ff21d3c83c8cb409f60bb696cd8619d2a0cb0201",
            "pd/pd-p07-s035-x00442-y00052.png maskJpeg boxes" to
                "9 21e0b6c4e1e0429d3ebec8dcf998998d5a7c58824d13ec950d04495759053668",
            "pd/pd-p07-s050-x00635-y00212.png maskJpeg boxes" to
                "7 092caa032521594e95d9699ab2be830cb96ec97d8eb2db19e541c437b0471240",
            "pd/pd-p07-s075-x01273-y00525.png maskJpeg boxes" to
                "5 e5b486ca1157c035d9d1fe247c64262a1101c94634315de33e0879ca692e4743",
            "pd/pd-p07-s100-x01616-y01074.png maskJpeg boxes" to
                "2 8e057f3cfb3c23bf563f5b66a7a0649778a3f3950fb099993551e7fe7e70ae13",
            "maskJpeg GRADIENT_OVAL" to "150a920ed02860849e75cc9d0d3f14a7b80adf62479827dcffa67e02a4a8e885",
            "composite 1920x1280 maskFile boxes" to
                "39 0fcc357838e3235742750fa12d4bd7fdeddb1e7969b8c19908ee9309bc4e9047",
            "maskFile composite 1920x1280 GRADIENT_OVAL" to
                "e05e08dd0135c7018ab7abec402bc7488622630d88ac9a9cf3d74e1d4023fbdc",
            "pd/pd-p01-s025-x00938-y00610.png boxes" to
                "11 fbcc17f57f352ba883dc8ab380a2b27169996c10137ebb13889a74b8371e8b1e",
            "pd/pd-p01-s025-x00938-y00610.png inputs" to
                "fad2be88a2cb13eb90e688cf73380e1649e71844a56633fd166beab21ea2bb5d",
            "pd/pd-p01-s035-x01339-y00967.png boxes" to
                "10 b088ce9618600ac6591c25bf207b4ea406cc7833b9bb798665589c1c0efad4b8",
            "pd/pd-p01-s035-x01339-y00967.png inputs" to
                "e9c97f7f91d4add057b015a13f31f4e5cc1a59352bc7b724db533c4c2c987f4e",
            "pd/pd-p01-s050-x02090-y01537.png boxes" to
                "7 fe3a696d9faa4e2b33859858e4b68ceee293c07aa9258be57e22b7af77f09118",
            "pd/pd-p01-s050-x02090-y01537.png inputs" to
                "b393ee7f0366cc4ad5bad0dc343af084aa873a32e46cdde3a9cfefb4911a2d6b",
            "pd/pd-p01-s075-x03369-y02443.png boxes" to
                "5 959fb24d770fb939210a0e045df3f564e028284b0fcf17f1ecec6d3924e8408e",
            "pd/pd-p01-s075-x03369-y02443.png inputs" to
                "42b99093cf734dde8de27cacbf9ed1799424f7655d1ca349a03479cd5724eac2",
            "pd/pd-p01-s075-x04860-y02556.png boxes" to
                "2 96099d2cdac913da50381c22c6138a057a0be923eb24a20924fcb7ebad5c60bb",
            "pd/pd-p01-s075-x04860-y02556.png inputs" to
                "462f3ab73919a65d998a30f23abf0208d065b1aab3a4ae45aa25dbb80efb7943",
            "pd/pd-p01-s100-x05759-y03433.png boxes" to
                "4 d4540901e0d1060cbf79f42779824f8c263977f2d0799c7c78264c4b9ccf797b",
            "pd/pd-p01-s100-x05759-y03433.png inputs" to
                "7d9817760977e94635afb3e6b18626a5f10c6c2849b8be4d8719b46ff37381de",
            "pd/pd-p03-s025-x00000-y00014.png boxes" to
                "5 75d2ba029ef9512e9c97d633f844bc639bfd61163e73f3a6b1ccca8136efe51b",
            "pd/pd-p03-s025-x00000-y00014.png inputs" to
                "b8d35b7f8000b16e83059330a9008eab66b22765da1c11422d625b4c365be33d",
            "pd/pd-p03-s035-x00277-y00161.png boxes" to
                "5 931f12594bb9c5566d8aa1885b5acb9608b1fc6ef32231f8f06f21d26948af0b",
            "pd/pd-p03-s035-x00277-y00161.png inputs" to
                "841b046b173d7058d03d441ad5dd74b115f3939581755a96937d9923961eacda",
            "pd/pd-p03-s050-x00532-y00367.png boxes" to
                "5 e8c90ee7abf6c9b940e6e561c83b5c004434caf1096ab3ca34b6132e9b6a8d04",
            "pd/pd-p03-s050-x00532-y00367.png inputs" to
                "3cfb78000afb8649461aaffee73bd17f7ef953f216410051606f1f428a0a399d",
            "pd/pd-p03-s075-x01154-y00684.png boxes" to
                "3 795f9c5c1d61a268482cbb0eb48791cf4f10bd4bbc4be4660b0affcc6db1e22e",
            "pd/pd-p03-s075-x01154-y00684.png inputs" to
                "f4ead1aaa1526b49e3edc1d808836e8895b7e3eff3481ec6a812730c9bc94855",
            "pd/pd-p03-s100-x01141-y01015.png boxes" to
                "2 3fc9460adb84ab1f3b858d55725cf319c6102cda0505380c9516caa3f0594ff4",
            "pd/pd-p03-s100-x01141-y01015.png inputs" to
                "1f92d69dd36fc0856c06288e9fb9918131eacae6985c903b52f0e7c06547b711",
            "pd/pd-p06-s026-x00000-y00191.png boxes" to
                "6 23831ffbb9c1bf628559e424a810670ea4a3c34e472c7f64adb7429794948b14",
            "pd/pd-p06-s026-x00000-y00191.png inputs" to
                "e733279ce06209e85c90c06a56bdd362f6971287039aeaed94d7c62d408cc6c0",
            "pd/pd-p06-s035-x00145-y00379.png boxes" to
                "6 b7a200014f17f37076076a60916c6fd711557b0728a9ddf13b651e77c185c2d6",
            "pd/pd-p06-s035-x00145-y00379.png inputs" to
                "a3a15abcb386ab336f2b73e7d7ce45fdd9bb1688a9e50d4c0d4643d251ba4df3",
            "pd/pd-p06-s050-x00258-y00663.png boxes" to
                "5 09de5cbaa2e0ca7c8b9f7e573441f0bda101d99a9e58e928dad6efa0ee03892b",
            "pd/pd-p06-s050-x00258-y00663.png inputs" to
                "a27535ded25706df387037a0cc9deb9fadee3bf59d1a4dfac768717b61d1357e",
            "pd/pd-p06-s075-x00950-y01178.png boxes" to
                "4 f31cbfac3cee37e6dc6d7461c7770736f273acf897fbddd9fa963ff08aee770b",
            "pd/pd-p06-s075-x00950-y01178.png inputs" to
                "a139d3d349dcc2ba89b898cdfcff3b6d539486b7f448601fd14b714302a432de",
            "pd/pd-p06-s100-x01377-y01454.png boxes" to
                "2 7a0a4819bc269111be9ffc30e830c929013e0c3a988f1b1420bdd35f6c2f69e9",
            "pd/pd-p06-s100-x01377-y01454.png inputs" to
                "0f30c5740b5351e558702d1565ea66f6064840d7183e57f9ec76e660f3183963",
            "pd/pd-p06-single-face-s100-x00200-y01600.png boxes" to
                "1 73ea394c4e33f161b2ebecb2f2f46a14b504eba8657f0fa0cc88843df07730e6",
            "pd/pd-p06-single-face-s100-x00200-y01600.png inputs" to
                "32efe00d08c3f05c98424db7b6b0ea6d5ba462e0f9a64d05c153639d11fa8483",
            "pd/pd-p07-s026-x00183-y00018.png boxes" to
                "9 fc8cba042dc481323341bba0ff21d3c83c8cb409f60bb696cd8619d2a0cb0201",
            "pd/pd-p07-s026-x00183-y00018.png inputs" to
                "a28a90b6729683f3beef49f0bc72155722b674dcfab84ae4d6a9f07b9b764ada",
            "pd/pd-p07-s035-x00442-y00052.png boxes" to
                "9 21e0b6c4e1e0429d3ebec8dcf998998d5a7c58824d13ec950d04495759053668",
            "pd/pd-p07-s035-x00442-y00052.png inputs" to
                "1f189532baca8a34e5592a58323217763fef7c42ece93f33e4bccd1f3593ece1",
            "pd/pd-p07-s050-x00635-y00212.png boxes" to
                "7 092caa032521594e95d9699ab2be830cb96ec97d8eb2db19e541c437b0471240",
            "pd/pd-p07-s050-x00635-y00212.png inputs" to
                "59c21910f51a1325c4ed39866b2086142d505df68b9539452a7432732bb317e5",
            "pd/pd-p07-s075-x01273-y00525.png boxes" to
                "5 e5b486ca1157c035d9d1fe247c64262a1101c94634315de33e0879ca692e4743",
            "pd/pd-p07-s075-x01273-y00525.png inputs" to
                "a297e6547b5ee2d6ae4910d9579e8ab36c8994d8f57815731177912f233a2232",
            "pd/pd-p07-s100-x01616-y01074.png boxes" to
                "2 8e057f3cfb3c23bf563f5b66a7a0649778a3f3950fb099993551e7fe7e70ae13",
            "pd/pd-p07-s100-x01616-y01074.png inputs" to
                "8974f2f05dd23bbaa2a5a8c9bf2c9ab750e3bf439ce45f133ebac9e1fc9b3c27",
            "composite 1920x1280 boxes" to "39 0fcc357838e3235742750fa12d4bd7fdeddb1e7969b8c19908ee9309bc4e9047",
            "composite 1920x1280 inputs" to "a63b08691aa52de7d6d43b817588e9d001cd8763dde7705e976f1d9ae63a9de7",
            "masked GRADIENT_OVAL" to "3a6d260673f6241ac774cdaf94a6e0f36d97b6b3207c6cee9893fa489a9e97ae",
            "maskJpeg SMOOTH_FILL" to "10018a27f82a519288de0751cabfc3232316d866cce20cc118bd75d56a6f6881",
            "maskFile composite 1920x1280 SMOOTH_FILL" to
                "e8bbfca4abcdeb314a3b71e27773913ce1af57921deb982ac6fb21475716ec64",
            "masked SMOOTH_FILL" to "244621eddb4099d68ae2fad66809896e47252ebbe714c1c7098d51abe2590a2d",
            "maskJpeg SOLID" to "1e941c55ec0a4e9551e7669e6d88991d34aad6e64de09a181f6fc3cc5cf2c70b",
            "maskFile composite 1920x1280 SOLID" to "244163515a1e903c64b21cf353a1a2749baa7895a19b187b1243f1c4fa07de4d",
            "masked SOLID" to "05d82628c9c4ffa49f29208c6f6236092acc094c138d150f33e6d78984e78c77",
        )

        fun plan(): MaskPlan = MaskPlan(
            W,
            H,
            listOf(
                Mask.exact(MaskShape.ELLIPSE, 420f, 330f, 260f, 300f),
                Mask.exact(MaskShape.RECTANGLE, 820f, 160f, 120f, 90f),
                Mask.exact(MaskShape.ELLIPSE, 980f, 730f, 70f, 80f),
            ),
        )

        fun syntheticBitmap(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply {
            setPixels(photoLike(W, H, SEED), 0, W, 0, 0, W, H)
        }

        fun decoded(bytes: ByteArray): Bitmap {
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        }

        /** As the composite of GoldenOutputJvmTest: the first six fixtures side by side, three per row. */
        fun composite(fixtures: List<ByteArray>): Bitmap {
            val out = Bitmap.createBitmap(1920, 1280, Bitmap.Config.ARGB_8888)
            for ((k, b) in fixtures.take(6).withIndex()) {
                val part = decoded(b)
                val px = pixels(part)
                out.setPixels(px, 0, part.width, (k % 3) * 640, (k / 3) * 640, part.width, part.height)
            }
            return out
        }

        fun pixels(bitmap: Bitmap): IntArray {
            val w = bitmap.width
            val h = bitmap.height
            return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** As `Golden.combined` of the common tests: SHA-256 of the lines joined with newlines. */
        fun combined(parts: List<String>): String = sha256(parts.joinToString("\n").toByteArray(Charsets.UTF_8))

        /** As the JVM golden test: the face count and `Golden.boxes` (raw bits of every box value) combined. */
        fun boxes(faces: List<FaceBox>): String {
            val raw = faces.joinToString(";") {
                listOf(it.left, it.top, it.right, it.bottom, it.score).joinToString(",") { v ->
                    Integer.toHexString(v.toRawBits())
                }
            }
            return "${faces.size} ${combined(listOf(raw))}"
        }

        /** As `Golden.fnv` of the common tests: 64-bit FNV-1a, one step per int. */
        fun fnv(values: IntArray): String {
            var h = FNV_OFFSET
            for (v in values) h = (h xor (v.toLong() and 0xFFFFFFFFL)) * FNV_PRIME
            return h.toULong().toString(16).padStart(16, '0')
        }

        /** As `Golden.fnv` of the common tests for tensors: 64-bit FNV-1a over the raw bits of every float. */
        fun fnv(values: FloatArray): String {
            var h = FNV_OFFSET
            for (v in values) h = (h xor (v.toRawBits().toLong() and 0xFFFFFFFFL)) * FNV_PRIME
            return h.toULong().toString(16).padStart(16, '0')
        }

        /** As `Golden.photoLike` of the common tests. */
        fun photoLike(w: Int, h: Int, seed: Int): IntArray {
            var s = seed or 1
            return IntArray(w * h) { i ->
                s = s xor (s shl 13)
                s = s xor (s ushr 17)
                s = s xor (s shl 5)
                val x = i % w
                val y = i / w
                val n = (s and 0x1F) - 16
                val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
                val g = (y * 255 / maxOf(1, h - 1) - n).coerceIn(0, 255)
                val b = (if ((x / 23 + y / 17) % 2 == 0) 60 else 190) + n / 2
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b.coerceIn(0, 255)
            }
        }
    }
}
