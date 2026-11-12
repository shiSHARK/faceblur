// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFURLCreateFromFileSystemRepresentation
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks

/**
 * Core Foundation helpers of the iOS file mode: plain C calls, no Objective-C object. Every `create` result is owned
 * by the caller, who releases it with `CFRelease`.
 */
internal object IosCf {
    val TRUE: CFTypeRef? get() = kCFBooleanTrue
    val FALSE: CFTypeRef? get() = kCFBooleanFalse

    /** A dictionary of [pairs]; the values that are numbers made by [number] are released here (it retains them). */
    fun dictionary(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFMutableDictionaryRef {
        val d = CFDictionaryCreateMutable(
            null,
            pairs.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: throw io.github.shishark.faceblur.FaceMaskException.Internal("a dictionary could not be created")
        for ((key, value) in pairs) {
            CFDictionarySetValue(d, key, value)
            if (value != null && value != kCFBooleanTrue && value != kCFBooleanFalse) CFRelease(value)
        }
        return d
    }

    /** A new number; meant to be passed straight to [dictionary], which releases it. */
    fun number(value: Int): CFTypeRef? = memScoped {
        val v = alloc<IntVar>()
        v.value = value
        CFNumberCreate(null, kCFNumberSInt32Type, v.ptr)
    }

    /** A new number; meant to be passed straight to [dictionary], which releases it. */
    fun number(value: Double): CFTypeRef? = memScoped {
        val v = alloc<DoubleVar>()
        v.value = value
        CFNumberCreate(null, kCFNumberDoubleType, v.ptr)
    }

    /** The integer at [key] of [dict], or `null` when there is none or it is not a number. */
    fun long(dict: CFDictionaryRef?, key: CFStringRef?): Long? {
        val raw = CFDictionaryGetValue(dict, key) ?: return null
        if (CFGetTypeID(raw) != CFNumberGetTypeID()) return null
        return memScoped {
            val v = alloc<LongVar>()

            @Suppress("UNCHECKED_CAST") // the type ID was checked above
            val number = raw as CFNumberRef
            if (CFNumberGetValue(number, kCFNumberSInt64Type, v.ptr)) v.value else null
        }
    }

    /** A new string; the caller releases it. */
    fun cfString(value: String): CFStringRef? = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    /** [value] as a Kotlin string (ASCII type identifiers; at most 255 bytes), or `null`. */
    fun string(value: CFStringRef?): String? {
        if (value == null || CFStringGetLength(value) > 255) return null
        return memScoped {
            val buf = allocArray<ByteVar>(256)
            if (CFStringGetCString(value, buf, 256, kCFStringEncodingUTF8)) buf.toKString() else null
        }
    }

    /** A file URL of [path] (its bytes as given, not a directory); the caller releases it. */
    fun fileUrl(path: String): CFURLRef? {
        val bytes = path.encodeToByteArray()
        if (bytes.isEmpty()) return null
        return bytes.usePinned { p ->
            CFURLCreateFromFileSystemRepresentation(null, p.addressOf(0).reinterpret(), bytes.size.convert(), false)
        }
    }
}
