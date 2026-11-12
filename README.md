<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur

FaceBlur finds faces in a photo with a face-detection model that your app provides, covers each face with an opaque
mask, and writes the result as a new JPEG without the source file's metadata. It runs on the device, for Android and
for iOS, as a Kotlin Multiplatform library (`io.github.shishark:faceblur`). Swift apps use the Swift package
[`faceblur-swift`](https://github.com/shiSHARK/faceblur-swift).

FaceBlur is a tool for data minimisation: it reduces what a photo shows before you store or share it. Detection can
miss faces, and masks cover only the faces that the model finds. Check the result before you share a photo.

## What it does

- **Opaque masks only.** A face is covered by an opaque mask. There is no blur, no pixelation and no transparent or
  partial style, and no setting can make a mask smaller than the default: a mask is 1.6 times the width and 1.8 times
  the height of the face box by default, and can only be made larger (up to 3.0).
- **Metadata removed.** File mode builds the output JPEG from the masked pixels. It carries none of the source file's
  metadata, unless you explicitly keep a group (GPS, date and time, camera) with an allow-list; each group keeps
  personal data in the output.
- **No network code.** FaceBlur contains no network code of its own. On Android it also switches
  ONNX Runtime's telemetry off. The [Android guide](docs/guides/android.md) shows how to keep the network permissions that ONNX
  Runtime declares out of an app that does not need them.
- **Two API modes.** *File mode* reads a photo file, masks it and writes a new JPEG. *In-memory mode* masks the pixels
  of a `Bitmap` (Android), a `CGContext` or a pixel buffer (iOS) in place.
- **Fail closed.** If something goes wrong, the call throws a `FaceMaskException` and does not hand out an image that
  looks masked. An output that fails the library's own structure and metadata check is not kept.

## What it does not do

- It does not ship a face-detection model. Your app chooses one, describes it in a `DetectorProfile`, bundles it and
  hard-codes its SHA-256; a file with another hash is refused before it is parsed. The model, its licence and its
  training data are your responsibility.
- It does not guarantee that every face is found. Detection can miss faces, for example small, turned, partly covered
  or unusual faces, and a score threshold that is too high misses more. FaceBlur publishes no detection results in this
  release; measure your own model and settings on your own photos (see [Score threshold](docs/guides/score-threshold.md)).
- It does not judge whether your model, profile or threshold suits your photos.
- It does not edit the pixels of a photo in any other way, and it does not read or write metadata in in-memory mode:
  the encoder you use decides what a file contains.
- It writes nothing to disk except the masked output file (and, for a safe overwrite, a temporary file of the masked
  output next to the destination). It keeps no cache of images or models.
- It is not an assessment of your legal obligations. Whether and how masking helps you meet data-protection rules for
  your product is for you to decide.

## Platforms and install

| Platform | Requirements | Install |
|---|---|---|
| Android | `minSdk` 30 (Android 11) | Maven Central: `implementation("io.github.shishark:faceblur:0.1.0")` |
| Kotlin Multiplatform (Android and iOS) | Kotlin Multiplatform project | `api("io.github.shishark:faceblur:0.1.0")` in `commonMain`; iOS also needs the ONNX Runtime bridge, see the [KMP guide](docs/guides/kmp-ios.md) |
| iOS (Swift) | iOS 18 or newer | Swift Package Manager: `.package(url: "https://github.com/shiSHARK/faceblur-swift", .upToNextMinor(from: "0.1.0"))` |

Input formats are JPEG, HEIC and PNG, decoded by the platform decoder; the output is always JPEG. The Android build uses
ONNX Runtime 1.31.0 and the iOS build ONNX Runtime 1.20.0.

## Quick start (Android, Kotlin)

Add the dependency, bundle your model in the app's assets and put its SHA-256 (`shasum -a 256 model.onnx`) in your
code. Then, off the main thread:

```kotlin
import io.github.shishark.faceblur.maskFile
// plus the other FaceBlur imports, see the Android guide

val bytes = context.assets.open("model.onnx").use { it.readBytes() }
val profile = DetectorProfile(
    format = DetectionFormat.YuNetLayout(), // the output layout of YOUR model
    inputWidth = 640,
    inputHeight = 640,
    scoreThreshold = 0.5f,                  // example value: choose it with your own photos
    expectedSha256 = MODEL_SHA256,          // hard-coded in your app
)
val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile)
FaceMasker(model).use { masker ->
    val result = masker.maskFile(context, sourceUri, AndroidOutputTargets.newFile(targetUri))
    // result.masks.size faces were covered; detection can miss faces, so let the person check the result.
}
```

Every call blocks: run it on a background thread and offer cancellation. On any error treat the photo as not masked and
never publish the original instead. The full walk-through, with Java and in-memory mode, is in the guides below.

## Guides

- [FaceBlur on Android](docs/guides/android.md) (Kotlin and Java): setup, file mode, in-memory `Bitmap` mode, targets and
  overwrite rules, apps without network permission, errors, limits.
- [FaceBlur in a Swift app](docs/guides/swift.md): the `faceblur-swift` package on iOS.
- [FaceBlur in a Kotlin Multiplatform app on iOS](docs/guides/kmp-ios.md): shared code, ONNX Runtime bridge, platform setup.
- [Providing your model](docs/guides/providing-your-model.md): profile, SHA-256, bundling, model updates.
- [Score threshold](docs/guides/score-threshold.md): choosing it, checking it, the per-photo limits.
- [Threading, cancellation and memory](docs/guides/threading-and-memory.md).
- [Architecture](docs/architecture/ARCHITECTURE.md) and the [decision records](docs/adr/).

## Demo app

`samples/android` is a small Android app that shows file mode and the in-memory pipeline end to end. See its
[README](samples/android/README.md). The Swift package has a SwiftUI demo in its repository.

## Privacy rules of this repository

- Opaque masks only; no style, setting or option weakens them.
- No network code in the library; builds check the dependencies and the merged manifests.
- Report a suspected privacy problem (a face left visible by a documented setup, metadata in an output) privately, as
  described in [SECURITY.md](SECURITY.md), never as a public issue and never with the photo. Other bugs and questions
  are issues.

## Contributing

Version 0.1.0 accepts issues, not pull requests. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Model and training-data licensing

FaceBlur is licensed under the Apache License, Version 2.0 (see `LICENSE` and `NOTICE`). The licences of the third-party
components that FaceBlur depends on are listed in `licenses/INDEX.txt`, and the licence texts are in `licenses/`; they
travel inside the published artifacts. ONNX Runtime is a dependency under the MIT licence.

FaceBlur does not include a face-detection model and does not download one. The model that your app uses, the licence
of that model and the licence and origin of the data it was trained on are separate from the licence of FaceBlur. They
are your responsibility, and FaceBlur does not check or assess them.
