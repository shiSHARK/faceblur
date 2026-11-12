// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import docs.snippets.kmp.createMasker
import docs.snippets.kmp.loadModel
import docs.snippets.kmp.maskPhotoFile
import docs.snippets.kmp.maskPixels
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.testmodel.Onnx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * ST-033 (AC2, AC4, AC5): the common sample of the KMP guide (`docs/guides/snippets/kmp/`, added to this source set
 * by the build) runs from common code on every target that runs commonTest: the JVM, the Android host and the iOS
 * simulator. The runtime is a test runtime installed the way the platform registration installs one; the installed
 * bindings are restored after each test.
 */
class KmpGuideSampleTest {
    private val modelBytes = Onnx.yunetModel()

    /** Runs [block] with [runtime] as the only runtime (platform defaults off), then restores the previous state. */
    private fun withRuntime(runtime: ModelRuntime?, block: () -> Unit) {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        val before = FaceBlurRuntime.runtime()
        try {
            if (runtime == null) FaceBlurRuntime.uninstall() else FaceBlurRuntime.install(runtime)
            block()
        } finally {
            before?.let { FaceBlurRuntime.install(it) } ?: FaceBlurRuntime.uninstall()
            FaceBlurRuntime.overrideDefaults(null)
        }
    }

    @Test
    fun theSampleMasksPixelsInPlaceFromCommonCode() = withRuntime(RunnerRuntime(OneFaceRunner())) {
        val model = loadModel(ModelSource.fromBytes(modelBytes), Sha256.hex(modelBytes))
        val original = gradientPixels(640, 480)
        val image = IntArrayPixels(640, 480, original.copyOf())
        val masks = createMasker(model).use { maskPixels(it, image) }
        assertEquals(1, masks.size, "the one face of the test runtime is masked")
        assertFalse(original.contentEquals(image.data), "the pixels under the mask changed")
    }

    @Test
    fun withoutARegisteredRuntimeTheSampleFailsWithRuntimeUnavailable() = withRuntime(null) {
        val model = loadModel(ModelSource.fromBytes(modelBytes), Sha256.hex(modelBytes))
        assertFailsWith<FaceMaskException.RuntimeUnavailable> { createMasker(model) }
    }

    @Test
    fun thePlaceholderHashOfTheSampleIsRefused() {
        assertFailsWith<FaceMaskException.ModelIntegrity> { loadModel(ModelSource.fromBytes(modelBytes)) }
    }

    @Test
    fun theFileModeCallOfTheSampleFailsTypedForAMissingSource() = withRuntime(RunnerRuntime(OneFaceRunner())) {
        val model = loadModel(ModelSource.fromBytes(modelBytes), Sha256.hex(modelBytes))
        createMasker(model).use { masker ->
            assertFailsWith<FaceMaskException> {
                maskPhotoFile(masker, "faceblur-sample-missing-source.jpg", "faceblur-sample-never-written.jpg")
            }
        }
    }
}
