// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.ios.IosAfterCall
import io.github.shishark.faceblur.ios.IosImageCodec
import io.github.shishark.faceblur.ios.IosMemoryProbe
import io.github.shishark.faceblur.ios.IosModelFileReader
import io.github.shishark.faceblur.ios.IosPathWriter

private val IOS_DEFAULTS =
    PlatformDefaults(
        modelFiles = IosModelFileReader,
        memoryProbe = IosMemoryProbe,
        imageCodec = IosImageCodec,
        targetWriter = IosPathWriter(),
        afterCall = IosAfterCall,
    )

/**
 * iOS: the model-file reader, the memory probe and the after-call garbage collection (ADR 0004 decision 4) need no
 * setup; the ONNX Runtime binding is registered by the Swift glue (`FaceBlurIos.registerOnnxRuntime`, ADR 0010).
 * File mode: the ImageIO decoder and encoder ([IosImageCodec]) and the POSIX path writer ([IosPathWriter]). No
 * `HiddenFromJava` here: it is an optional expectation with no iOS actual, which Kotlin allows only in common sources
 * (and there is no Java on iOS).
 */
internal actual fun platformDefaults(): PlatformDefaults = IOS_DEFAULTS
