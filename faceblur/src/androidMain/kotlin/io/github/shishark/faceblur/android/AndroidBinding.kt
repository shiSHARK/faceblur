// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed

/**
 * The telemetry rule of the Android binding (ADR 0007 decision 2). The bindings themselves (runtime, model-file
 * reader, memory probe, file-mode codec and writer) are the platform defaults (`PlatformDefaults.android.kt`, ADR
 * 0015 decision 1): there is no setup call. [AndroidModelRuntime] calls [disableOrtTelemetry] as the first step of
 * opening a session, before any ONNX Runtime class is initialised.
 */
internal object AndroidBinding {
    /** The environment variable ONNX Runtime reads when its environment is created (ADR 0007 decision 2). */
    const val TELEMETRY_VARIABLE: String = "ORT_DISABLE_TELEMETRY"

    /**
     * Seam for the process environment: production uses `android.system.Os`; host tests (no libcore natives) pass a
     * recorder. Synthetic, so Java code cannot replace it.
     */
    @get:HiddenFromJava
    @set:HiddenFromJava
    internal var environment: ProcessEnvironment = OsEnvironment

    /**
     * Sets `ORT_DISABLE_TELEMETRY=1` in the process environment and reads it back. Fails closed with
     * [RuntimeOptionFailed] (creation then throws `RuntimeUnavailable`) when it cannot be set: ONNX Runtime must never
     * start without it.
     */
    fun disableOrtTelemetry() {
        val env = environment
        val value =
            try {
                env.set(TELEMETRY_VARIABLE, "1")
                env.get(TELEMETRY_VARIABLE)
            } catch (e: Exception) {
                // Any failure (ErrnoException from setenv, or anything else) means the variable is not known to be
                // set: fail closed.
                null
            }
        if (value != "1") throw RuntimeOptionFailed()
    }
}

/** The process environment (a seam so host tests can run without the platform's native `Os`). */
internal interface ProcessEnvironment {
    fun set(name: String, value: String)

    fun get(name: String): String?
}
