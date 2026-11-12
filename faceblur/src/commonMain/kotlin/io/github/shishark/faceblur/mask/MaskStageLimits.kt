// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

/**
 * Every cap of the mask stage in one place (ADR 0013). The public KDoc of the in-place calls states the worst case
 * that these values give ([WORST_CASE_BYTES_PER_PIXEL] × image pixels + [WORST_CASE_FIXED_BYTES]); a test derives
 * the bound from the values below and checks the documented text, so a change of a cap that breaks the text fails.
 */
internal object MaskStageLimits {
    /**
     * Masks per image (faces plus part masks). Final: a generous technical limit that bounds the per-mask arrays of
     * the working area; a plan above it fails closed before any pixel is read, with `TOO_MANY_MASKS` for masks from
     * the model (which names the mask count and the score threshold) or `InvalidConfiguration("masks")` for an app's
     * plan. One constant.
     */
    const val MAX_MASKS_PER_IMAGE: Int = 65_536

    /**
     * The summed working-rectangle area of all masks, clipped to the image, may be at most this many times
     * max(image pixels, [MIN_AREA_BASE]). Final: a technical limit that keeps the stage's work linear in the image
     * size even for heavily overlapping masks (the stage's time grows linearly with this factor); exceeding it
     * fails closed before any pixel is read. Only plans that
     * cover the same pixels many times over come near it.
     */
    const val MAX_MASK_AREA_FACTOR: Long = 32L

    /** The smallest image area the area limit is computed from (1,048,576 pixels). */
    const val MIN_AREA_BASE: Long = 1_048_576L

    /**
     * A face whose box grew more often than this in the merge is drawn as one rectangle cover (ADR 0013 decision 1).
     * Final: a generous technical limit on the part masks per face; the cover contains every earlier state of the
     * box, so a capped face loses no masked pixel.
     */
    const val MAX_PART_MASK_GROWTH: Int = 32

    /** Base cells of a SMOOTH_FILL grid; above it the grid spacing is raised (ADR 0013 decision 6). PROVISIONAL. */
    const val MAX_SMOOTH_FILL_CELLS: Int = 524_288

    /** iOS: memory kept free beyond the working area when the available figure is checked. PROVISIONAL. */
    const val AVAILABLE_MEMORY_MARGIN: Long = 64L * 1024 * 1024

    /** Side of a shadow and coverage tile in pixels. */
    const val TILE: Int = 64

    /** Longest run of pixels passed to `readPixels` or `writePixels` by the stage's row reads and the copy. */
    const val MAX_RUN: Int = 64

    /** The merge counts the growth steps of a box only up to this value (one more than the cap is enough). */
    const val GROWTH_SATURATION: Int = MAX_PART_MASK_GROWTH + 1

    /** The documented worst case: at most this many bytes per image pixel ... */
    const val WORST_CASE_BYTES_PER_PIXEL: Double = 4.3

    /** ... plus this many bytes (32 MiB). */
    const val WORST_CASE_FIXED_BYTES: Long = 32L * 1024 * 1024

    /**
     * A sizing sample for tests, computed by the library's own sizing ([MaskEngine.workingAreaBytes]) and pinned by
     * a test. It is not published as a figure: the KDoc and the guides state the worst case only. A 4,000 × 3,000
     * photo (12 MP) with three faces of square boxes of [TYPICAL_FACE_SIDES] px, centred at (1,000, 1,500),
     * (2,000, 1,500) and (3,000, 1,500), default masking (GRADIENT_OVAL, ELLIPSE, expansion 1.6 / 1.8).
     */
    val TYPICAL_FACE_SIDES: List<Float> = listOf(150f, 300f, 600f)

    /** Working-area bytes for each of [TYPICAL_FACE_SIDES], in that order. */
    val TYPICAL_BYTES: List<Long> = listOf(1_710_239L, 5_020_519L, 16_338_075L)
}
