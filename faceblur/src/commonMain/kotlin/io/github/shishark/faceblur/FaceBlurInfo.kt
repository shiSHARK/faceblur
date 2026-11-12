// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * Static information about the FaceBlur library.
 *
 * Placeholder entry point used to verify the build, publishing and CI set-up; the real API follows.
 */
public object FaceBlurInfo {
    /** Human-readable library name. */
    public const val NAME: String = "FaceBlur"

    /** SPDX identifier of the library licence. */
    public const val LICENSE: String = "Apache-2.0"

    /** The library version (equal to the build's VERSION_NAME; a test keeps them in step). */
    internal const val VERSION: String = "0.1.0"

    /** Returns a one-line description, for example `FaceBlur (Apache-2.0)`. */
    public fun describe(): String = "$NAME ($LICENSE)"
}
