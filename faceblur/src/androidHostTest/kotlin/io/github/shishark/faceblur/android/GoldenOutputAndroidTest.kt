// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.TensorFaceRunner
import io.github.shishark.faceblur.applyMasks
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskInPlace
import io.github.shishark.faceblur.maskJpeg
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden output of the Android adapters on the host (Robolectric native graphics): for every style, the JPEG bytes
 * that `maskJpeg` writes for every public fixture (`testdata/pd`: decode, detection input, masks, encode), the pixels
 * that `maskInPlace` leaves in a `Bitmap` of every fixture, and the pixels of one synthetic `Bitmap` after
 * `applyMasks` with a fixed plan (the picture and plan of GoldenOutputDeviceTest) must stay identical. Recorded
 * before the detection input and the mask stage were reworked for speed. The fake model's face comes from a hash of
 * every input tensor ([TensorFaceRunner]), so a changed detector input changes the output too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GoldenOutputAndroidTest {
    @Before
    fun setUp() {
        FaceBlurRuntime.installImageCodec(AndroidImageCodec.using(HostFiles))
    }

    @After
    fun tearDown() {
        FaceBlurRuntime.installImageCodec(null)
    }

    private val fixtures: List<File> = run {
        val dir = File("../testdata/pd").canonicalFile
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".png") }.sortedBy { it.name }.onEach {
            require(it.canonicalPath.startsWith(dir.path + File.separator)) { "outside testdata/pd" }
        }
    }

    private fun masker(style: MaskStyle): FaceMasker = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(MaskingConfig(style = style)),
        RunnerRuntime(TensorFaceRunner()),
    )

    private fun pixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun decoded(file: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return checkNotNull(BitmapFactory.decodeByteArray(file.readBytes(), 0, file.length().toInt(), options))
    }

    private fun actual(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            masker(style).use { masker ->
                val jpegs = fixtures.map { f ->
                    val sink = ByteArrayOutputStream()
                    masker.maskJpeg(ByteArrayInputStream(f.readBytes()), sink)
                    "${f.name} ${Golden.sha256(sink.toByteArray())}"
                }
                out["maskJpeg $style"] = Golden.combined(jpegs)
                val inPlace = fixtures.map { f ->
                    val bitmap = decoded(f)
                    masker.maskInPlace(bitmap)
                    "${f.name} ${Golden.fnv(pixels(bitmap))}"
                }
                out["maskInPlace $style"] = Golden.combined(inPlace)
                out["applyMasks synthetic $style"] = synthetic(masker)
            }
        }
        return out
    }

    /** The synthetic picture and fixed plan of the device test, applied to a `Bitmap`. */
    private fun synthetic(masker: FaceMasker): String {
        val w = Golden.DEVICE_W
        val h = Golden.DEVICE_H
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(Golden.photoLike(w, h, Golden.DEVICE_SEED), 0, w, 0, 0, w, h)
        masker.applyMasks(bitmap, Golden.devicePlan())
        return Golden.fnv(pixels(bitmap))
    }

    @Test
    fun theJpegAndBitmapOutputOfEveryStyleIsUnchanged() {
        assertEquals(22, fixtures.size, "the public fixture set")
        val actual = actual()
        assertEquals(EXPECTED, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "maskJpeg GRADIENT_OVAL" to "cfbc7426e935b464848fdde873c6270c7ae222447c8590ac19f1311b717ffd15",
            "maskInPlace GRADIENT_OVAL" to "4cac2da3de2dce2dd479f07dde528d5f9392d34f8107884a8d760ddd7a624c31",
            "applyMasks synthetic GRADIENT_OVAL" to "f09321b725b41c5c",
            "maskJpeg SMOOTH_FILL" to "2f561bbdb074f2489ae7173761bf457965ca4cd67d7e441b4b14d6d3f3731c66",
            "maskInPlace SMOOTH_FILL" to "ef491d31de4ca881d2306abc5c50b15f9c4c4042fe6b5fce71f340b79467ee18",
            "applyMasks synthetic SMOOTH_FILL" to "accca04284c4ff11",
            "maskJpeg SOLID" to "6a242b37e6f05ab83b7c8e59113b0a13cc1e3545539926881402b175b237a89d",
            "maskInPlace SOLID" to "0ecfe75278b4a334bb201eddb974571c89140f1c8907f75f6d8bbe73a8b6445a",
            "applyMasks synthetic SOLID" to "4b9c46f9275ed22a",
        )
    }
}
