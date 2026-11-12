// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.Os

/**
 * `android.system.Os.setenv`/`getenv`: the real process environment that ONNX Runtime's native code reads.
 *
 * Phone-only (Robolectric's `Os.setenv` does nothing and `getenv` returns null): this file holds only the two calls;
 * the fail-closed check is [AndroidBinding.disableOrtTelemetry] (host-tested).
 */
internal object OsEnvironment : ProcessEnvironment {
    override fun set(name: String, value: String) = Os.setenv(name, value, true)

    override fun get(name: String): String? = Os.getenv(name)
}
