// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionImage
import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.FaceDetectorEngine
import io.github.shishark.faceblur.detect.InPassNms
import io.github.shishark.faceblur.detect.Limits
import io.github.shishark.faceblur.detect.ModelDetector
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.TiledFaceDetector
import io.github.shishark.faceblur.detect.YUNET_STRIDES
import io.github.shishark.faceblur.detect.yunetDetector
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.CenterColorSource
import io.github.shishark.faceblur.mask.MaskSettings
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library defaults (ST-006 AC1, ADR 0002 decision 10). This test is the oracle for the defaults table: a change of
 * any default must change this test in the same commit.
 */
class DefaultsTest {
    @Test
    fun detectionDefaults() {
        val d = DetectionSettings(minScore = 0.5f)
        assertEquals(0.5f, d.minScore, "the threshold comes from the profile")
        assertEquals(0.4f, d.nmsIouThreshold, "cross-pass NMS IoU")
        assertEquals(0.25f, d.overlap, "overlap")
        assertEquals(262_144, d.maxPasses, "pass cap")
        assertEquals(Limits.MAX_MERGE_CANDIDATES_PER_PHOTO, d.maxMergeCandidates, "merge bound")
        assertEquals(0.3f, ModelDetector.IN_PASS_NMS_IOU, "in-pass NMS IoU")
        assertEquals(0.7f, TiledFaceDetector.CONTAINMENT_THRESHOLD, "containment merge")
        assertEquals(65_536L, Limits.MAX_ROWS)
        assertEquals(4_194_304L, Limits.MAX_ELEMENTS)
        assertEquals(40_000, Limits.MAX_DETECTIONS_PER_PASS)
        assertEquals(30_000L, Limits.CONTRACT_RUN_MILLIS)
        assertEquals(2_000L, Limits.MIN_RUN_TIMEOUT_MILLIS)
        assertEquals(20L, Limits.RUN_TIMEOUT_FACTOR)
        assertEquals(52_428_800L, DetectorModel.DEFAULT_MAX_MODEL_BYTES)
    }

    @Test
    fun maskDefaults() {
        val m = MaskSettings()
        assertEquals(MaskStyle.GRADIENT_OVAL, m.style, "default style (ADR 0002 decision 10)")
        assertEquals(MaskShape.ELLIPSE, m.shape)
        assertEquals(1.6f, m.expandX)
        assertEquals(1.8f, m.expandY)
        assertEquals(0.08f, m.shiftUp)
        assertEquals(0.10f, m.feather)
        assertEquals(0xFF7F7F7F.toInt(), m.color)
        assertEquals(CenterColorSource.FIXED, m.centerColorSource)
        assertEquals(0.45f, m.innerSolidFraction)
        assertEquals(0.15f, m.ringWidth)
    }

    @Test
    fun everyImageLargerThanOneTileIsTiledWithFullResolutionTiles() {
        val tiler = TiledFaceDetector(FakeEngine(emptyList()), DetectionSettings(0.5f))
        // 1000 × 800 was not tiled by the bench's earlier default (minImageSideForTiling 1600).
        val tiles = tiler.planTiles(1000, 800)
        assertEquals(4, tiles.size)
        for (t in tiles) {
            assertEquals(640, t[2])
            assertEquals(640, t[3])
        }
        assertEquals(listOf(0, 360), tiles.map { it[0] }.distinct())
        assertEquals(listOf(0, 160), tiles.map { it[1] }.distinct())
        // Stride is 480 px (25 % overlap of 640 px): 4000 px wide → 8 columns.
        assertEquals(8, tiler.planTiles(4000, 640).size)
        // An image that fits in one tile gets only the global pass (a single tile would add nothing).
        assertTrue(tiler.planTiles(640, 480).isEmpty())
        assertEquals(2, tiler.planTiles(641, 480).size)
    }

    @Test
    fun tilerPassesTheProfileThresholdToTheEngine() {
        val seen = ArrayList<Float>()
        val engine =
            object : FaceDetectorEngine {
                override val inputWidth = 640
                override val inputHeight = 640

                override fun close() = Unit

                override fun detect(
                    image: DetectionImage,
                    minScore: Float,
                    checkCancelled: () -> Unit,
                ): List<DetectedFace> {
                    seen += minScore
                    return emptyList()
                }
            }
        val outcome = TiledFaceDetector(engine, DetectionSettings(0.37f)).detect(FakeDetectionImage(1000, 800))
        assertEquals(5, outcome.passes, "1 global pass + 4 tiles")
        assertEquals(List(5) { 0.37f }, seen)
    }

    /** Two YuNet anchors whose integer boxes overlap with IoU ≈ 0.35: kept apart only when the NMS IoU is above it. */
    private val overlappingPair =
        object : TensorRunner {
            override val inputWidth = 640
            override val inputHeight = 640

            override fun initialize() = Unit

            override fun close() = Unit

            override fun run(input: FloatArray): Map<String, FloatArray> {
                val m = HashMap<String, FloatArray>()
                for (s in YUNET_STRIDES) {
                    val n = (640 / s) * (640 / s)
                    m["cls_$s"] = FloatArray(n)
                    m["obj_$s"] = FloatArray(n)
                    m["bbox_$s"] = FloatArray(n * 4)
                }
                // stride 8, row 40: column 40 → centre x 320; column 41 with offset 0.875 → centre x 335 (15 px apart).
                for ((col, dx, score) in listOf(Triple(40, 0f, 0.9f), Triple(41, 0.875f, 0.8f))) {
                    val idx = 40 * 80 + col
                    m.getValue("cls_8")[idx] = score
                    m.getValue("obj_8")[idx] = score
                    m.getValue("bbox_8")[idx * 4] = dx
                    m.getValue("bbox_8")[idx * 4 + 2] = ln(4f)
                    m.getValue("bbox_8")[idx * 4 + 3] = ln(4f)
                }
                return m
            }
        }

    @Test
    fun theInPassNmsIs0_3() {
        val image = FakeDetectionImage(640, 640)
        assertEquals(1, yunetDetector(overlappingPair).detect(image, 0.5f).size, "0.3 merges IoU 0.35")
        // The two anchors as decoded: 32 px boxes centred at x 320 and 335.
        val faces = listOf(
            DetectedFace(RectF(304f, 304f, 336f, 336f), 0.9f),
            DetectedFace(RectF(319f, 304f, 351f, 336f), 0.8f),
        )
        assertEquals(1, InPassNms.nmsLikeOpenCv(faces, 0.3f).size)
        assertEquals(2, InPassNms.nmsLikeOpenCv(faces, 0.4f).size)
    }
}
