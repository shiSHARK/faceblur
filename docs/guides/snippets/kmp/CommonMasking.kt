// SPDX-License-Identifier: Apache-2.0
package docs.snippets.kmp

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OutputTarget

// COMMON CODE (commonMain). Every function here blocks until it is done: call it from a worker thread
// (for example a coroutine on Dispatchers.Default), never from the main thread. @Throws makes a failure a Swift
// `throws` when Swift code calls these functions; without it a Kotlin exception would end the app.

// The SHA-256 of YOUR model file, hard-coded in your app: `shasum -a 256 model.onnx`. This placeholder matches no
// file, so loading fails with ModelIntegrity until you replace it.
const val MODEL_SHA256: String = "0000000000000000000000000000000000000000000000000000000000000000"

/** Loads the model that your platform code found in the app bundle (see "Platform setup"). */
@Throws(FaceMaskException::class)
fun loadModel(source: ModelSource, expectedSha256: String = MODEL_SHA256): DetectorModel {
    val profile = DetectorProfile(
        format = DetectionFormat.YuNetLayout(), // the layout of YOUR model; see "Providing your model"
        inputWidth = 640,
        inputHeight = 640,
        scoreThreshold = 0.5f, // example value; see "Score threshold"
        expectedSha256 = expectedSha256,
    )
    return DetectorModel.load(source, profile)
}

/** One masker per worker thread; close it when you are done with it. Masks are opaque. */
@Throws(FaceMaskException::class)
fun createMasker(model: DetectorModel): FaceMasker =
    FaceMasker(model, FaceMaskerConfig.Builder().masking(MaskingConfig.Builder().build()).build())

/** File mode: reads a JPEG or HEIC photo and writes a new JPEG built from the masked pixels, without metadata. */
@Throws(FaceMaskException::class)
fun maskPhotoFile(masker: FaceMasker, sourcePath: String, outputPath: String): List<Mask> =
    masker.maskFile(sourcePath, OutputTarget.NewFile(outputPath)).masks

/**
 * In-memory mode: covers the faces in [image] in place. Your platform code wraps its pixel buffer.
 * After any error, treat the image as NOT masked: do not show, save or share it, and never publish the original instead.
 */
@Throws(FaceMaskException::class)
fun maskPixels(masker: FaceMasker, image: MutablePixelImage): List<Mask> = masker.maskInPlace(image).masks
