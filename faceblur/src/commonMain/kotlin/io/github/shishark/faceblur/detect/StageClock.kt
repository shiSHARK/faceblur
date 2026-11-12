// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import kotlin.time.TimeSource

/**
 * Diagnostics: total time per named stage of a detection, e.g. the tiler's "crop" / "engine" / "merge" and, inside
 * "engine", YuNet's "yunet.pixels" / "yunet.tensor" / "yunet.inference" / "yunet.decode" (and the ONNX runner's
 * "ort.*" inside "yunet.inference"). Stages can be nested, so they do not add up to the detection time.
 *
 * Measuring costs two monotonic clock reads per stage. Not thread-safe: one clock per detector, used from the
 * thread that runs the detection.
 */
internal class StageClock {
    private val nanos = LinkedHashMap<String, Long>()

    /** Runs [block] and adds its duration to [stage] (also when it throws). */
    inline fun <T> measure(stage: String, block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            add(stage, mark.elapsedNow().inWholeNanoseconds)
        }
    }

    fun add(stage: String, nanoseconds: Long) {
        nanos[stage] = (nanos[stage] ?: 0L) + nanoseconds
    }

    fun reset() = nanos.clear()

    /** Totals since the last [reset] in milliseconds, in the order the stages were first seen. */
    fun snapshotMs(): Map<String, Double> = nanos.mapValuesTo(LinkedHashMap()) { it.value / 1_000_000.0 }
}

/** [StageClock.measure] when [clock] is set, otherwise just [block]. */
internal inline fun <T> timed(clock: StageClock?, stage: String, block: () -> T): T =
    if (clock == null) block() else clock.measure(stage, block)
