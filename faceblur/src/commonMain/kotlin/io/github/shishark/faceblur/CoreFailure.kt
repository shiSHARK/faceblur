// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * Typed errors of the detection and masking core (fail closed). The core never returns an image in which a
 * detected or requested face was silently left unmasked; it raises one of these instead.
 *
 * Internal: the public entry points (`FaceMasker`, ADR 0002 decision 9) map them to `FaceMaskException`:
 * `InvalidSetting` → `InvalidConfiguration`; `ImageTooLarge` → `ImageTooLarge`; `WriteNotApplied` and
 * `WriteInterrupted` → `MaskWriteFailed`; `MaskWorkLimit` → `InvalidConfiguration("masks")` (masks from the model:
 * `ModelOutputInvalid` with `TOO_MANY_MASKS`, mapped by the caller); `NotEnoughMemory` → `NotEnoughMemory`;
 * `InvalidDetection`, `InvalidFaceBox` and `InvalidMask` → `Internal`.
 * Caller-supplied masks are rejected by their factories with `InvalidConfiguration`; the core's own refusal is a
 * second layer and surfaces as `Internal`. Messages are fixed text plus parameter names or image dimensions; never
 * pixel data, paths or face coordinates.
 */
internal sealed class CoreFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** An internal detection or mask setting is outside its allowed range. [parameter] is the property name. */
    class InvalidSetting(val parameter: String, allowed: String) :
        CoreFailure("Invalid setting '$parameter': allowed $allowed")

    /** A face box to be masked has a non-finite coordinate or score, or inverted edges. */
    class InvalidFaceBox(reason: String) : CoreFailure("Invalid face box: $reason")

    /** A mask has a non-finite centre or radius, or is smaller than one pixel, so it cannot cover anything. */
    class InvalidMask(reason: String) : CoreFailure("Invalid mask: $reason")

    /** The detector produced a non-finite score or box (a runtime or model fault). */
    class InvalidDetection(reason: String) : CoreFailure("Invalid detector output: $reason")

    /** The image has more pixels than a pixel block can address ([Int.MAX_VALUE]), or a negative size. */
    class ImageTooLarge(val width: Int, val height: Int) :
        CoreFailure("Image of $width x $height pixels cannot be processed (at most ${Int.MAX_VALUE} pixels)")

    /**
     * The summed working-rectangle area of the masks exceeds [io.github.shishark.faceblur.mask.MaskStageLimits]
     * (ADR 0013 decision 2); checked before the mask stage reads or writes any pixel.
     */
    class MaskWorkLimit(
        /** The summed working-rectangle area of the masks, in pixels. */
        val area: Long,
        /** The limit it exceeded, in pixels. */
        val limit: Long,
    ) : CoreFailure("The masks' summed working area exceeds the per-image limit")

    /**
     * The mask stage's working area cannot be reserved (ADR 0013 decision 6): larger than the platform allows, its
     * size overflows, or allocating it failed ([cause], an out-of-memory error). Nothing of the image was read or
     * written.
     */
    class NotEnoughMemory(cause: Throwable?) :
        CoreFailure("Not enough memory for the mask stage's working area; the image is untouched", cause)

    /**
     * Reading or writing the caller's image failed during the final copy of the mask stage ([cause]). The image may
     * be partly masked and must be discarded.
     */
    class WriteInterrupted(cause: Throwable) :
        CoreFailure("Copying the masks into the image failed; discard the image", cause)

    /**
     * Pixels written to a caller's image during the final copy did not read back as written. The image may be partly
     * masked and must be discarded.
     */
    class WriteNotApplied :
        CoreFailure("Mask pixels written to the image did not read back as written; discard the image")
}

/**
 * Errors that must propagate unchanged (ADR 0002 decision 9): on the JVM and Android the `VirtualMachineError`
 * family. Matched by class name because common code cannot name these types; on Kotlin/Native no name matches, so
 * every Throwable is wrapped there, as the iOS rule requires.
 */
internal fun isFatal(t: Throwable): Boolean = t::class.qualifiedName in JVM_FATAL_ERRORS

private val JVM_FATAL_ERRORS =
    setOf(
        "java.lang.OutOfMemoryError",
        "java.lang.StackOverflowError",
        "java.lang.InternalError",
        "java.lang.UnknownError",
    )
