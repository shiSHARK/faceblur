// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.filemode.normalised
import java.io.File

/**
 * The Android writer of finished files (ADR 0015 decision 4): file-system paths ([OutputTarget.NewFile],
 * [OutputTarget.OverwriteSource], `file:` URIs of `AndroidOutputTargets`) through [PathWriteRules], `content:` URIs
 * through [ContentUriWriter]. Stateless; the `ContentResolver` comes with each call.
 *
 * - `NewFile(path)` never replaces anything; `NewFile(path, replaceExisting = true)` and
 *   `AndroidOutputTargets.overwriteNonAtomic(file URI)` replace a regular file with one name in one step;
 * - `OverwriteSource` replaces exactly the file that was decoded (its device and inode, recorded by the codec);
 *   so does a replacing target whose path is the source path;
 * - `AndroidOutputTargets.newFile(content URI)` writes an empty document only, `overwriteNonAtomic(content URI)`
 *   replaces its content with the verified, non-atomic write.
 */
internal class AndroidTargetWriter<D> private constructor(private val calls: FileCalls<D>) : TargetWriter {
    @HiddenFromJava
    override fun check(target: OutputTarget, source: FileSource, call: CallContext) {
        when (val r = route(target, source, call)) {
            is Route.Path -> PathWriteRules.check(r.path, r.mode, r.identity, calls)
            is Route.Content -> ContentUriWriter.check(AndroidSources.resolver(call), r.value.uri, r.value.overwrite)
        }
    }

    @HiddenFromJava
    override fun accepts(value: PlatformTargetValue): Boolean = value is AndroidTargetValue

    override fun absolutePath(path: String): String = File(path).absolutePath

    @HiddenFromJava
    override fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) {
        when (val r = route(target, source, call)) {
            is Route.Path -> PathWriteRules.write(r.path, r.mode, r.identity, bytes, calls)

            is Route.Content ->
                ContentUriWriter.write(AndroidSources.resolver(call), r.value.uri, r.value.overwrite, bytes)
        }
    }

    private sealed class Route {
        class Path(val path: String, val mode: PathMode, val identity: SourceIdentity?) : Route()

        class Content(val value: AndroidTargetValue) : Route()
    }

    private fun route(target: OutputTarget, source: FileSource, call: CallContext): Route = when (target) {
        is OutputTarget.NewFile -> replacing(target.path, target.replaceExisting, source)

        OutputTarget.OverwriteSource -> {
            val path = sourcePath(source)
                ?: throw FaceMaskException.UnsupportedTarget("this source has no file to replace")
            Route.Path(absolutePath(path), PathMode.REPLACE, SourceIdentities.of(source) ?: unknownSource())
        }

        is OutputTarget.PlatformTarget -> {
            if (call.platform == null) {
                throw FaceMaskException.UnsupportedTarget("a platform target needs the form that takes a Context")
            }
            val value = target.value as? AndroidTargetValue
                ?: throw FaceMaskException.UnsupportedTarget("the target was not made by AndroidOutputTargets")
            when (value.uri.scheme) {
                UriSchemes.FILE -> replacing(
                    value.uri.path?.takeIf { it.isNotEmpty() }
                        ?: throw FaceMaskException.UnsupportedTarget("the file URI has no path"),
                    value.overwrite,
                    source,
                )

                UriSchemes.CONTENT -> Route.Content(value)

                else -> throw FaceMaskException.UnsupportedTarget("only content: and file: URIs can be written")
            }
        }
    }

    /**
     * A path target, made absolute (the check and the write then use the same directory whatever the process's
     * working directory); a replacing one at the source's own path must still be the decoded file.
     */
    private fun replacing(path: String, replace: Boolean, source: FileSource): Route.Path {
        val target = absolutePath(path)
        if (!replace) return Route.Path(target, PathMode.NO_REPLACE, null)
        val src = sourcePath(source)
        val same = src != null && normalised(absolutePath(src)) == normalised(target)
        return Route.Path(target, PathMode.REPLACE, if (same) SourceIdentities.of(source) ?: unknownSource() else null)
    }

    private fun sourcePath(source: FileSource): String? = (source as? FileSource.Path)?.path

    private fun unknownSource(): Nothing = throw FaceMaskException.Internal("the decoded source file is not known")

    companion object {
        /** The production writer on `android.system.Os`. */
        @get:HiddenFromJava
        val DEFAULT: TargetWriter = AndroidTargetWriter(OsDescriptors)

        /** A writer on other [calls] (host tests). */
        @HiddenFromJava
        fun <D> using(calls: FileCalls<D>): TargetWriter = AndroidTargetWriter(calls)
    }
}
