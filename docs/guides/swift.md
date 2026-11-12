<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur in a Swift app

This guide shows how a Swift app on iOS adds FaceBlur through the `faceblur-swift` Swift package, loads a model, masks
the faces in a photo file or in a `CGContext`, and handles errors. FaceBlur masks the faces that your model finds with
opaque masks, on the device, as data minimisation. Detection can miss faces, and masks cover only the faces that it
finds. File mode writes a new JPEG that carries none of the source file's metadata. FaceBlur contains no network code.

Using Kotlin Multiplatform instead? Read [FaceBlur in a Kotlin Multiplatform app on iOS](kmp-ios.md) and do not add the
`FaceBlur` product described here: two copies of the Kotlin runtime in one app are not supported.

Related guides: [Providing your model](providing-your-model.md), [Score threshold](score-threshold.md),
[Threading, cancellation and memory](threading-and-memory.md).

## What FaceBlur does and does not do in Swift

- It finds faces with a model that your app provides and covers each with an opaque mask. There is no blur, no
  pixelation and no transparent or partial style. The settings can only make masks larger: `MaskingConfiguration`
  defaults to 1.6 times the box width and 1.8 times the box height, and these defaults are also the smallest values
  allowed (up to 3.0).
- File mode writes a new JPEG built from the masked pixels, without the source's metadata, unless you opt in to keep
  some (`FileOptions.metadata`, `MetadataPolicy.keepAllowlist`; each group keeps personal data in the output and works
  against data minimisation). In-memory mode changes pixels only; the encoder you use decides what a file contains.
- It does not ship a model, does not judge whether your model suits your photos, and can miss faces. Check your setup
  as described in [Score threshold](score-threshold.md).
- The public API is Swift only: async methods, Swift errors (`FaceMaskError`), no Kotlin type.

## Setup

Requirements: iOS 18 or newer; a Swift 6.2 toolchain (Xcode with Swift 6.2), as the package declares.

In Xcode, choose File > Add Package Dependencies, enter `https://github.com/shiSHARK/faceblur-swift` and use the rule
"Up to Next Minor Version" from `0.1.0`. In a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/shiSHARK/faceblur-swift", .upToNextMinor(from: "0.1.0")),
],
targets: [
    .target(name: "MyApp", dependencies: [.product(name: "FaceBlur", package: "faceblur-swift")]),
]
```

The package brings ONNX Runtime 1.20.0 (Microsoft's `onnxruntime-swift-package-manager`, pinned exactly) and the
FaceBlur library as a binary framework. Do not add another ONNX Runtime version to your app. The `FaceBlurOrtShim`
product is for Kotlin Multiplatform apps only. The package bundles a privacy manifest (`PrivacyInfo.xcprivacy`) that
declares no tracking and no collected data; the declarations of your own app remain your responsibility.

Then `import FaceBlur`.

## Provide your model

FaceBlur ships no model. Your app bundles one, describes it in a `DetectorProfile` and hard-codes its SHA-256; a file
that does not match is refused with `FaceMaskError.modelIntegrity` before it is parsed. The full rules and the hash
command are in [Providing your model](providing-your-model.md).

```swift
import FaceBlur
import Foundation

// The SHA-256 of YOUR model file, hard-coded in your app: `shasum -a 256 model.onnx`. This placeholder matches no
// file, so loading fails with `modelIntegrity` until you replace it.
let modelSHA256 = "0000000000000000000000000000000000000000000000000000000000000000"

func makeMasker() async throws -> FaceMasker {
    guard let modelURL = Bundle.main.url(forResource: "model", withExtension: "onnx") else {
        throw FaceMaskError.ioFailed
    }
    let profile = try DetectorProfile(
        format: .yuNetLayout,            // the layout of YOUR model; or .genericDetections(...)
        inputWidth: 640, inputHeight: 640,
        scoreThreshold: 0.5,             // example value; see "Score threshold"
        expectedSHA256: modelSHA256)
    // Reads, hashes and checks the whole file on a private queue of FaceBlur, never on the main thread.
    let model = try await DetectorModel(source: .file(modelURL), profile: profile)
    return try await FaceMasker(model: model)
}
```

`ModelSource.data(Data)` takes model bytes you already hold in memory. The model, its licence and its training data are
your responsibility. Load the model once and share the `DetectorModel` between maskers; create one `FaceMasker` per
parallel worker and call `close()` when you are done with it.

## File mode

File mode reads a JPEG, HEIC or PNG file (decoded by the system), covers the faces and writes a new JPEG. Nothing is kept when a call fails or is
cancelled: FaceBlur writes a temporary file of the masked output next to the destination and replaces the destination
in one step.

```swift
func maskPhoto(masker: FaceMasker, source: URL, output: URL) async throws -> Int {
    let result = try await masker.maskFile(at: source, to: .newFile(output))
    return result.masks.count // detection can miss faces: let the person check the result
}
```

| Target | Meaning |
|---|---|
| `.newFile(url)` | A new file at `url`. An existing file is an error (`unsupportedTarget`). |
| `.newFile(url, replaceExisting: true)` | Replaces an existing file in one step. |
| `.overwriteSource` | Replaces the source file in one step. |

`detectFile(at:)` finds the faces and writes nothing. Its result converts to a plan with `toMaskPlan()`, which you can
edit with `MaskPlan.withMasks(_:)` and pass to `maskFile(at:to:plan:)`. A plan is bound to the file as decoded: if the
file changes in between, `maskFile` throws `sourceChanged` and writes nothing. Coordinates in results are pixels of the
working image: the source with its orientation applied, at a long side of 3,500 px by default (never scaled up).

## In-memory mode

Use this when your app holds the pixels. `mask(_:orientation:)` masks a copy of a `CGImage` and returns an upright image
without alpha. `maskInPlace(_:)` and `applyMasks(_:to:)` write into a `CGContext` or a locked `CVPixelBuffer` that you
own. `UIImage.cgImage` ignores `imageOrientation`: pass the matching `CGImagePropertyOrientation`, mapped case by case
(the mapping is in the documentation comment of `FaceMasker`). A wrong orientation can leave a sideways face
unmasked.

```swift
func maskAndEncode(masker: FaceMasker, image: CGImage) async throws -> Data {
    let (masked, _) = try await masker.mask(image, orientation: .up)
    return try await FaceMasker.encodeJPEG(masked) // pixels only: no metadata, orientation 1
}
```

An image with transparency is refused (`unsupportedImage`), never repaired. After any error from an in-place call,
do not display, encode or share the buffer as masked.

## Threading and cancellation

The methods are `async`. The work runs on a private serial queue of each masker, never on the main thread, and one
masker handles one call after another. Cancel with Swift task cancellation: a cancelled call throws
`CancellationError`, and in file mode writes nothing. For an in-place call, treat `CancellationError` as "not masked":
the buffer is either untouched or fully masked, and you cannot tell which. Never publish, upload or share it. For
parallel work create one `FaceMasker` per worker; on phones, at most 2 masking calls in parallel is the
recommendation. More is in [Threading, cancellation and memory](threading-and-memory.md).

## Errors

All errors are `FaceMaskError` (apart from Swift's `CancellationError`). Messages are fixed text and never contain
paths, file names, pixel data or coordinates. New cases and finding codes may appear in later versions: keep a
`default` branch when you switch over the type, and treat every finding code as a failure.

| Error | When | What to do |
|---|---|---|
| `invalidConfiguration(parameter:)` | A value is out of range. | Fix the value named by `parameter`. |
| `modelIntegrity` | The model's SHA-256 differs from `expectedSHA256`. | Load the file you hashed. |
| `modelRejected(findings:)`, `modelOutputInvalid(findings:)` | The model or its output failed the checks. | Read the finding codes; see [Score threshold](score-threshold.md). |
| `runtimeUnavailable` | ONNX Runtime could not be set up. | Check that the package pins the ONNX Runtime version and that no other runtime bridge was registered first. |
| `imageTooLarge`, `unsupportedImage`, `unsupportedTarget`, `ioFailed` | The source or target cannot be used. | Nothing was written. |
| `sourceChanged`, `maskPlanMismatch` | The plan does not belong to this image. | Detect again on the current image. |
| `notEnoughMemory` | The working area did not fit. The image was not masked. | Do not publish the original. Retry with less in parallel or a smaller image. |
| `maskWriteFailed` | The masks failed while being copied in. | Discard the image. |
| `outputValidationFailed(findings:)` | The encoded file failed the library's own check. | Nothing was kept. Treat it as a failure. |
| `closed`, `internal` | The masker was closed; a fault of the library. | Create a new masker; discard the image and report the fault. |

## What your app is responsible for

- The model, its licence and its training data, and the profile (see [Providing your model](providing-your-model.md)).
- Checking your setup on your own photos; detection can miss faces, and masks cover only what the model finds.
- Never publishing, uploading or sharing an original as if it were masked after an error or a cancellation.
- Your own legal and platform obligations (for example App Store privacy declarations and data-protection duties):
  FaceBlur gives facts about what it does, not an assessment of your obligations.
