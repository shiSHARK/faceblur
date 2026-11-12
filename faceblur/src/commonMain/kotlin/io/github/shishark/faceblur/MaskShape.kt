// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * Outline of a mask. Both shapes are opaque inside and get the same outward soft edge.
 */
public enum class MaskShape {
    /** An ellipse around the face (the default and the measured configuration). */
    ELLIPSE,

    /** A rectangle with the same radii as the ellipse; it contains the ellipse, so it covers more. */
    RECTANGLE,
}
