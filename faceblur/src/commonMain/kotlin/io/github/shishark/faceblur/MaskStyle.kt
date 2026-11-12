// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * How the inside of a face mask is filled. Every style is **opaque**: inside the mask every pixel is replaced by the
 * fill and is fully opaque (alpha 255), computed without using any pixel value inside any face mask, so nothing the
 * face showed can be recovered from it. A style may vary the fill colour, never its opacity. The only partly
 * transparent pixels are in the soft edge, which lies outside the mask.
 *
 * There is deliberately no blur, pixelation or any other reversible, transparent or partial style.
 */
public enum class MaskStyle {
    /**
     * Opaque oval: a solid centre colour whose colour (not its opacity) changes towards the colours sampled just
     * outside the mask, so the edge blends into the photo. The default style.
     */
    GRADIENT_OVAL,

    /** Fills the mask smoothly from the colours around it (a membrane fill from the mask's surroundings). */
    SMOOTH_FILL,

    /** A single solid colour. */
    SOLID,
}
