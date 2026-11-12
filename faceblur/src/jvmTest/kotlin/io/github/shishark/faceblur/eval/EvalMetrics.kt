// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskGeometry
import kotlin.math.max

/** The counts of one image at one threshold. */
internal data class ImageCounts(
    /** Ground-truth faces that are not ignored. */
    val gtFaces: Int,
    /** Of those, faces with at least the coverage fraction of their box under the masks. */
    val protectedFaces: Int,
    /** Detections matched one-to-one to a ground-truth face at the match IoU. */
    val truePositives: Int,
    /** Detections matched to no ground-truth face and to no ignored face: false masks. */
    val falsePositives: Int,
)

/** The totals of one threshold over all measured images. */
internal data class Totals(
    val images: Int = 0,
    val imagesWithFaces: Int = 0,
    val imagesFullyProtected: Int = 0,
    val gtFaces: Int = 0,
    val protectedFaces: Int = 0,
    val truePositives: Int = 0,
    val falsePositives: Int = 0,
    val failedImages: Int = 0,
    val unreadableImages: Int = 0,
) {
    operator fun plus(c: ImageCounts) = copy(
        images = images + 1,
        imagesWithFaces = imagesWithFaces + if (c.gtFaces > 0) 1 else 0,
        imagesFullyProtected = imagesFullyProtected + if (c.gtFaces > 0 && c.protectedFaces == c.gtFaces) 1 else 0,
        gtFaces = gtFaces + c.gtFaces,
        protectedFaces = protectedFaces + c.protectedFaces,
        truePositives = truePositives + c.truePositives,
        falsePositives = falsePositives + c.falsePositives,
    )

    /** A failed image counts as measured, with every face missed (fail closed: an error protects nothing). */
    fun failed(gtFacesOfImage: Int) = copy(
        images = images + 1,
        imagesWithFaces = imagesWithFaces + if (gtFacesOfImage > 0) 1 else 0,
        gtFaces = gtFaces + gtFacesOfImage,
        failedImages = failedImages + 1,
    )

    /** A listed photo that could not be read: counted, with every face missed (fail closed). */
    fun unreadable(gtFacesOfImage: Int) = copy(
        images = images + 1,
        imagesWithFaces = imagesWithFaces + if (gtFacesOfImage > 0) 1 else 0,
        gtFaces = gtFaces + gtFacesOfImage,
        unreadableImages = unreadableImages + 1,
    )

    /** Protected faces / ground-truth faces (0 without faces). */
    val protection: Double get() = ratio(protectedFaces, gtFaces)

    /** Matched detections / ground-truth faces. */
    val recall: Double get() = ratio(truePositives, gtFaces)

    /** Matched detections / (matched + false). */
    val precision: Double get() = ratio(truePositives, truePositives + falsePositives)

    /** False masks per image that was read (unreadable photos have no detections). */
    val falseMasksPerImage: Double get() = ratio(falsePositives, images - unreadableImages)

    private fun ratio(a: Int, b: Int) = if (b == 0) 0.0 else a.toDouble() / b
}

/**
 * The measures of faceblur-eval (DX-MA-001): one-to-one greedy matching in descending IoU order at the match IoU;
 * a detection that matches no face but hits an ignored face (IoU at least the match IoU, or at least
 * max(match IoU, 0.5) of its own area inside it) is neither a match nor a false mask; a face is protected when at
 * least the coverage fraction of a 16 x 16 grid of sample points (cell centres) over its box lies inside the opaque
 * part of any mask.
 */
internal object EvalMetrics {
    const val COVERAGE_SAMPLES: Int = 16

    fun image(
        gt: List<RectF>,
        ignored: List<Boolean>,
        detections: List<RectF>,
        masks: List<MaskGeometry>,
        matchIou: Float,
        coverage: Float,
    ): ImageCounts {
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (d in detections.indices) {
            for (g in gt.indices) {
                if (ignored[g]) continue
                val iou = detections[d].iou(gt[g])
                if (iou > 0f && iou >= matchIou) pairs += Triple(iou, d, g)
            }
        }
        pairs.sortWith(
            compareByDescending<Triple<Float, Int, Int>> {
                it.first
            }.thenBy { it.second }.thenBy { it.third },
        )
        val gtTaken = BooleanArray(gt.size)
        val detTaken = BooleanArray(detections.size)
        var tp = 0
        for ((_, d, g) in pairs) {
            if (gtTaken[g] || detTaken[d]) continue
            gtTaken[g] = true
            detTaken[d] = true
            tp++
        }
        var fp = 0
        for (d in detections.indices) {
            if (detTaken[d]) continue
            if (gt.indices.none { ignored[it] && hitsIgnored(detections[d], gt[it], matchIou) }) fp++
        }
        var protectedFaces = 0
        for (g in gt.indices) if (!ignored[g] && covered(gt[g], masks) >= coverage) protectedFaces++
        return ImageCounts(ignored.count { !it }, protectedFaces, tp, fp)
    }

    /** Fraction (0 to 1) of the [COVERAGE_SAMPLES]² sample points of [box] inside the opaque part of a mask. */
    fun covered(box: RectF, masks: List<MaskGeometry>): Double {
        if (box.isEmpty) return 0.0
        val near = masks.filter { m ->
            m.cx + m.rx > box.left && m.cx - m.rx < box.right && m.cy + m.ry > box.top && m.cy - m.ry < box.bottom
        }
        if (near.isEmpty()) return 0.0
        var inside = 0
        for (j in 0 until COVERAGE_SAMPLES) {
            val py = box.top + (j + 0.5f) * box.height / COVERAGE_SAMPLES
            for (i in 0 until COVERAGE_SAMPLES) {
                val px = box.left + (i + 0.5f) * box.width / COVERAGE_SAMPLES
                if (near.any { it.normalizedRadius(px, py) <= 1f }) inside++
            }
        }
        return inside.toDouble() / (COVERAGE_SAMPLES * COVERAGE_SAMPLES)
    }

    private fun hitsIgnored(det: RectF, region: RectF, matchIou: Float): Boolean {
        val inter = det.intersectionArea(region)
        if (inter <= 0f) return false
        return det.iou(region) >= matchIou || (det.area > 0f && inter / det.area >= max(matchIou, 0.5f))
    }
}
