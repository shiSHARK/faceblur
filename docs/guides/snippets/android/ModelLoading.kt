// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android

import android.content.Context
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.ModelSource

// The SHA-256 of YOUR model file, hard-coded in your app: `shasum -a 256 model.onnx`. This placeholder matches no
// file, so loading fails with ModelIntegrity until you replace it.
private const val MODEL_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"

/** Reads the bundled model from the app's assets and loads it. Call off the main thread. */
fun loadBundledModel(context: Context): DetectorModel {
    val bytes = context.assets.open("model.onnx").use { it.readBytes() }
    val profile = DetectorProfile(
        format = DetectionFormat.YuNetLayout(), // the layout of YOUR model; see "Providing your model"
        inputWidth = 640,
        inputHeight = 640,
        scoreThreshold = 0.5f, // example value; see "Score threshold"
        expectedSha256 = MODEL_SHA256,
    )
    return DetectorModel.load(ModelSource.fromBytes(bytes), profile)
}

/** One masker per worker, created once off the main thread, closed when you are done with it. */
fun createMasker(model: DetectorModel): FaceMasker = FaceMasker(model)
