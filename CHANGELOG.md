<!-- SPDX-License-Identifier: Apache-2.0 -->
# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project follows
[Semantic Versioning](https://semver.org/); the API is not stable before 1.0, and breaking changes are listed here.

## [0.1.0] - Unreleased

First release, for Android and iOS. One version number covers the Maven artifact `io.github.shishark:faceblur`, the
`FaceBlurCore` XCFramework and the `faceblur-swift` Swift package.

### Added

- `FaceMasker`: finds faces with the app's own model and covers them with opaque masks. `detect` returns the face
  boxes, `applyMasks` writes a mask plan (made or edited by the app) into an image, `maskInPlace` does both in place,
  and `checkProfile` is an optional self-check of a model and profile on a test image with known face boxes
  (`ExpectedFace`, `ProfileCheckResult`). One call at a time per instance; one instance per worker. Every call blocks:
  run it off the main thread. Calls take an optional `CancellationToken`.
- File mode: `maskFile` and `detectFile` read a JPEG, HEIC or PNG file, mask it and write a new JPEG built from the
  masked pixels, without the source's metadata unless the app opts in with an allow-list
  (`FileOptions`, `MetadataPolicy`, `OutputFormat.Jpeg`). Targets: `OutputTarget.NewFile` (with `replaceExisting`,
  default `false`) and `OutputTarget.OverwriteSource`, which replace a file in one step, and on Android the platform
  targets of `AndroidOutputTargets` for content Uris. The result, `FileResult`, reports the sizes, the faces, the
  masks and `gainMapRemoved`. On Android the stream form `maskJpeg` reads at most 64 MiB (`SourceTooLarge`); use the
  file or `Uri` form for larger inputs. Every output passes the library's own fail-closed structure and metadata check
  before it is kept.
- In-memory mode: `Bitmap` calls on Android, and on iOS the pixel-buffer forms through `FaceBlurIos.wrapRgba` and the
  Swift calls of `faceblur-swift`. Pixel access goes through the `PixelImage` and `MutablePixelImage` interfaces
  (non-premultiplied ARGB_8888 ints), so a large photo never has to be copied into one array. On Android 14 and newer, a
  `Bitmap` or source file with an HDR gain map is masked with the gain map removed (`MaskResult.gainMapRemoved`,
  `FileResult.gainMapRemoved`), so that a later encode cannot write the unmasked faces through it.
- `PlanMasker` (and `UprightBox`) for apps that bring their own detector: it loads no model and no runtime; the app
  supplies the face boxes.
- `DetectorModel.load` with `ModelSource.fromBytes` and `ModelSource.fromFile`. FaceBlur ships no model: the app
  provides it and is responsible for it, its licence and its training data. Loading checks, in this order, the size
  (default cap 50 MiB, `maxModelBytes` 1 to 2,147,483,639), the SHA-256 against the profile's `expectedSha256`
  (a file with another hash is never parsed), the structure of the ONNX file, and the model's inputs and outputs
  against the profile; only then does a runtime see the bytes. `ModelInfo` reports the loaded bytes and `warnings`
  (`SMALL_INPUT`).
- `DetectorProfile` (and `DetectorProfile.Builder` for Java) describes the app's model: `DetectionFormat.YuNetLayout`
  or `DetectionFormat.GenericDetections` (decoded rows or columns; box format, coordinate space, colour order, tensor
  layout, `PixelScaling`, `nmsInGraph`), input size 128 to 1,280 px, the score threshold, the expected SHA-256 and the
  smallest mask expansion. `expectedSha256` is required and non-null: a model with another hash is refused with
  `ModelIntegrity` before it is parsed. Compute the value with `shasum -a 256 model.onnx` and keep it in your app's
  code. FaceBlur checks the values technically and does not judge whether a model, profile or threshold suits the
  app's photos.
- Masks: `Mask` (`fromFace`, `manual`, `exact`), `MaskPlan`, `MaskingConfig` and `FaceMaskerConfig` (builders for
  Java). Masks are always opaque and can only be made larger than the defaults: a face's mask is 1.6 times the width
  and 1.8 times the height of its box by default, and these defaults are also the smallest values allowed
  (`MaskingConfig.expandX`/`expandY` and `DetectorProfile.minMaskExpandX`/`minMaskExpandY` accept 1.6 / 1.8 to 3.0;
  smaller values throw `FaceMaskException.InvalidConfiguration`). The styles are `GRADIENT_OVAL`, `SMOOTH_FILL` and
  `SOLID`, the shapes `ELLIPSE` and `RECTANGLE`. A lower score threshold never removes or shrinks a mask of a call that
  succeeds. No blur, pixelation or other reversible style exists.
- Memory: the in-place call reserves its whole working area before it writes the first pixel and checks it against the
  memory that is available now, together with the other running calls. If it does not fit it throws
  `NotEnoughMemory` and the image is untouched. The masks' summed working area is limited to 32 times the image area
  (at least 32 times 1,048,576 pixels); exceeding it fails safely with an actionable error. A call that fails with a
  `FaceMaskException` leaves the image untouched except after `MaskWriteFailed`; a cancellation never leaves a
  half-masked image. Recommendation: at most 2 masking calls in parallel on phones, masking off the main thread, and
  a way to cancel. See [Threading, cancellation and memory](docs/guides/threading-and-memory.md).
- Fail-closed errors, all subtypes of `FaceMaskException` (including `ModelIntegrity`, `ModelRejected`,
  `ModelOutputInvalid`, `NotEnoughMemory`, `Cancelled`, `ConcurrentUse`, `Closed`, `InvalidConfiguration`). Finding
  codes are stable strings: a code is never renamed, removed or given another meaning without an entry here, and new
  codes may be added. `Detection`, `MaskResult` and `FileResult` report the outcome, including `outputWidth` and
  `outputHeight`.
- Per-photo limits that fail safely: at most 65,536 masks and at most 240,000,000 box pair checks in the in-pass
  duplicate removal. `TOO_MANY_MASKS` and `TOO_MANY_NMS_PAIR_CHECKS` report the count and the profile's score
  threshold and say the threshold is very likely too low for this model. See
  [Score threshold](docs/guides/score-threshold.md).
- Android: `minSdk` 30; ONNX Runtime for Android 1.31.0 with the model read from bytes or a file; the AAR ships consumer
  R8/ProGuard rules that keep ONNX Runtime's Java API, so a minified release build opens a model session. The
  library removes ONNX Runtime's telemetry start-up provider from the app's manifest; the guide shows two lines that
  remove ONNX Runtime's two network permissions from an app that needs none.
- iOS: `iosArm64` and `iosSimulatorArm64`; ONNX Runtime for iOS 1.20.0 through a small bridge that the app registers
  once (`FaceBlurIos.registerOnnxRuntime`); the model session is created from the verified bytes in memory and no model
  file is written. The `FaceBlurCore` XCFramework is built from this repository and used by the `faceblur-swift`
  package.
- Android demo app (`samples/android`, Kotlin, Views): picks a model file (with a hash confirmation) and a photo with
  the system pickers, shows the detected boxes and masks, lets the user keep a mask unmasked or add one with buttons
  (no gestures needed), and saves a new JPEG with file mode. A second screen runs the in-memory pipeline on a Bitmap.
  Its manifest removes ONNX Runtime's two permissions, and the build task `checkReleaseMergedManifest` fails if the
  merged release manifest declares a network, location or storage permission.
- faceblur-eval, a command-line tool in the test sources of this repository (not part of any published artifact), run
  with `./gradlew :faceblur:faceblurEval`: measures a model and profile on the developer's own annotated photos across
  a list of score thresholds. It has no network code and prints no file names unless asked.
- Build checks: the resolved runtime dependencies and the dependencies in the published POM and Gradle module metadata
  are compared with a reviewed list (a networking, telemetry, analytics or crash-reporting library fails the check);
  every file-access line of the library sources is compared with a reviewed classification; licence texts for every
  runtime dependency are checked and packaged.
- Licence notices: `LICENSE`, `NOTICE` and the licence texts of the third-party components (`licenses/`) are packaged
  inside the Android AAR, the root jar, the sources and javadoc jars, and at the root of the XCFramework zip.
- Guides: [FaceBlur on Android](docs/guides/android.md), [FaceBlur in a Swift app](docs/guides/swift.md),
  [FaceBlur in a Kotlin Multiplatform app on iOS](docs/guides/kmp-ios.md),
  [Providing your model](docs/guides/providing-your-model.md), [Score threshold](docs/guides/score-threshold.md),
  [Threading, cancellation and memory](docs/guides/threading-and-memory.md).

### Known issues

- Known issue (iOS): FaceBlur runs with ONNX Runtime 1.20.0 on iOS, an older runtime that lacks some later memory-safety fixes. FaceBlur requires the SHA-256 of your model and refuses models that reference external data before the runtime reads the file, but it cannot make a malicious model safe: load only models you trust, ship them inside your app and hard-code their hash.
