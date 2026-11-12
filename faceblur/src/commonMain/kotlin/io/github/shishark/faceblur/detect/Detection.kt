// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.mask.MaskStageLimits

/**
 * A platform image handle that detectors consume (Android: `Bitmap`; iOS: an RGBA buffer).
 * Platform code downcasts to its own implementation; the shared YuNet detector uses [readArgb].
 */
internal interface DetectionImage {
    val width: Int
    val height: Int

    /**
     * Returns a NEW image holding the crop ([x], [y], [w], [h]) of this image, downscaled (never upscaled) so that
     * its longer side is at most [targetMaxSide]. The caller must [release] the returned image.
     */
    fun cropScaled(x: Int, y: Int, w: Int, h: Int, targetMaxSide: Int): DetectionImage

    /** All pixels as non-premultiplied ARGB, row-major. Only call on small images (≤ ~1 MP), e.g. a 640 px tile. */
    fun readArgb(): IntArray

    /** Free native memory now (idempotent). The full-resolution source image owns its own lifetime. */
    fun release()
}

/**
 * One detector pass (the library has one: [ModelDetector]; tests use fakes). Blocking; call off the main thread.
 */
internal interface FaceDetectorEngine {
    /** Width of one detector input in pixels; tiles have this width in source pixels. */
    val inputWidth: Int

    /** Height of one detector input in pixels; tiles have this height in source pixels. */
    val inputHeight: Int

    /**
     * Detect faces in [image] (at most [inputWidth] × [inputHeight]; a larger image is scaled down to fit) with the
     * score threshold [minScore]. Returned boxes are in [image]'s pixel coordinates and are NOT clipped: a box may
     * extend beyond [image] (a face at its edge). [checkCancelled] is called during long loops and throws to stop.
     */
    fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit = {}): List<DetectedFace>

    /**
     * Starts a new photo: the engine's per-photo work counts (the in-pass NMS pair checks) start at 0, with the bound
     * [maxNmsWork]. The tiler calls it before the first pass of every photo.
     */
    fun beginPhoto(maxNmsWork: Long) {}

    /** Diagnostics: in-pass NMS pair checks since [beginPhoto] (0 for engines without an in-pass NMS). */
    val nmsWork: Long get() = 0L

    /** Diagnostics: when set, the engine adds the time of its internal stages to this clock. */
    var stageClock: StageClock?
        get() = null
        set(_) {}

    fun close()
}

/**
 * The fixed detection rules (ADR 0002 decision 10, ADR 0012 decision 3): the same for every model and not
 * settings. The score threshold comes from the model's profile.
 */
internal data class DetectionSettings(
    /** The profile's score threshold. */
    val minScore: Float,
    /** IoU above which overlapping detections from different passes are merged. */
    val nmsIouThreshold: Float = DEFAULT_CROSS_PASS_NMS_IOU,
    /** Overlap between neighbouring tiles as a fraction of the tile side, on each axis. */
    val overlap: Float = DEFAULT_OVERLAP,
    /** Fail-closed pass cap ([Limits.MAX_PASSES_PER_IMAGE]); lowered only by tests. */
    val maxPasses: Int = Limits.MAX_PASSES_PER_IMAGE,
    /** Merge bound ([Limits.MAX_MERGE_CANDIDATES_PER_PHOTO]); lowered only by tests. */
    val maxMergeCandidates: Int = Limits.MAX_MERGE_CANDIDATES_PER_PHOTO,
    /** Merge work bound ([Limits.MAX_MERGE_PAIR_CHECKS_PER_PHOTO]); lowered only by tests. */
    val maxMergeWork: Long = Limits.MAX_MERGE_PAIR_CHECKS_PER_PHOTO,
    /** Mask count bound ([MaskStageLimits.MAX_MASKS_PER_IMAGE]); lowered only by tests. */
    val maxMasks: Int = MaskStageLimits.MAX_MASKS_PER_IMAGE,
    /** In-pass NMS work bound per photo ([Limits.MAX_NMS_PAIR_CHECKS_PER_PHOTO]); lowered only by tests. */
    val maxNmsWork: Long = Limits.MAX_NMS_PAIR_CHECKS_PER_PHOTO,
) {
    init {
        if (!(minScore > 0f && minScore < 1f)) throw CoreFailure.InvalidSetting("minScore", "greater than 0, below 1")
        if (!(nmsIouThreshold > 0f && nmsIouThreshold <= 1f)) {
            throw CoreFailure.InvalidSetting("nmsIouThreshold", "greater than 0, at most 1")
        }
        if (!(overlap >= 0f && overlap < 1f)) throw CoreFailure.InvalidSetting("overlap", "0 to below 1")
        if (maxPasses < 1) throw CoreFailure.InvalidSetting("maxPasses", ">= 1")
        if (maxMergeCandidates < 0) throw CoreFailure.InvalidSetting("maxMergeCandidates", ">= 0")
        if (maxMergeWork < 0L) throw CoreFailure.InvalidSetting("maxMergeWork", ">= 0")
        if (maxMasks < 0) throw CoreFailure.InvalidSetting("maxMasks", ">= 0")
        if (maxNmsWork < 0L) throw CoreFailure.InvalidSetting("maxNmsWork", ">= 0")
    }

    companion object {
        const val DEFAULT_CROSS_PASS_NMS_IOU: Float = 0.4f
        const val DEFAULT_OVERLAP: Float = 0.25f
    }
}

/** Technical limits against broken or runaway output (ADR 0012 decision 6); never a truncation. */
internal object Limits {
    /** Rows per output. Also the only bound on candidates above the threshold (no separate candidate cap). */
    const val MAX_ROWS: Long = 65_536L

    /** Elements per output. */
    const val MAX_ELEMENTS: Long = 4_194_304L

    /** Detections after the in-pass NMS, per pass (with NMS in the graph: rows at or above the threshold). */
    const val MAX_DETECTIONS_PER_PASS: Int = 40_000

    /** Passes per image, derived from geometry (128 px input, overlap 0.25, the in-memory size limits). */
    const val MAX_PASSES_PER_IMAGE: Int = 262_144

    /**
     * Candidates entering the cross-pass merge per photo (all passes), a fixed value for every working size (ADR 0012
     * decision 6). A generous technical limit that bounds the merge's memory; exceeding it fails closed with
     * `TOO_MANY_MERGE_CANDIDATES` before any pixel is written.
     */
    const val MAX_MERGE_CANDIDATES_PER_PHOTO: Int = 2_500_000

    /**
     * Merge work per photo: kept boxes the merge index returns for candidates (pair checks), plus the index's cell
     * lookups and skipped entries, so the bound covers all of the index's work (ADR 0012 decisions 3 and 6), plus
     * the evaluations of the part test (ADR 0013). Exceeding it is `TOO_MANY_MERGE_CANDIDATES`.
     *
     * A generous technical bound (96 units per candidate at [MAX_MERGE_CANDIDATES_PER_PHOTO]) that keeps the merge
     * of one photo bounded even when the index degrades to pairwise checks; exceeding it fails closed with
     * `TOO_MANY_MERGE_CANDIDATES` before any pixel is written.
     */
    const val MAX_MERGE_PAIR_CHECKS_PER_PHOTO: Long = 240_000_000L

    /**
     * In-pass NMS work per photo, summed over all passes: one unit per pair of boxes compared (a candidate against a
     * kept box). Exceeding it is `TOO_MANY_NMS_PAIR_CHECKS`, before any pixel is written, with a message that gives
     * the pair checks reached, the score threshold and the advice to check the threshold with faceblur-eval.
     *
     * Final value: a technical bound of the same order as the merge work bound, so that output with far too many
     * boxes (a score threshold far below the model's useful range) is stopped instead of running for minutes.
     */
    const val MAX_NMS_PAIR_CHECKS_PER_PHOTO: Long = 240_000_000L

    /** Contract run time limit at creation, in milliseconds. */
    const val CONTRACT_RUN_MILLIS: Long = 30_000L

    /** Smallest per-pass run timeout (Android and JVM), in milliseconds. */
    const val MIN_RUN_TIMEOUT_MILLIS: Long = 2_000L

    /** The per-pass run timeout is this many times the contract run's duration (at least [MIN_RUN_TIMEOUT_MILLIS]). */
    const val RUN_TIMEOUT_FACTOR: Long = 20L
}

/**
 * Fail closed (second layer behind the model output checks): a detection with a non-finite score or coordinate, or
 * with edges that are not strictly ordered, is reported as [CoreFailure.InvalidDetection] instead of being dropped (a
 * dropped face would stay visible).
 */
internal fun DetectedFace.requireValidDetection() {
    val r = rect
    if (score.isNaN() || score.isInfinite()) throw CoreFailure.InvalidDetection("non-finite score")
    if (!(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite())) {
        throw CoreFailure.InvalidDetection("non-finite box")
    }
    if (!(r.right > r.left && r.bottom > r.top)) throw CoreFailure.InvalidDetection("inverted box")
}

/** Result of running one engine (global pass + tiles + merge) on one full image. */
internal data class DetectionOutcome(
    /** Merged faces in FULL-image pixel coordinates. */
    val faces: List<DetectedFace>,
    /** Number of engine.detect() calls (1 global + N tiles). */
    val passes: Int,
    /** Part masks of merged faces (earlier states of grown boxes), grouped by face in face order. */
    val parts: List<MergedPart> = emptyList(),
    /** Indexes into [faces] of the faces drawn as one rectangle cover (ADR 0013 decision 1). */
    val covers: Set<Int> = emptySet(),
    /** Diagnostics: candidates that entered the merge, its pair checks and its counted work. */
    val mergeCandidates: Int = 0,
    val mergePairChecks: Long = 0L,
    val mergeWork: Long = 0L,
    /** Diagnostics: in-pass NMS pair checks of this photo (all passes). */
    val nmsWork: Long = 0L,
)
