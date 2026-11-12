// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import java.io.File

/**
 * The internal reference model, a TEST FIXTURE ONLY (ADR 0009, ADR 0012): the maintainer's local fixtures (a folder
 * outside every repository) supply it, the build checks its SHA-256 against the pin kept there and copies it to a
 * git-ignored build directory. It is never committed and never part of an artifact. The tests load it through the
 * public API with the documented example profile, pinned by SHA-256. A missing fixture fails the test with a clear
 * message; it is never skipped.
 */
internal object TestModels {
    /** SHA-256 of the reference fixture (the same pin as the local fixtures check of the build). */
    const val YUNET_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

    private const val YUNET_FILE = "face_detection_yunet_2023mar.onnx"

    /**
     * The fixture directory: the system property `faceblur.testModelsDir` when the build sets it, otherwise the
     * model step's output directory `build/yunet/` of the repository (tests run in the module directory).
     */
    val directory: File
        get() = File(System.getProperty("faceblur.testModelsDir") ?: "../build/yunet")

    val yunetFile: File
        get() {
            val f = File(directory, YUNET_FILE)
            check(f.isFile) {
                "reference model fixture missing: run the build's model step (it downloads the pinned file into " +
                    "the build directory); the reference tests are never skipped"
            }
            return f
        }

    /** The documented YuNet example profile (640 × 640, the given threshold, pinned). */
    fun yunetProfile(threshold: Float = 0.5f) =
        DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, threshold, expectedSha256 = YUNET_SHA256)

    /** The reference model loaded through the public API from its file. */
    fun yunet(threshold: Float = 0.5f): DetectorModel {
        FaceBlurRuntime.installModelFiles(JvmModelFileReader)
        return DetectorModel.load(ModelSource.fromFile(yunetFile.path), yunetProfile(threshold))
    }

    /** The bytes of the reference fixture (for derived test models), checked against the pin. */
    fun yunetBytes(): ByteArray = yunetFile.readBytes().also {
        check(io.github.shishark.faceblur.internal.model.Sha256.hex(it) == YUNET_SHA256) { "fixture pin mismatch" }
    }
}
