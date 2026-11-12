// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava

/** JVM (tests and tools, not published): no defaults; the tests and the evaluation tool install their bindings. */
@HiddenFromJava
internal actual fun platformDefaults(): PlatformDefaults = PlatformDefaults.NONE
