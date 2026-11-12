// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorProfile
import java.io.File

/**
 * Fixtures of the device tests. The harness places them in the test app's private storage before the run (never in
 * the APK, never in the AAR): `files/faceblur-fixtures/` holds the reference model (a test fixture, ADR 0009/0012) and
 * one image from the maintainer's local fixtures (outside the repository). A missing fixture fails the test; it is
 * never skipped.
 */
internal object DeviceFixtures {
    const val TAG = "FaceBlurDeviceTest"

    /** SHA-256 of the reference model fixture (the same pin as the local fixtures check of the build). */
    const val YUNET_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    const val YUNET_FILE = "face_detection_yunet_2023mar.onnx"

    /** An image of the maintainer's local fixtures (images/, never in the repository); one face in the reference data. */
    const val IMAGE_FILE = "face-0003542-s0625-x0000-y0000.png"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val dir: File get() = File(context.filesDir, "faceblur-fixtures")

    fun file(name: String): File {
        val f = File(dir, name)
        check(f.isFile) { "device fixture missing: $name (run the harness that places it in the app's files)" }
        return f
    }

    /** The documented YuNet example profile (640 × 640, threshold 0.5), pinned to the fixture. */
    fun yunetProfile(): DetectorProfile =
        DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, expectedSha256 = YUNET_SHA256)

    /** A scratch directory in the app's private storage, emptied first. */
    fun scratch(name: String): File = File(context.filesDir, "faceblur-scratch/$name").apply {
        deleteRecursively()
        mkdirs()
    }

    private var headerLogged = false

    /** Log header of ST-003 AC3 and the process working directory (the base directory ONNX Runtime uses for models loaded from bytes). */
    @Synchronized
    fun logHeader() {
        if (headerLogged) return
        headerLogged = true
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.i(
            TAG,
            "device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        Log.i(
            TAG,
            "build type: ${if (debuggable) "debug" else "release"}; ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
        )
        val cwd = runCatching { Os.readlink("/proc/self/cwd") }.getOrElse { "unreadable (${it.javaClass.simpleName})" }
        Log.i(TAG, "process working directory: $cwd; user.dir: ${System.getProperty("user.dir")}")
        Log.i(TAG, "API 30 is covered by minSdk 30 and compilation only (no Android 11 device)")
    }
}
