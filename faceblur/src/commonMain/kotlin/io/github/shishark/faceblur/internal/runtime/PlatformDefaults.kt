// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.mask.MemoryProbe

/**
 * The bindings a platform provides without any setup call (ADR 0015 decision 1): one nullable value per
 * [FaceBlurRuntime] slot. A registry lookup returns the explicitly installed value if there is one, otherwise the
 * value here, so an installation (tests, the iOS bridge registration of ADR 0010) always wins.
 *
 * The values are stateless objects created by the class loader on first use; nothing runs at app start. TELEMETRY
 * ORDER: nothing reachable from a [PlatformDefaults] may reference an ONNX Runtime type; the runtime binding sets
 * `ORT_DISABLE_TELEMETRY=1` and reads it back as the first step of opening a session, and touches ONNX Runtime types
 * only after that (ADR 0007 decision 2).
 */
internal class PlatformDefaults private constructor(
    /** The ONNX Runtime binding (`FaceMasker` creation). */
    @get:HiddenFromJava val runtime: ModelRuntime?,
    /** The reader behind `ModelSource.fromFile`. */
    @get:HiddenFromJava val modelFiles: ModelFileReader?,
    /** The mask stage's memory probe (ADR 0013 decision 6); without one there is no memory pre-check. */
    @get:HiddenFromJava val memoryProbe: MemoryProbe?,
    /** The platform SHA-256; `null` keeps the common implementation. */
    @get:HiddenFromJava val digest: ModelDigest?,
    /** File mode: the platform decoder and encoder (ADR 0015 decision 4). */
    @get:HiddenFromJava val imageCodec: ImageCodec?,
    /** File mode: the platform writer of finished files (ADR 0015 decision 4). */
    @get:HiddenFromJava val targetWriter: TargetWriter?,
    /**
     * Runs after each public `FaceMasker` call that did work (ADR 0004 decision 4): `GC.collect()` on iOS, `null`
     * (nothing runs) on Android and the JVM. Not the memory probe's `collect()`, which requests a collection on Android.
     */
    @get:HiddenFromJava val afterCall: AfterCallSeam?,
) {
    /**
     * Kotlin `internal` is public in JVM bytecode. So that Java code can neither make nor read a [PlatformDefaults]
     * (and through it reach a platform binding), the constructor is private and every member, including this
     * factory and [NONE], is synthetic on the JVM and Android ([HiddenFromJava]); the JVM boundary test checks it.
     */
    companion object {
        /** No platform binding at all: the JVM (tests install theirs), and the test switch's "defaults off". */
        @get:HiddenFromJava
        val NONE: PlatformDefaults = PlatformDefaults(null, null, null, null, null, null, null)

        /** Makes the defaults of a platform (or a test's fake defaults); a value left out is `null`. */
        @HiddenFromJava
        operator fun invoke(
            runtime: ModelRuntime? = null,
            modelFiles: ModelFileReader? = null,
            memoryProbe: MemoryProbe? = null,
            digest: ModelDigest? = null,
            imageCodec: ImageCodec? = null,
            targetWriter: TargetWriter? = null,
            afterCall: AfterCallSeam? = null,
        ): PlatformDefaults =
            PlatformDefaults(runtime, modelFiles, memoryProbe, digest, imageCodec, targetWriter, afterCall)
    }
}

/**
 * This platform's defaults (ADR 0015 decision 1). Android: the Android bindings; JVM: [PlatformDefaults.NONE] (tests
 * and tools install their bindings explicitly); iOS: [PlatformDefaults.NONE] until the iOS stories fill it (the Swift
 * bridge registers the runtime, ADR 0010). Must return the same immutable value on every call. Synthetic on the JVM
 * and Android ([HiddenFromJava]), so Java code cannot call it; the JVM and Android actuals carry the same annotation
 * (the iOS actual cannot: an optional expectation without an actual may only be used in common sources).
 */
@HiddenFromJava
internal expect fun platformDefaults(): PlatformDefaults
