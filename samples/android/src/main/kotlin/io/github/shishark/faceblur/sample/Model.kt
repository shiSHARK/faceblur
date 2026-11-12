// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

/** Where a mask comes from. */
enum class Origin { DETECTED, ADDED }

enum class MaskForm { ELLIPSE, RECTANGLE }

/**
 * One mask of the edit list, in working-image pixels. [handle] is the engine's own object for it (the library's
 * `Mask`); the controller never looks inside.
 */
class MaskItem(
    val id: Int,
    val origin: Origin,
    val form: MaskForm,
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val handle: Any,
) {
    val bounds: Box get() = Box(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY)
}

/** A face the detector found: its box in working-image pixels and its score. */
class FaceView(val index: Int, val box: Box, val score: Float)

/** The result of a detect-only run. [preview] is the engine's picture of the photo (a Bitmap on Android). */
class Analysis(
    val width: Int,
    val height: Int,
    val faces: List<FaceView>,
    val masks: List<MaskItem>,
    val preview: Any?,
    val handle: Any,
)

/** What a saved file reports back. [preview] is the picture read back from the saved file. */
class SaveOutcome(val faces: Int, val masks: Int, val preview: Any?)

/** A model file the user picked, hashed by the demo and waiting for the user's confirmation. */
class ModelCandidate(val name: String, val sizeBytes: Long, val sha256: String, val handle: Any) {
    /** The SHA-256 in lines of 16 hex digits, so that a person can compare it piece by piece. */
    val sha256Lines: List<String> get() = sha256.chunked(HASH_LINE)

    private companion object {
        const val HASH_LINE = 16
    }
}

/** The output size choices of the demo (the library option `maxOutputLongSide`; 0 keeps the working size). */
enum class OutputOption(val maxOutputLongSide: Int) {
    SAME_SIZE(0),
    LONG_SIDE_2048(2_048),
    LONG_SIDE_1024(1_024),
}

enum class FailureKind {
    MODEL_TOO_LARGE,
    MODEL_UNREADABLE,
    MODEL_INTEGRITY,
    MODEL_REJECTED,
    TOO_MANY_MASKS,
    TOO_MANY_PAIR_CHECKS,
    NOT_ENOUGH_MEMORY,
    IMAGE_TOO_LARGE,
    UNSUPPORTED_IMAGE,
    SOURCE_CHANGED,
    TARGET_REFUSED,
    IO,
    CANCELLED,
    OTHER,
}

/**
 * A failure the screen can explain. [detail] is the library's own message, shown unmodified in a details block (the
 * demo does not parse it); it never holds a path or a Uri. [leftover] is true when a file that the save created could
 * not be removed after the failure.
 */
class DemoFailure(val kind: FailureKind, val detail: String? = null, val leftover: Boolean = false) :
    Exception(kind.name)

/** The engine does the work that needs the library and the platform; the controller drives it off the main thread. */
interface Engine<U> {
    /** Reads and hashes the model file; keeps nothing on disk. */
    fun readModel(source: U): ModelCandidate

    /** Loads the model the user confirmed and creates the masker. Not cancellable once the runtime starts. */
    fun loadModel(candidate: ModelCandidate)

    /** Detect only: nothing is written. */
    fun analyse(source: U, output: OutputOption, cancelled: () -> Boolean): Analysis

    /** A mask for a box the user placed, in working-image pixels of [analysis]. */
    fun manualMask(analysis: Analysis, id: Int, box: Box): MaskItem

    /**
     * Writes the photo with exactly [masks] to [target] (a new, empty document). On any failure nothing stays at
     * [target]: the engine removes what the call created, and reports [DemoFailure.leftover] if it cannot.
     */
    fun save(
        source: U,
        target: U,
        analysis: Analysis,
        masks: List<MaskItem>,
        output: OutputOption,
        cancelled: () -> Boolean,
    ): SaveOutcome
}
