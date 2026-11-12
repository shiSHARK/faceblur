// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur

import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.model.OnnxDescription
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Where the app's model comes from: bytes or a regular file. Nothing is read when the source is created;
 * [DetectorModel.load] reads it once.
 */
public class ModelSource private constructor(
    /** The app's array (read by `load` only; synthetic on the JVM and Android, so Java code cannot reach it). */
    @get:HiddenFromJava internal val bytes: ByteArray?,
    /** The file path (synthetic like [bytes]). */
    @get:HiddenFromJava internal val path: String?,
) {
    /** Debug text naming the kind of source only (never the path). */
    override fun toString(): String = if (bytes != null) "ModelSource(bytes)" else "ModelSource(file)"

    /** Factories. */
    public companion object {
        /**
         * The model as bytes. The array is copied once, when [DetectorModel.load] runs; changing it after `load`
         * has returned changes nothing.
         */
        @JvmStatic
        public fun fromBytes(bytes: ByteArray): ModelSource = ModelSource(bytes, null)

        /**
         * The model as a regular file (a symbolic link as the last path component, a directory, a FIFO or a device is
         * refused). The file is read once, when [DetectorModel.load] runs; the runtime never receives this path.
         */
        @JvmStatic
        public fun fromFile(path: String): ModelSource = ModelSource(null, path)
    }
}

/**
 * The app's face-detection model, read, size-checked, hashed and checked against its [profile] once, before any
 * runtime sees it. Create it once (off the main thread: [load] blocks while it reads, hashes and parses) and share it:
 * it is immutable and thread-safe, and every worker creates its own [FaceMasker] from it. A [FaceMasker] keeps only
 * the profile and the reports, so the app can drop this object once all its instances exist.
 *
 * The app chooses the model and is responsible for it, its licence and its training data. FaceBlur ships no model.
 * Load only models you trust, ideally bundled with the app; every profile carries the SHA-256 of the approved model
 * ([DetectorProfile.expectedSha256]), and a model with another hash is never parsed.
 */
public class DetectorModel private constructor(
    private val bytes: ByteArray,
    internal val description: OnnxDescription,
    /** The profile the model was checked against. */
    public val profile: DetectorProfile,
    /** Report of the loaded bytes: SHA-256, size, IR version, opsets, inputs, outputs and warnings. */
    public val info: ModelInfo,
) {
    /** A runtime's own form of the verified bytes (for example a direct buffer), created once per model. */
    private val platformForm = AtomicReference<Any?>(null)

    /**
     * The verified bytes, for runtime bindings only. Callers must not change the array; it is the only copy the
     * runtime ever receives. Synthetic on the JVM and Android, so Java code cannot call it.
     */
    @HiddenFromJava
    internal fun verifiedBytes(): ByteArray = bytes

    /**
     * The runtime binding's own form of the bytes, created by [create] at most once per model and then shared.
     * Synthetic on the JVM and Android, so Java code cannot call it.
     */
    @HiddenFromJava
    @Suppress("UNCHECKED_CAST") // only this function stores into the slot, always a T of the one binding
    internal fun <T : Any> platformForm(create: (ByteArray) -> T): T {
        platformForm.load()?.let { return it as T }
        val made = create(bytes)
        return if (platformForm.compareAndSet(null, made)) made else platformForm.load() as T
    }

    /** Debug text with the report (no path, no model content). */
    override fun toString(): String = "DetectorModel(profile=$profile, info=$info)"

    /** Loading. */
    public companion object {
        /** Default limit on the model size: 50 MiB (52,428,800 bytes). */
        public const val DEFAULT_MAX_MODEL_BYTES: Long = 52_428_800L

        /**
         * Reads [source] (at most [maxModelBytes] + 1 bytes) and checks, in this order: the size; the SHA-256 against
         * [DetectorProfile.expectedSha256] (a model with another hash is never parsed); the pre-parse (an ONNX model
         * without external data, with at most 65,536 main-graph inputs, 1,024 graph outputs, 1,024 operator-set
         * domains and 64 dimensions per declared shape, else `NOT_ONNX`); and that its inputs and outputs match
         * [profile]. No runtime sees the bytes before all of this passed. Memory while instances are created is about
         * 3-4 × the model size (estimate).
         *
         * This reads, hashes and checks the whole file in one blocking call whose time grows with the file size; it
         * cannot be cancelled. Call it once per model, off the main thread (a call on the main thread is not
         * rejected), and share the result between instances.
         *
         * [maxModelBytes] may be 1 to 2,147,483,639. A Java caller passing `null` gets Kotlin's
         * `NullPointerException` before any work.
         *
         * @throws FaceMaskException.InvalidConfiguration (`maxModelBytes`) when the limit is outside its range.
         * @throws FaceMaskException.IoFailed when the file cannot be read (the message never contains the path).
         * @throws FaceMaskException.ModelIntegrity when the SHA-256 differs from [DetectorProfile.expectedSha256].
         * @throws FaceMaskException.ModelRejected with the finding codes when the file or the model is refused.
         * @throws FaceMaskException.Internal when the check itself fails unexpectedly (a FaceBlur bug).
         */
        @JvmStatic
        @JvmOverloads
        @Throws(FaceMaskException::class)
        public fun load(
            source: ModelSource,
            profile: DetectorProfile,
            maxModelBytes: Long = DEFAULT_MAX_MODEL_BYTES,
        ): DetectorModel = ModelLoader.load(source, profile, maxModelBytes)

        internal fun create(bytes: ByteArray, description: OnnxDescription, profile: DetectorProfile, info: ModelInfo) =
            DetectorModel(bytes, description, profile, info)
    }
}

/**
 * Report of a loaded model. [sha256] and [sizeBytes] describe exactly the bytes the runtime receives. FaceBlur never
 * logs this report; apps may show it.
 */
public class ModelInfo internal constructor(
    /**
     * SHA-256 of exactly the bytes the runtime receives, 64 lower-case hexadecimal digits, computed when the model
     * was loaded; it always equals the profile's [DetectorProfile.expectedSha256].
     */
    public val sha256: String,
    /** Size of the loaded bytes. */
    public val sizeBytes: Long,
    /** The ONNX IR version declared by the model. */
    public val irVersion: Long,
    opsetImports: Map<String, Long>,
    inputs: List<TensorInfo>,
    outputs: List<TensorInfo>,
    warnings: List<String>,
) {
    /** Operator set versions by domain (the default domain is the empty string); read-only. */
    public val opsetImports: Map<String, Long> = opsetImports.readOnly()

    /** The model inputs the app's images are fed to (graph inputs that are initializers are not listed); read-only. */
    public val inputs: List<TensorInfo> = inputs.readOnly()

    /** Every graph output, also those the format ignores; read-only. */
    public val outputs: List<TensorInfo> = outputs.readOnly()

    /**
     * Non-blocking warning codes; they never stop loading, creation or a call. The 0.1.0 code is `SMALL_INPUT`
     * (computed for a working long side of 3,500 px): medium-size faces may be missed between the tile passes and the
     * whole-photo pass; measure with faceblur-eval. New codes may be added in any release; read-only.
     */
    public val warnings: List<String> = warnings.readOnly()

    /** True when [other] is a [ModelInfo] with the same values. */
    override fun equals(other: Any?): Boolean = other is ModelInfo &&
        sha256 == other.sha256 &&
        sizeBytes == other.sizeBytes &&
        irVersion == other.irVersion &&
        opsetImports == other.opsetImports &&
        inputs == other.inputs &&
        outputs == other.outputs &&
        warnings == other.warnings

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(sha256, sizeBytes, irVersion, opsetImports, inputs, outputs, warnings).hashCode()

    /** Debug text with every value. */
    override fun toString(): String =
        "ModelInfo(sha256=$sha256, sizeBytes=$sizeBytes, irVersion=$irVersion, opsetImports=$opsetImports, " +
            "inputs=$inputs, outputs=$outputs, warnings=$warnings)"
}

/** One model input or output as declared in the model file. */
public class TensorInfo internal constructor(
    /** The tensor name. */
    public val name: String,
    /** The ONNX element type name, for example `FLOAT`; `NOT_A_TENSOR` for other value types. */
    public val elementType: String,
    shape: List<Long?>,
) {
    /** The dimensions; `null` marks a symbolic dimension. Empty when the model declares no shape. Read-only. */
    public val shape: List<Long?> = shape.readOnly()

    /** True when [other] is a [TensorInfo] with the same values. */
    override fun equals(other: Any?): Boolean =
        other is TensorInfo && name == other.name && elementType == other.elementType && shape == other.shape

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(name, elementType, shape).hashCode()

    /** Debug text with every value. */
    override fun toString(): String = "TensorInfo(name=$name, elementType=$elementType, shape=$shape)"
}

/** Report of one [FaceMasker]: the model report plus what the runtime itself reports. */
public class MaskerModelInfo internal constructor(
    /** The report of the loaded model. */
    public val model: ModelInfo,
    /** The ONNX Runtime version, read from the runtime (never a literal). */
    public val runtimeVersion: String,
    /** The execution provider actually used: `XNNPACK` or `CPU`. */
    public val executionProvider: String,
    /** The FaceBlur library version. */
    public val faceBlurVersion: String,
) {
    /** True when [other] is a [MaskerModelInfo] with the same values. */
    override fun equals(other: Any?): Boolean = other is MaskerModelInfo &&
        model == other.model &&
        runtimeVersion == other.runtimeVersion &&
        executionProvider == other.executionProvider &&
        faceBlurVersion == other.faceBlurVersion

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(model, runtimeVersion, executionProvider, faceBlurVersion).hashCode()

    /** Debug text with every value. */
    override fun toString(): String =
        "MaskerModelInfo(model=$model, runtimeVersion=$runtimeVersion, executionProvider=$executionProvider, " +
            "faceBlurVersion=$faceBlurVersion)"
}
