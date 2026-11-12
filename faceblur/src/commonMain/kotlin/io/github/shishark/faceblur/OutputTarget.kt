// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.jvm.JvmOverloads

/**
 * Where a file-mode call writes the masked JPEG. There is no default: overwriting is always an explicit choice.
 *
 * The common targets are [NewFile] and [OverwriteSource]. Platform factories make [PlatformTarget]s (Android:
 * `AndroidOutputTargets.newFile(uri)` and `AndroidOutputTargets.overwriteNonAtomic(uri)` for content URIs). A target
 * is only a description: creating one checks and touches nothing, and it holds no platform context. A target that
 * cannot be used for a call is refused with [FaceMaskException.UnsupportedTarget] before the source is decoded, and
 * nothing is written.
 *
 * `toString()` names the kind of target only, never a path or URI.
 */
public sealed class OutputTarget {
    /**
     * A file at [path] in the file system (Android app storage, the JVM, an iOS sandbox path). The masked file is
     * written completely next to the target and then moved into place, so the target never holds a partly written
     * file; on any failure or cancellation the target is untouched. The exception for platforms without hard links
     * is described below.
     *
     * With [replaceExisting] `false` (the default) an existing file at [path] is not replaced; the call fails with
     * [FaceMaskException.UnsupportedTarget] instead. Where the file system supports hard links, and on Android, this
     * also holds for a file created at [path] while the call runs. Where the platform refuses hard links (on Android this includes the app's own storage) a new file is created exclusively
     * at [path] and written there: until the call returns, and after a process kill, it can hold an incomplete file of
     * masked output (never anything of the source); on a reported failure it is removed. An existing file is never
     * replaced in either case.
     *
     * With `true` an existing file is replaced in one step (the masked file is written next to it and renamed over
     * it). A [path] equal to the source path needs [replaceExisting] `true`, or use [OverwriteSource].
     */
    public class NewFile
    @JvmOverloads
    constructor(
        /** The file to write. Never part of a message or of `toString()`. */
        public val path: String,
        /** `true` replaces an existing file at [path]; default `false`. */
        public val replaceExisting: Boolean = false,
    ) : OutputTarget() {
        /** True when [other] is a [NewFile] with the same path and the same [replaceExisting]. */
        override fun equals(other: Any?): Boolean =
            other is NewFile && path == other.path && replaceExisting == other.replaceExisting

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = 31 * path.hashCode() + (if (replaceExisting) 1 else 0)

        /** Debug text with [replaceExisting] and without the path. */
        override fun toString(): String = "NewFile(replaceExisting=$replaceExisting)"
    }

    /**
     * Replaces the source file with the masked JPEG. For a file-system path the replacement is one step: on any
     * failure the source is unchanged. A content-URI source on Android is refused with
     * [FaceMaskException.UnsupportedTarget]; replacing a content URI needs the explicit opt-in
     * `AndroidOutputTargets.overwriteNonAtomic(uri)`. Java code uses `OutputTarget.OverwriteSource.INSTANCE`.
     */
    public object OverwriteSource : OutputTarget() {
        /** Debug text. */
        override fun toString(): String = "OverwriteSource"
    }

    /**
     * A target made by a platform factory (Android: `AndroidOutputTargets`); apps cannot construct one. It can be
     * used only with the platform's forms of the file-mode calls (on Android the forms that take a `Context`); the
     * common path form refuses it with [FaceMaskException.UnsupportedTarget] before any work.
     */
    public class PlatformTarget private constructor(@get:HiddenFromJava internal val value: PlatformTargetValue) :
        OutputTarget() {
        /** True when [other] is a [PlatformTarget] for the same platform target. */
        override fun equals(other: Any?): Boolean = other is PlatformTarget && value == other.value

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = value.hashCode()

        /** Debug text with the factory's kind of target and without the URI. */
        override fun toString(): String = "PlatformTarget(${value.kind})"

        internal companion object {
            /**
             * The platform factories' only way to make a target. Synthetic on the JVM and Android ([HiddenFromJava]),
             * so Java code cannot call it; the constructor is private.
             */
            @HiddenFromJava
            internal fun create(value: PlatformTargetValue): PlatformTarget = PlatformTarget(value)
        }
    }
}

/**
 * The platform part of an [OutputTarget.PlatformTarget] (ADR 0015 decision 2). Android implements it with the URI and
 * the overwrite opt-in. Implementations define `equals`/`hashCode` over their values and hold no platform context.
 * The interface is public in JVM bytecode, so a platform writer accepts only its own implementation and refuses any
 * other value with `UnsupportedTarget` (fail closed).
 */
internal interface PlatformTargetValue {
    /** The factory's name of the kind of target (for example `newFile`), for `toString()`; never a path or URI. */
    val kind: String
}
