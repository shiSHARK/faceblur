// SPDX-License-Identifier: Apache-2.0
@file:OptIn(NativeRuntimeApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.internal.runtime.AfterCallSeam
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The iOS after-call seam (ADR 0004 decision 4): one `GC.collect()` after each public `FaceMasker` call that did
 * work, so that the Kotlin wrappers of a call are freed before the next photo ("between photos"). Internal only.
 */
internal object IosAfterCall : AfterCallSeam {
    override fun afterCall() = GC.collect()
}
