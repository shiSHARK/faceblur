// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Opens one ONNX Runtime session per `FaceMasker` (ADR 0012 decisions 4 and 8; ADR 0010 for iOS). The platform
 * binding:
 * - creates the session only from [DetectorModel.verifiedBytes] (Android and JVM: a direct buffer made once per model
 *   through [DetectorModel.platformForm], one `duplicate()` per session; iOS: the carrier file of ADR 0010 decision 6);
 * - sets exactly the locked session options of ADR 0012 decision 4 (ONNX format, released opsets only, graph
 *   optimisation "all", logging `fatal`, XNNPACK with CPU fallback, intra-op pool 1 thread, spinning off) and throws
 *   [RuntimeOptionFailed] when one cannot be set;
 * - throws [SessionRejected] when the runtime refuses the model;
 * - never puts a runtime exception or its text into anything it throws (runtime messages can contain paths).
 * [threads] is the configured `onnxThreads` (0 = automatic).
 */
internal fun interface ModelRuntime {
    fun open(model: DetectorModel, threads: Int): ModelSession
}

/** One input or output as the runtime reports it; [elementType] and [shape] are null when it reports names only. */
internal class SessionTensor(val name: String, val elementType: Int?, val shape: List<Long?>?)

/**
 * One output of a pass: the ONNX element type, the shape the runtime reports and the values ([data] is null when the
 * type is not float32 or the element count exceeds the cap given to [ModelSession.run]).
 */
internal class PassOutput(val elementType: Int, val shape: LongArray, val data: FloatArray?)

/** A runtime session. Not thread-safe: the `FaceMasker` makes at most one call at a time. */
internal interface ModelSession {
    /** The runtime's version string, read from the runtime. */
    val runtimeVersion: String

    /** "XNNPACK" or "CPU". */
    val executionProvider: String

    /** The model inputs the runtime reports (initializers excluded). */
    val inputs: List<SessionTensor>

    /** The model outputs the runtime reports. */
    val outputs: List<SessionTensor>

    /**
     * Runs one pass on [input] (shape [inputShape]) and returns exactly the requested [outputNames] it produced.
     * An output with more than [maxElements] elements is returned without data. A run still going after
     * [timeoutMillis] (0 = no limit) is stopped and [SessionTimeout] is thrown; any other failure throws
     * [SessionRunFailed]. Platforms that cannot stop a run (iOS 1.20.0) ignore [timeoutMillis].
     */
    fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput>

    fun close()
}

/** Typed session failures; never carry a runtime exception or its text. */
internal sealed class SessionFailure(message: String) : RuntimeException(message)

/** The runtime refused the model when the session was created. */
internal class SessionRejected : SessionFailure("the runtime refused the model")

/** A required session option could not be set. */
internal class RuntimeOptionFailed : SessionFailure("a required session option could not be set")

/** A pass was stopped by its time limit. */
internal class SessionTimeout : SessionFailure("the run was stopped by its time limit")

/** A pass failed in the runtime. */
internal class SessionRunFailed : SessionFailure("the run failed in the runtime")

/**
 * Reads a model file for `ModelSource.fromFile` under the platform's read rules (ADR 0012 decision 4, read rules):
 * the last path component must be a regular file and not a symbolic link; at most [maxBytes] + 1 bytes are read.
 * Throws `FaceMaskException.ModelRejected(NOT_REGULAR_FILE)` or `FaceMaskException.IoFailed` without the path.
 */
internal fun interface ModelFileReader {
    fun read(path: String, maxBytes: Long): ByteArray
}

/**
 * A platform SHA-256 (Android/JVM `MessageDigest`, iOS CommonCrypto), used instead of the common implementation when
 * installed. It must return the 32-byte FIPS 180-4 digest of exactly [bytes] and must not keep or change them; an
 * equivalence test against the common implementation is required for every installed digest.
 */
internal fun interface ModelDigest {
    fun sha256(bytes: ByteArray): ByteArray
}

/**
 * The internal, iOS-only after-call seam (ADR 0004 decision 4): the common `FaceMasker` runs it once after each
 * public call that did work, on success and on a thrown error, outside the stage timings. iOS sets it to
 * `GC.collect()`; Android and the JVM have none, so nothing runs there.
 */
internal fun interface AfterCallSeam {
    fun afterCall()
}

/**
 * The process-wide platform bindings. Each lookup returns the explicitly installed value if there is one, otherwise
 * the platform default ([platformDefaults], ADR 0015 decision 1), so an installation always wins and an
 * `install…(null)` or [uninstall] goes back to the default. Platforms install bindings explicitly where they have no
 * default (the iOS bridge registration, the JVM test runtime); the Android bindings are defaults, so apps call
 * nothing. The slots: the runtime, the model-file reader, the memory probe ([installMemoryProbe]: without it the mask
 * stage has no memory pre-check), optionally the SHA-256, and the file-mode codec and target writer. Without a
 * runtime, creating a `FaceMasker` throws `RuntimeUnavailable`. Every member is synthetic on the JVM and Android, so
 * Java code cannot replace a binding.
 */
internal object FaceBlurRuntime {
    private val runtime = AtomicReference<ModelRuntime?>(null)
    private val files = AtomicReference<ModelFileReader?>(null)
    private val digest = AtomicReference<ModelDigest?>(null)
    private val memory = AtomicReference<MemoryProbe?>(null)
    private val codec = AtomicReference<ImageCodec?>(null)
    private val writer = AtomicReference<TargetWriter?>(null)
    private val defaultsOverride = AtomicReference<PlatformDefaults?>(null)

    /** The platform defaults in effect: the test switch's value if set, otherwise [platformDefaults]. */
    private fun defaults(): PlatformDefaults = defaultsOverride.load() ?: platformDefaults()

    /**
     * Test switch (ADR 0015 decision 1): [value] replaces the platform defaults; [PlatformDefaults.NONE] turns them
     * off, so a common test sees "nothing installed" on every platform; `null` restores [platformDefaults].
     */
    @HiddenFromJava
    fun overrideDefaults(value: PlatformDefaults?) {
        defaultsOverride.store(value)
    }

    @HiddenFromJava
    fun install(value: ModelRuntime) {
        runtime.store(value)
    }

    /** The runtime in effect: the installed one, otherwise the platform default. */
    @HiddenFromJava
    fun runtime(): ModelRuntime? = runtime.load() ?: defaults().runtime

    /** Test seam: removes the installed runtime (the platform default, if any, applies again). */
    @HiddenFromJava
    fun uninstall() {
        runtime.store(null)
    }

    @HiddenFromJava
    fun installModelFiles(reader: ModelFileReader?) {
        files.store(reader)
    }

    @HiddenFromJava
    fun modelFiles(): ModelFileReader? = files.load() ?: defaults().modelFiles

    /** Installs the platform digest (`null` = the platform default, else the common implementation). */
    @HiddenFromJava
    fun installDigest(value: ModelDigest?) {
        digest.store(value)
    }

    @HiddenFromJava
    fun digest(): ModelDigest? = digest.load() ?: defaults().digest

    /**
     * Installs the platform's memory probe for the mask stage's working area (ADR 0013 decision 6): the JVM and
     * Android bindings use the remaining heap ([MemoryRule.HEAP_REMAINING],
     * `maxMemory() − (totalMemory() − freeMemory())` of `Runtime.getRuntime()`), the iOS binding the available figure
     * with the rule of its build target ([MemoryRule.AVAILABLE_ON_DEVICE] for `iosArm64`,
     * [MemoryRule.AVAILABLE_ON_SIMULATOR] for `iosSimulatorArm64`). `null` removes the installed probe (the platform
     * default, if any, applies again).
     *
     * WITHOUT A PROBE THERE IS NO PRE-CHECK: the working area is only counted, and only the last-resort catch remains
     * on the JVM and Android (on iOS nothing: the system ends the process). Binding checklist: provide the probe as a
     * platform default or install it in the same registration as the runtime, and add a binding test that asserts
     * [memoryProbe] and its rule on that target with no further setup (JVM: `MemoryProbeBindingTest`; Android
     * `HEAP_REMAINING`; `iosArm64` `AVAILABLE_ON_DEVICE`; `iosSimulatorArm64` `AVAILABLE_ON_SIMULATOR`).
     */
    @HiddenFromJava
    fun installMemoryProbe(value: MemoryProbe?) {
        memory.store(value)
    }

    @HiddenFromJava
    fun memoryProbe(): MemoryProbe? = memory.load() ?: defaults().memoryProbe

    /** Installs the file-mode codec (`null` = the platform default). */
    @HiddenFromJava
    fun installImageCodec(value: ImageCodec?) {
        codec.store(value)
    }

    @HiddenFromJava
    fun imageCodec(): ImageCodec? = codec.load() ?: defaults().imageCodec

    /** Installs the file-mode target writer (`null` = the platform default). */
    @HiddenFromJava
    fun installTargetWriter(value: TargetWriter?) {
        writer.store(value)
    }

    @HiddenFromJava
    fun targetWriter(): TargetWriter? = writer.load() ?: defaults().targetWriter

    /** The platform's after-call seam ([PlatformDefaults.afterCall]); there is no install: only a platform sets it. */
    @HiddenFromJava
    fun afterCall(): AfterCallSeam? = defaults().afterCall
}
