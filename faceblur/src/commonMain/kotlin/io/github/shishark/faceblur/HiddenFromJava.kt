// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMultiplatform::class)

package io.github.shishark.faceblur

/**
 * Marks an internal library function as synthetic on JVM-based targets (`kotlin.jvm.JvmSynthetic`), so Java source
 * cannot call it. Targets without an actual (iOS) ignore it; Kotlin allows it only in common sources there, never
 * in iosMain. JVM: jvmMain; Android: androidMain.
 */
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
internal expect annotation class HiddenFromJava()
