// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model input of every pass (tiles, the scaled and padded global pass, crops at the right and bottom edge, odd
 * and one-pixel pictures), end to end through the tiled detector: the YuNet layout, which fills the tensor directly,
 * hands the model exactly the tensor of a generic model with the same layout (NCHW, BGR, identity scaling), which
 * is built by the general fill. Every tensor is compared bit for bit, with the same number of passes.
 */
class TensorFillEdgeTest {
    private val inputW = 160
    private val inputH = 128

    /** Keeps a copy of every input tensor and finds nothing (all scores 0). */
    private abstract class Recording : TensorRunner {
        override val inputWidth: Int = 160
        override val inputHeight: Int = 128
        val tensors = ArrayList<FloatArray>()

        override fun initialize() = Unit

        override fun close() = Unit

        protected fun keep(input: FloatArray) {
            tensors += input.copyOf()
        }
    }

    private class YuNetNothing : Recording() {
        override fun run(input: FloatArray): Map<String, FloatArray> {
            keep(input)
            val m = HashMap<String, FloatArray>()
            for (s in intArrayOf(8, 16, 32)) {
                val n = (inputWidth / s) * (inputHeight / s)
                m["cls_$s"] = FloatArray(n)
                m["obj_$s"] = FloatArray(n)
                m["bbox_$s"] = FloatArray(n * 4)
            }
            return m
        }
    }

    private class GenericNothing : Recording() {
        override fun run(input: FloatArray): Map<String, FloatArray> {
            keep(input)
            return mapOf("det" to floatArrayOf(0f, 0f, 10f, 10f, 0f))
        }
    }

    private fun yunetDetector(runner: Recording): ModelDetector = yunetDetector(runner, yunetProfile(inputW, inputH))

    private fun genericDetector(runner: Recording): ModelDetector {
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
            PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f)),
        )
        val profile = DetectorProfile(format, inputW, inputH, 0.5f, ANY_SHA256)
        val bytes = Onnx.genericModel(
            Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, inputH, inputW)),
            listOf(Onnx.valueInfo("det", Onnx.FLOAT, listOf(1, 1, 5))),
        )
        val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes))
        return ModelDetector(FakeSession(runner, model, mapOf("det" to longArrayOf(1, 1, 5))), profile)
    }

    private fun rawBits(values: FloatArray) = IntArray(values.size) { values[it].toRawBits() }

    private fun picture(w: Int, h: Int, seed: Int): PixelDetectionImage {
        val argb = Golden.photoLike(w, h, seed)
        // Alpha the fill must ignore; a fully transparent and a fully black pixel among them.
        for (i in argb.indices) argb[i] = (((i * 53) and 0xFF) shl 24) or (argb[i] and 0xFFFFFF)
        argb[0] = 0x00FFFFFF
        return PixelDetectionImage(IntArrayPixels(w, h, argb))
    }

    @Test
    fun everyTensorOfOddAndEdgeSizesEqualsTheGeneralFill() {
        val sizes = listOf(
            1 to 1, 1 to 2000, 2000 to 1, 3 to 641, 641 to 3, 159 to 127, 160 to 128, 161 to 129, 160 to 129,
            161 to 128, 321 to 257, 317 to 509, 1000 to 7, 7 to 1000, 480 to 360,
        )
        var passes = 0
        for ((w, h) in sizes) {
            val yunet = YuNetNothing()
            val generic = GenericNothing()
            val image = picture(w, h, w * 7 + h)
            TiledFaceDetector(yunetDetector(yunet), DetectionSettings(0.5f)).detect(image)
            TiledFaceDetector(genericDetector(generic), DetectionSettings(0.5f)).detect(image)
            assertEquals(generic.tensors.size, yunet.tensors.size, "$w x $h: the same passes")
            assertTrue(yunet.tensors.isNotEmpty(), "$w x $h: at least the global pass")
            for (k in yunet.tensors.indices) {
                assertEquals(
                    rawBits(generic.tensors[k]).toList(),
                    rawBits(yunet.tensors[k]).toList(),
                    "$w x $h: tensor of pass $k",
                )
            }
            passes += yunet.tensors.size
        }
        assertTrue(passes > sizes.size, "tiled pictures take several passes")
    }

    @Test
    fun theSamePictureTwiceGivesTheSameTensorsAndTheCallersPicturesAreNotWritten() {
        val argb = Golden.photoLike(321, 257, 4)
        val before = argb.copyOf()
        val first = YuNetNothing()
        val second = YuNetNothing()
        val image = PixelDetectionImage(IntArrayPixels(321, 257, argb))
        TiledFaceDetector(yunetDetector(first), DetectionSettings(0.5f)).detect(image)
        // The same detector instance twice: the reused tensor buffer (padding zero again) and the shared crop arrays.
        val detector = TiledFaceDetector(yunetDetector(second), DetectionSettings(0.5f))
        detector.detect(image)
        val firstRun = second.tensors.size
        detector.detect(image)
        assertEquals(before.toList(), argb.toList(), "the pixels of the picture are only read")
        assertEquals(first.tensors.size, firstRun)
        assertEquals(2 * firstRun, second.tensors.size)
        for (k in 0 until firstRun) {
            assertEquals(rawBits(first.tensors[k]).toList(), rawBits(second.tensors[k]).toList(), "first run, pass $k")
            assertEquals(
                rawBits(first.tensors[k]).toList(),
                rawBits(second.tensors[firstRun + k]).toList(),
                "second run, pass $k",
            )
        }
    }
}
