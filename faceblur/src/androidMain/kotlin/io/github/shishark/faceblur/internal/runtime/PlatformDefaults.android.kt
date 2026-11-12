// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.android.AndroidHeapProbe
import io.github.shishark.faceblur.android.AndroidImageCodec
import io.github.shishark.faceblur.android.AndroidModelFileReader
import io.github.shishark.faceblur.android.AndroidModelRuntime
import io.github.shishark.faceblur.android.AndroidTargetWriter

/**
 * Android (ADR 0015 decision 1): the ONNX Runtime binding, the model-file reader, the remaining-heap memory probe and
 * the file-mode decoder and writer, with no setup call. The common SHA-256 stays (no platform digest).
 *
 * TELEMETRY ORDER: none of these objects references an ONNX Runtime type; [AndroidModelRuntime] sets and reads back
 * `ORT_DISABLE_TELEMETRY=1` before it reaches one (ADR 0007 decision 2).
 */
private val ANDROID_DEFAULTS: PlatformDefaults =
    PlatformDefaults(
        runtime = AndroidModelRuntime,
        modelFiles = AndroidModelFileReader,
        memoryProbe = AndroidHeapProbe,
        imageCodec = AndroidImageCodec.DEFAULT,
        targetWriter = AndroidTargetWriter.DEFAULT,
    )

@HiddenFromJava
internal actual fun platformDefaults(): PlatformDefaults = ANDROID_DEFAULTS
