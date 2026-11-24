// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.TensorFaceRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden output of the iOS file mode on the simulator: for every style, the JPEG bytes written for every public
 * fixture (`testdata/pd`: ImageIO decode, detection input, masks, ImageIO encode) must stay identical. Recorded before
 * the detection input and the mask stage were reworked for speed. The fake model's face comes from a hash of every
 * input tensor ([TensorFaceRunner]), so a changed detector input changes the output too. Fixtures of the local gate
 * are not part of the golden set.
 *
 * The expected values belong to the iOS 27.2 simulator runtime: its ImageIO encoder produced the JPEG bytes. A newer
 * runtime or Xcode may encode differently and fail this test without any change in the library. In that case do not
 * re-record on the changed code alone: first prove that the output is otherwise unchanged (the pixel goldens and the
 * detection-input golden pass), then re-record on the commit that introduced the new runtime, never in the same
 * commit as a change of masking or detection code.
 */
class GoldenOutputIosTest {
    @AfterTest
    fun cleanUp() = FaceBlurRuntime.installImageCodec(null)

    private val fixtures: List<String> = IosFiles.publicFixtures().filter { it.contains("/testdata/pd/") }

    private fun masker(style: MaskStyle): FaceMasker = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(MaskingConfig(style = style)),
        IosModelRuntime(FakeFactory { FakeBridge(TensorFaceRunner()) }),
    )

    @Test
    fun theJpegOutputOfEveryStyleIsUnchanged() {
        assertEquals(22, fixtures.size, "the public fixture set")
        FaceBlurRuntime.installImageCodec(IosImageCodec)
        val actual = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            masker(style).use { masker ->
                val jpegs = fixtures.map { path ->
                    val (_, out) = IosFiles.maskBytes(masker, IosFiles.read(path))
                    "${path.substringAfterLast('/')} ${Golden.sha256(out)}"
                }
                actual["file mode $style"] = Golden.combined(jpegs)
            }
        }
        assertEquals(EXPECTED, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "file mode GRADIENT_OVAL" to "bedb0a363e792f9ac7943c235f72a6e1735105592dfbf18ed01bf1d1276a1a3b",
            "file mode SMOOTH_FILL" to "677d11ff6bb2cd7812d3867a5e4d63652059d1443dce0ff0b9f19e479d07fdc7",
            "file mode SOLID" to "0ad36d8f8d89a3fcea5d8542afde70e4a93108c3072359cb6a845eec063b167f",
        )
    }
}
