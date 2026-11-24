// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden output with the reference model on the JVM runtime: on every public fixture (`testdata/pd`) and on one
 * composite of six of them (1,920 × 1,280 px: tiles plus a scaled, padded global pass), the tensors handed to the
 * model, the detected boxes (every value exactly) and the masked pixels of every style must stay bit-identical. The
 * values were recorded before the detection input and the mask stage were reworked for speed; the model's own
 * arithmetic is not part of that change. One runtime thread, so the model's results do not depend on scheduling.
 * Needs the reference model (a local fixture, config/local-fixture-tests.txt).
 */
class GoldenOutputJvmTest {
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
            seen += Golden.fnv(input)
            return inner.run(input, inputShape, outputNames, maxElements, timeoutMillis)
        }
    }

    private val fixtures: List<String> =
        (Fixtures.testdata.resolve("pd").list() ?: emptyArray()).filter { it.endsWith(".png") }.sorted()
            .map { "pd/$it" }

    /** Six fixtures side by side, three per row (each 640 × 640). */
    private fun composite(): IntArrayPixels {
        val parts = fixtures.take(6).map { Fixtures.image(it) }
        val out = IntArrayPixels(1920, 1280)
        for ((k, p) in parts.withIndex()) {
            val row = IntArray(p.width)
            for (y in 0 until p.height) {
                p.readPixels(0, y, p.width, 1, row)
                out.writePixels((k % 3) * 640, (k / 3) * 640 + y, p.width, 1, row)
            }
        }
        return out
    }

    private fun images(): List<Pair<String, () -> IntArrayPixels>> =
        fixtures.map { f -> f to { Fixtures.image(f) } } + ("composite 1920x1280" to ::composite)

    private fun actual(): Map<String, String> {
        val model = TestModels.yunet()
        val inputs = ArrayList<String>()
        val runtime = ModelRuntime { m, threads -> RecordingSession(OrtModelRuntime().open(m, threads), inputs) }
        val out = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            val config = FaceMaskerConfig(MaskingConfig(style = style), onnxThreads = 1)
            FaceMasker.forTest(model, config, runtime).use { masker ->
                val masked = ArrayList<String>()
                for ((name, image) in images()) {
                    val pixels = image()
                    val start = inputs.size
                    val result = masker.maskInPlace(pixels)
                    if (style == MaskStyle.entries.first()) {
                        val passes = inputs.subList(start, inputs.size).joinToString(",")
                        val boxes = Golden.combined(listOf(Golden.boxes(result.faces)))
                        out["$name boxes"] = "${result.faces.size} $boxes"
                        out["$name inputs"] = Golden.combined(listOf(passes))
                    }
                    masked += "$name ${Golden.fnv(pixels.data)}"
                }
                out["masked $style"] = Golden.combined(masked)
            }
        }
        return out
    }

    @Test
    fun boxesDetectorInputsAndMaskedPixelsOfThePublicFixturesAreUnchanged() {
        assertEquals(22, fixtures.size, "the public fixture set")
        val actual = actual()
        assertEquals(EXPECTED, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "pd/pd-p01-s025-x00938-y00610.png boxes" to
                "11 7e3fac8b5d96a96ad6f43b203ad4a5ce9f69468052f5ab0bc3356dbc4dafaf79",
            "pd/pd-p01-s025-x00938-y00610.png inputs" to
                "fad2be88a2cb13eb90e688cf73380e1649e71844a56633fd166beab21ea2bb5d",
            "pd/pd-p01-s035-x01339-y00967.png boxes" to
                "10 6afdbefb9ec9be1511159f362e318842dcffe5035b5d7360ddf8de6b7fe3be11",
            "pd/pd-p01-s035-x01339-y00967.png inputs" to
                "e9c97f7f91d4add057b015a13f31f4e5cc1a59352bc7b724db533c4c2c987f4e",
            "pd/pd-p01-s050-x02090-y01537.png boxes" to
                "7 8715a27a82120f3d34d8803c2faf58a30a505a17cd076746fa0707161c332501",
            "pd/pd-p01-s050-x02090-y01537.png inputs" to
                "b393ee7f0366cc4ad5bad0dc343af084aa873a32e46cdde3a9cfefb4911a2d6b",
            "pd/pd-p01-s075-x03369-y02443.png boxes" to
                "5 2dd6ec90791b1563d47b4921304ee10f47400b3f49e0381a749569f15a422dfe",
            "pd/pd-p01-s075-x03369-y02443.png inputs" to
                "42b99093cf734dde8de27cacbf9ed1799424f7655d1ca349a03479cd5724eac2",
            "pd/pd-p01-s075-x04860-y02556.png boxes" to
                "2 e09d4719d6bd279398abcc15150e1db1405df3380168044e0160d72acca23f00",
            "pd/pd-p01-s075-x04860-y02556.png inputs" to
                "462f3ab73919a65d998a30f23abf0208d065b1aab3a4ae45aa25dbb80efb7943",
            "pd/pd-p01-s100-x05759-y03433.png boxes" to
                "4 47b4dd20e49ecfda3d66f75ae86c443d3adbc13d0d388dda4761b0cfdbac40b8",
            "pd/pd-p01-s100-x05759-y03433.png inputs" to
                "7d9817760977e94635afb3e6b18626a5f10c6c2849b8be4d8719b46ff37381de",
            "pd/pd-p03-s025-x00000-y00014.png boxes" to
                "5 1046bf6da42cb95e5a6688dfcde21ea8e69d5b7751d3dbb6a423928f3f72c016",
            "pd/pd-p03-s025-x00000-y00014.png inputs" to
                "b8d35b7f8000b16e83059330a9008eab66b22765da1c11422d625b4c365be33d",
            "pd/pd-p03-s035-x00277-y00161.png boxes" to
                "5 95a221810777793e9cb0403b635bc3f20ae61470a42eb4b6dc8c1436cda00e89",
            "pd/pd-p03-s035-x00277-y00161.png inputs" to
                "841b046b173d7058d03d441ad5dd74b115f3939581755a96937d9923961eacda",
            "pd/pd-p03-s050-x00532-y00367.png boxes" to
                "5 5593bee70d4ce50ecbd112ae0c59f9d45770b94b03b299b838081453a1928c03",
            "pd/pd-p03-s050-x00532-y00367.png inputs" to
                "3cfb78000afb8649461aaffee73bd17f7ef953f216410051606f1f428a0a399d",
            "pd/pd-p03-s075-x01154-y00684.png boxes" to
                "3 c53efe240455fe3c0c0aa2dd924fa4a6ba0387dd6ee4c474a99d2f13b51f1510",
            "pd/pd-p03-s075-x01154-y00684.png inputs" to
                "f4ead1aaa1526b49e3edc1d808836e8895b7e3eff3481ec6a812730c9bc94855",
            "pd/pd-p03-s100-x01141-y01015.png boxes" to
                "2 5a80fd5c630f386965328b1b03c549249fc7c1ef801a300d73ac184325bcb59c",
            "pd/pd-p03-s100-x01141-y01015.png inputs" to
                "1f92d69dd36fc0856c06288e9fb9918131eacae6985c903b52f0e7c06547b711",
            "pd/pd-p06-s026-x00000-y00191.png boxes" to
                "6 e8f1f9eac5c7c2b3f1ca2c4da4c3ae64981c54d68767de2053e16d4cd574f52e",
            "pd/pd-p06-s026-x00000-y00191.png inputs" to
                "e733279ce06209e85c90c06a56bdd362f6971287039aeaed94d7c62d408cc6c0",
            "pd/pd-p06-s035-x00145-y00379.png boxes" to
                "6 a5f2acd2200d37270f34a1b5ca1fa97e0ced282d4df6c821ef6d4eb8d3d07d24",
            "pd/pd-p06-s035-x00145-y00379.png inputs" to
                "a3a15abcb386ab336f2b73e7d7ce45fdd9bb1688a9e50d4c0d4643d251ba4df3",
            "pd/pd-p06-s050-x00258-y00663.png boxes" to
                "5 892b5de13501827a8073990c67a9e6d7d2563dcc5cfd4591007f797f0d3f03d3",
            "pd/pd-p06-s050-x00258-y00663.png inputs" to
                "a27535ded25706df387037a0cc9deb9fadee3bf59d1a4dfac768717b61d1357e",
            "pd/pd-p06-s075-x00950-y01178.png boxes" to
                "4 758f6e9aead7dc1b0a0362e776d370293a42707f7a85deffeaaa7564f5a6e7e8",
            "pd/pd-p06-s075-x00950-y01178.png inputs" to
                "a139d3d349dcc2ba89b898cdfcff3b6d539486b7f448601fd14b714302a432de",
            "pd/pd-p06-s100-x01377-y01454.png boxes" to
                "2 25388f5775ccd7ed1ea352f694cb82e5cbc87bcffa3c7c0ef9eb5813b02ba4ed",
            "pd/pd-p06-s100-x01377-y01454.png inputs" to
                "0f30c5740b5351e558702d1565ea66f6064840d7183e57f9ec76e660f3183963",
            "pd/pd-p06-single-face-s100-x00200-y01600.png boxes" to
                "1 e7787e782d9229a2d3ed425f4c3dc8c5285d7350327cbf85b34b8289f687f413",
            "pd/pd-p06-single-face-s100-x00200-y01600.png inputs" to
                "32efe00d08c3f05c98424db7b6b0ea6d5ba462e0f9a64d05c153639d11fa8483",
            "pd/pd-p07-s026-x00183-y00018.png boxes" to
                "9 c4a15cbf7a4ce26e80c3fed55e9a622f6b6d1960cf78dd9bf715088cac761dfa",
            "pd/pd-p07-s026-x00183-y00018.png inputs" to
                "a28a90b6729683f3beef49f0bc72155722b674dcfab84ae4d6a9f07b9b764ada",
            "pd/pd-p07-s035-x00442-y00052.png boxes" to
                "9 64fcc4bd85a5d992bcc8fc99072125055831a5d8d93e6d21c28d0bab37d21dd4",
            "pd/pd-p07-s035-x00442-y00052.png inputs" to
                "1f189532baca8a34e5592a58323217763fef7c42ece93f33e4bccd1f3593ece1",
            "pd/pd-p07-s050-x00635-y00212.png boxes" to
                "7 9f86f325674011b5af508f39103734fdbc3d5ce00fc2f415e060506c3aa3515f",
            "pd/pd-p07-s050-x00635-y00212.png inputs" to
                "59c21910f51a1325c4ed39866b2086142d505df68b9539452a7432732bb317e5",
            "pd/pd-p07-s075-x01273-y00525.png boxes" to
                "5 a6ac4d0186122153ba574eef0cf7ed175afaae72390c980cc2ac0168344c8ba6",
            "pd/pd-p07-s075-x01273-y00525.png inputs" to
                "a297e6547b5ee2d6ae4910d9579e8ab36c8994d8f57815731177912f233a2232",
            "pd/pd-p07-s100-x01616-y01074.png boxes" to
                "2 c72e390591ec9348efbc5a744e387cd8aff63eeea5d6f0674b9c5e380d1ad5b1",
            "pd/pd-p07-s100-x01616-y01074.png inputs" to
                "8974f2f05dd23bbaa2a5a8c9bf2c9ab750e3bf439ce45f133ebac9e1fc9b3c27",
            "composite 1920x1280 boxes" to "39 53e4fc3d762594bcc073b9f427b5e87815b6228bf447820711925d2a37d8018b",
            "composite 1920x1280 inputs" to "a63b08691aa52de7d6d43b817588e9d001cd8763dde7705e976f1d9ae63a9de7",
            "masked GRADIENT_OVAL" to "0da02f12dd47c427bb474385bcb51f70d62bdee3afcf8911fe49797e7e8d75ef",
            "masked SMOOTH_FILL" to "cee2d2c307562a877d679be62692fea3503c10c84b1bac06b81d1cee0503665a",
            "masked SOLID" to "631950837b4175a7760b2d08772ce5fdad1164e48e7ce7905025b6c32a119df4",
        )
    }
}
