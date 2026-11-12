<!-- SPDX-License-Identifier: Apache-2.0 -->
# Providing your model

Related guides: [FaceBlur on Android](android.md), [Score threshold](score-threshold.md),
[Threading, cancellation and memory](threading-and-memory.md).

FaceBlur ships no face-detection model. Your app chooses a model, describes it in a `DetectorProfile`, and is
responsible for the model, its licence and its training data. FaceBlur checks the model technically and covers the
faces that the model finds with opaque masks, as data minimisation. It does not judge whether the model suits your
photos: that is what [faceblur-eval](score-threshold.md) is for.

This guide covers how to hand a model to FaceBlur and how to make sure FaceBlur receives exactly the
model you approved. The code is shown for Kotlin (common code, which applies to Android and to iOS through Kotlin
Multiplatform) and for Java. Swift apps that use the `faceblur-swift` package pass the same values to the Swift
types `DetectorProfile` and `DetectorModel` (see [FaceBlur in a Swift app](swift.md)). Threading and memory are in
[Threading, cancellation and memory](threading-and-memory.md); choosing the score threshold is in
[Score threshold](score-threshold.md).

## The expected SHA-256 is required

Every `DetectorProfile` carries `expectedSha256`: the SHA-256 of the exact model file you approved, as 64
hexadecimal digits (upper or lower case; it is stored in lower case). There is no profile without it.

`DetectorModel.load` checks the file in this order:

1. **Size cap.** Reading stops just past `maxModelBytes` (default `DetectorModel.DEFAULT_MAX_MODEL_BYTES`, 50 MiB,
   settable from 1 to 2,147,483,639). A larger file is refused with `ModelRejected` (`TOO_LARGE`).
2. **SHA-256.** The bytes are hashed and compared with `expectedSha256`. A different hash is refused with
   `ModelIntegrity`. A file that does not match is never parsed.
3. **Pre-check.** The structure of the ONNX file is checked without a runtime (for example `NOT_ONNX`,
   `EXTERNAL_DATA`, wrong input or output count or type). Failures are `ModelRejected` with finding codes.
4. **Runtime.** Only after the three steps above does a runtime see the bytes: `FaceMasker(model)` opens a session,
   compares the runtime's view of the inputs and outputs with your profile and runs one pass on a uniform grey input.

The hash check proves "this is exactly the file I approved". It does not prove that the model is good, that its
profile is right, or that it is licensed for your use. A well-formed model with a wrongly declared profile can find
no faces and still report success; check your setup as described in [Score threshold](score-threshold.md).

Loading reads, hashes and checks the whole file in one blocking call whose time grows with the file size, and it
cannot be cancelled. Call it once per model, off the main thread, and share the resulting `DetectorModel` between
workers. While instances are created, the memory needed is several times the model's file size.

## Recommended: bundle the model and hard-code its hash

Ship the model inside your app (Android assets, the iOS app bundle, a JVM resource) and write its SHA-256 as a
constant in your app's code, not in a file next to the model:

- it is exactly the model you measured, because the hash in your code can only match that file;
- it works offline;
- a compromised server cannot change it: there is no server in the loop.

```kotlin
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.ModelSource

// The SHA-256 of the model you measured and approved, hard-coded in your app: `shasum -a 256 model.onnx`.
// This placeholder matches no file, so loading fails with ModelIntegrity until you replace it.
private const val MODEL_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"

/** Call off the main thread: loading hashes and checks the whole file. */
fun loadModel(modelBytes: ByteArray): DetectorModel {
    val profile = DetectorProfile(
        format = DetectionFormat.YuNetLayout(),
        inputWidth = 640,
        inputHeight = 640,
        scoreThreshold = 0.5f, // example value; see "Score threshold"
        expectedSha256 = MODEL_SHA256,
    )
    // Throws FaceMaskException.ModelIntegrity when the file is not the one you approved (corrupted, or replaced after
    // you measured the model). Do not mask with another model: fail visibly, and never publish the original as if it
    // were masked.
    return DetectorModel.load(ModelSource.fromBytes(modelBytes), profile)
}

/** Create one masker per worker from the same DetectorModel; close it when done. */
fun createMasker(model: DetectorModel): FaceMasker = FaceMasker(model)
```

The example profile above shows the shape of a profile. It is not a recommendation of a model or of a threshold, and it
says nothing about any model's licence or its suitability for your photos. `DetectionFormat.YuNetLayout` describes
one named-output layout; `DetectionFormat.GenericDetections` describes decoded rows or columns of boxes and scores
for other models. Read the asset or resource bytes with
your platform's own API and pass them to `ModelSource.fromBytes`, or pass a regular file's path to
`ModelSource.fromFile` (a symbolic link as the last path component, a directory, a FIFO or a device is refused; the runtime never receives the
path).

The same in Java, with the profile builder (`format`, `inputSize`, `scoreThreshold` and `expectedSha256` have no
default; `build()` names the first one that is missing). Passing `null` for the hash or the model is a Kotlin
`NullPointerException` before anything is read.

```java
import io.github.shishark.faceblur.DetectionFormat;
import io.github.shishark.faceblur.DetectorModel;
import io.github.shishark.faceblur.DetectorProfile;
import io.github.shishark.faceblur.FaceMasker;
import io.github.shishark.faceblur.ModelSource;

public final class ModelLoading {
    // The SHA-256 of the model you measured and approved, hard-coded in your app.
    // This placeholder matches no file, so loading fails with ModelIntegrity until you replace it.
    private static final String MODEL_SHA256 =
        "0000000000000000000000000000000000000000000000000000000000000000";

    /** Call off the main thread. */
    public static DetectorModel loadModel(byte[] modelBytes) {
        DetectorProfile profile = new DetectorProfile.Builder()
            .format(new DetectionFormat.YuNetLayout())
            .inputSize(640, 640)
            .scoreThreshold(0.5f)
            .expectedSha256(MODEL_SHA256)
            .build();
        return DetectorModel.load(ModelSource.fromBytes(modelBytes), profile);
    }

    public static FaceMasker createMasker(DetectorModel model) {
        return new FaceMasker(model);
    }
}
```

When you replace the model in a new app version, compute the new hash and ship it in the same update.

## How to get the hash

On macOS and Linux (Linux can also use `sha256sum`):

```
shasum -a 256 model.onnx
```

The first field of the output is the value for `expectedSha256` (64 hexadecimal digits, followed by the file name).

`openssl dgst -sha256 model.onnx` prints the same digest. After a successful load, `model.info.sha256` is the hash of
exactly the bytes the runtime receives and always equals the profile's `expectedSha256`.

## Advanced: model updates over the air

Downloading a new model after the app is installed is allowed, with all of these conditions:

1. The download and network code is in **your app**. FaceBlur has no network code and never fetches a model.
2. The model and its hash arrive together in a **manifest that you sign** (with a key only you hold).
3. The app holds the matching **public key** and verifies the manifest's signature **before** the hash is passed to
   FaceBlur.
4. Every new model is **measured with faceblur-eval** on your own annotated photos before you roll it out.

In outline:

```
manifest = download(manifestUrl)
if (!verifySignature(manifest, publicKeyInApp)) -> stop, keep the bundled model
modelBytes = download(manifest.modelUrl)
profile    = DetectorProfile(..., expectedSha256 = manifest.sha256)   // only after the signature check
model      = DetectorModel.load(ModelSource.fromBytes(modelBytes), profile)
```

This outline is not code you can paste: use a signature library you have reviewed, and keep the bundled model as the
fallback.

> **A hash fetched without a signature protects against nothing.** Whoever can replace the model on a server can
> replace the hash next to it. A hash only helps when it comes from somewhere an attacker cannot change: your app's
> code, or a manifest signed with a key the attacker does not have.

## Models chosen at run time (user-picked files)

The demo apps let a person pick a model file. After the pick they show the file's SHA-256 and ask "Use this model?";
the hash is passed to FaceBlur only after the person confirms. If your app accepts an untrusted file, do the same.
The size cap, the hash and the pre-check still run on every load, and they read the whole file, so a very large file
costs time (the size cap bounds it). A confirmed hash says which file the person chose, not that the file is a good
detector.

## Errors you may see

| Error | Meaning | What to do |
|---|---|---|
| `ModelIntegrity` | The file's SHA-256 differs from `expectedSha256`. | Check you load the file you hashed; recompute the hash after a model change. |
| `ModelRejected` (`TOO_LARGE`) | The file is larger than `maxModelBytes`. | Raise the cap only if you trust the file; the default is 50 MiB. |
| `ModelRejected` (other codes) | The pre-check, the input/output comparison with the profile or the contract pass refused the model. | Read the finding codes; they are for logs and support, treat unknown codes as a failure too. |
| `InvalidConfiguration` (`expectedSha256`) | The value is not 64 hexadecimal digits. | Copy the first field of the `shasum` output. |
| `RuntimeUnavailable` | ONNX Runtime could not be set up on this device, or a required session option cannot be set. On iOS in a Kotlin Multiplatform app: the bridge was not registered before the first `FaceMasker` (see [the iOS guide](kmp-ios.md)). Thrown by `FaceMasker(model)`. | Check the dependency and, on iOS, the bridge registration. |
| `IoFailed` | The file could not be read. The message never contains the path. | Check the file and its permissions. |

The loaded model's `info.warnings` may contain `SMALL_INPUT`: with a small model input, medium-size faces may be
missed between the tile passes and the whole-photo pass. It never stops loading; measure with faceblur-eval.

## What your app is responsible for

- The model, its licence and its training data; FaceBlur ships and judges none of them.
- The profile (colour order, pixel scaling, box format, coordinate space, score threshold) and checking it with your
  own photos.
- Where the model and its hash come from, and not weakening that chain.
- Detection can miss faces. Masks cover what the model finds.
