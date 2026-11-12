<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur in a Kotlin Multiplatform app on iOS

This guide shows how a Kotlin Multiplatform (KMP) app calls FaceBlur from its shared code on iOS: add the Maven
artifact, add ONNX Runtime through a small Swift package product, register the bridge, and supply your model. FaceBlur
masks the faces that your model finds with opaque masks, on the device, as data minimisation. Detection can miss faces,
and masks cover only the faces that it finds. File mode writes a new JPEG without the source file's metadata. FaceBlur
contains no network code.

This guide is for apps that share Kotlin code between Android and iOS. A pure Swift app uses the `faceblur-swift`
package instead: see [FaceBlur in a Swift app](swift.md). The common code example is kept as a source file, [`snippets/kmp/CommonMasking.kt`](snippets/kmp/CommonMasking.kt), and is compiled by the library build.

Related guides: [Android](android.md), [Providing your model](providing-your-model.md),
[Score threshold](score-threshold.md), [Threading, cancellation and memory](threading-and-memory.md).

## Before you start

- **Use the Maven artifact only.** Your shared code depends on `io.github.shishark:faceblur`. Never add the FaceBlur
  Swift library (product `FaceBlur` of the `faceblur-swift` package) as well: it carries its own copy of the library and
  of the Kotlin runtime, and two Kotlin runtimes in one app are not supported. From `faceblur-swift` you add only the
  product `FaceBlurOrtShim`, which contains no Kotlin code.
- **ONNX Runtime 1.20.0, exactly.** On iOS FaceBlur runs with ONNX Runtime 1.20.0 (Microsoft's
  `onnxruntime-swift-package-manager`). `FaceBlurOrtShim` pins this version; do not add another ONNX Runtime version to
  your app. The library refuses a bridge that reports another version.
- **Your app supplies the model.** FaceBlur does not ship a model and takes one from nowhere else than the file or the
  bytes your app passes in. Ship the model in your app bundle and load only models that you trust. The profile requires
  the model's SHA-256, hard-coded in your app code; a model with another hash is refused with `ModelIntegrity`. See
  [Providing your model](providing-your-model.md).
- **Without the bridge, nothing crashes.** If no bridge is registered, creating a `FaceMasker` throws
  `FaceMaskException.RuntimeUnavailable`. Mark the common functions that Swift calls with
  `@Throws(FaceMaskException::class)`, so that Swift receives the failure as a thrown error.

## Setup

1. **Add the dependency to your shared module** and export it to Swift, because your Swift code implements the bridge
   interface of the library:

   ```kotlin
   // shared/build.gradle.kts
   kotlin {
       listOf(iosArm64(), iosSimulatorArm64()).forEach {
           it.binaries.framework {
               baseName = "Shared"   // the name of YOUR framework; used in step 3
               isStatic = true
               export("io.github.shishark:faceblur:0.1.0")
           }
       }
       sourceSets {
           commonMain.dependencies { api("io.github.shishark:faceblur:0.1.0") }
       }
   }
   ```

   The one dependency line resolves the right library variant for each target (Android, `iosArm64`,
   `iosSimulatorArm64`) from Maven Central; `mavenCentral()` must be in the repositories of your project.

2. **Add ONNX Runtime to the iOS app.** In Xcode, add the package `https://github.com/shiSHARK/faceblur-swift` with the
   dependency rule "Exact Version" `0.1.0` (the version of your FaceBlur dependency) and link only the product
   `FaceBlurOrtShim` to your app target. It brings
   ONNX Runtime 1.20.0 and the C functions that the bridge calls. Do not add the product `FaceBlur` (see above): a KMP
   app must not link it. The `faceblur-swift` package declares the FaceBlurCore binary as a package dependency, so
   SwiftPM fetches it even when your app links only `FaceBlurOrtShim`; it is not linked into your app.

3. **Add the bridge to your app target.** Copy `Sources/FaceBlurOnnxRuntime/OnnxRuntimeGlue.swift` from
   `faceblur-swift` (tag `0.1.0`, the version that matches your FaceBlur dependency) into your app target and change its first import from `import FaceBlurCore` to
   the module of your framework (`import Shared`). Change nothing else: the file creates the ONNX Runtime session from
   the verified model bytes in memory, writes no model file, and passes only numbers per detection pass.

4. **Register the bridge once at app start**, before any `FaceMasker` is created:

   ```swift
   // PLATFORM SETUP (iOS, Swift)
   import Shared

   FaceBlurIos.shared.registerOnnxRuntime(factory: OnnxRuntimeGlueFactory())
   ```

   On Android there is nothing to register: the Maven artifact brings its own runtime.

5. **Bundle your model and hand it to common code.** Add the model file to your app target (iOS) or to your assets
   (Android), write its SHA-256 into `MODEL_SHA256` in your common code, and create a `ModelSource` on each platform:

   ```swift
   // PLATFORM SETUP (iOS, Swift): the model file in the app bundle
   let path = Bundle.main.path(forResource: "model", ofType: "onnx")!
   let source = ModelSource.companion.fromFile(path: path)
   ```

   ```kotlin
   // PLATFORM SETUP (Android, androidMain): Android assets are not files, so pass the bytes
   val source = ModelSource.fromBytes(context.assets.open("model.onnx").use { it.readBytes() })
   ```

   If your app already holds the model bytes in memory on iOS, `FaceBlurIos.shared.modelSourceFromMemory` copies them
   into a `ModelSource`.

6. **Call FaceBlur from common code, off the main thread.** The common code below loads the model, creates the masker
   and masks one photo in each API mode. Every call blocks until it is done, so never call it on the main thread.

## The common code

The file [`snippets/kmp/CommonMasking.kt`](snippets/kmp/CommonMasking.kt), in full:

```kotlin
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
```

What is platform setup and what is common code:

| Part | Where |
|---|---|
| Bridge registration (`registerOnnxRuntime`) | iOS app, Swift, once at start (step 4) |
| Finding the model and creating the `ModelSource` | platform code (step 5) |
| Wrapping a pixel buffer for in-memory mode | iOS: `FaceBlurIos.shared.wrapRgba(...)` over your RGBA buffer; Android: the `Bitmap` calls of the [Android guide](android.md) in `androidMain`, or your own `MutablePixelImage` |
| Loading the model, creating the masker, masking in both modes | common code (`CommonMasking.kt`) |

Calling the common code from Swift, off the main thread:

```swift
// PLATFORM SETUP (iOS, Swift)
DispatchQueue.global(qos: .userInitiated).async {
    do {
        let model = try CommonMaskingKt.loadModel(source: source, expectedSha256: CommonMaskingKt.MODEL_SHA256)
        let masker = try CommonMaskingKt.createMasker(model: model)
        defer { masker.close() }
        let masks = try CommonMaskingKt.maskPhotoFile(masker: masker, sourcePath: inputPath, outputPath: outputPath)
        // masks.count faces were masked; detection can miss faces, so let the user check the result.
    } catch {
        // A FaceMaskException, for example RuntimeUnavailable when step 4 is missing.
        // After any error the photo is NOT masked: do not show, save or share it, and never publish the original instead.
    }
}
```

Swift cannot use Kotlin default arguments, so it passes `expectedSha256` explicitly.

## If something does not work

- `RuntimeUnavailable` on iOS: the bridge was not registered before the first `FaceMasker` (step 4), or the bridge
  reports an ONNX Runtime version other than 1.20.0 (step 2).
- `ModelIntegrity`: the SHA-256 in your code does not match the model file in your bundle.
- After any error, treat the photo as NOT masked: do not show, save or share it, and never publish the original instead.
- Your app target lists the product `FaceBlur` of `faceblur-swift`: remove it and keep only `FaceBlurOrtShim`
  (step 2), so that only your own framework contains FaceBlur and the Kotlin runtime.
