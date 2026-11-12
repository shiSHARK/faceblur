// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentResolver
import android.net.Uri
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.PlatformCallValue
import io.github.shishark.faceblur.filemode.PlatformSourceValue

/** The URI schemes file mode reads and writes. */
internal object UriSchemes {
    const val CONTENT: String = ContentResolver.SCHEME_CONTENT
    const val FILE: String = ContentResolver.SCHEME_FILE
}

/**
 * A `content:` source of the `Context` forms (ADR 0015 decision 4). It holds the URI only; the `ContentResolver`
 * comes with each call ([AndroidCall]). A content URI cannot be replaced in one step, so `OverwriteSource` is refused
 * for it before anything is opened (ADR 0005 decision 4). `toString()` names the kind only.
 */
internal class ContentSource(val uri: Uri) : PlatformSourceValue {
    override val kind: String get() = UriSchemes.CONTENT

    override val replaceableInOneStep: Boolean get() = false

    override fun toString(): String = "ContentSource"
}

/**
 * The value of the targets `AndroidOutputTargets` makes: the URI and the overwrite opt-in. Equal for the same URI and
 * opt-in; holds no `Context`; `toString()` names the factory only, never the URI.
 */
internal class AndroidTargetValue(val uri: Uri, val overwrite: Boolean) : PlatformTargetValue {
    override val kind: String get() = if (overwrite) "overwriteNonAtomic" else "newFile"

    override fun equals(other: Any?): Boolean =
        other is AndroidTargetValue && uri == other.uri && overwrite == other.overwrite

    override fun hashCode(): Int = 31 * uri.hashCode() + (if (overwrite) 1 else 0)

    override fun toString(): String = "AndroidTargetValue($kind)"
}

/**
 * The platform value of one `Context` call: the caller's `ContentResolver`, for this call only (never stored in a
 * default object, a target, a source or the `FaceMasker`).
 */
internal class AndroidCall(val resolver: ContentResolver) : PlatformCallValue {
    override val kind: String get() = "android"

    override fun toString(): String = "AndroidCall"
}

/** Builds the sources and call contexts of the `Context` forms. */
internal object AndroidSources {
    /**
     * The file-mode source for [uri]: a `file:` URI is a path source (read and, with `OverwriteSource`, replaced by
     * the path rules), a `content:` URI a [ContentSource]. Any other scheme fails with `IoFailed`; the message never
     * contains the URI.
     */
    @HiddenFromJava
    fun of(uri: Uri): FileSource = when (uri.scheme) {
        UriSchemes.FILE -> FileSource.Path(
            uri.path?.takeIf { it.isNotEmpty() } ?: throw FaceMaskException.IoFailed("the file URI has no path"),
        )

        UriSchemes.CONTENT -> FileSource.Platform(ContentSource(uri))

        else -> throw FaceMaskException.IoFailed("only content: and file: URIs can be read")
    }

    /** The call context of one `Context` call. */
    @HiddenFromJava
    fun call(resolver: ContentResolver): CallContext = CallContext(AndroidCall(resolver))

    /** The `ContentResolver` of [call]; a call without one (or with a foreign value) fails closed. */
    @HiddenFromJava
    fun resolver(call: CallContext): ContentResolver = (call.platform as? AndroidCall)?.resolver
        ?: throw FaceMaskException.UnsupportedTarget(
            "a content URI needs the platform form of this call (the form that takes a Context)",
        )
}
