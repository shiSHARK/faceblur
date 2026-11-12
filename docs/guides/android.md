<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur on Android

This guide shows how to add FaceBlur to an Android app (Kotlin or Java), mask faces in a photo file or in a `Bitmap`,
run it off the main thread with cancellation, and keep network permissions out of your app if it does not need them.
FaceBlur masks the faces that your model finds with opaque masks, on the device, as data minimisation. Detection can
miss faces, and masks cover only the faces that it finds. The code examples in this guide are also kept as standalone source files in
[`snippets/android/`](snippets/android/).

Related guides: [Providing your model](providing-your-model.md), [Score threshold](score-threshold.md),
[Threading, cancellation and memory](threading-and-memory.md).

## What FaceBlur does and does not do on Android

- It finds faces with a model that your app provides and covers each with an opaque mask. There is no blur, no
  pixelation and no transparent or partial style. The settings can only make masks larger: a face's mask is 1.6 times
  the width and 1.8 times the height of its box by default, and these defaults are also the smallest values allowed
  (up to 3.0).
- File mode writes a new JPEG that is built from the masked pixels. It carries none of the source file's metadata, unless
  you opt in to keep some (`FileOptions.metadata`, `MetadataPolicy.KeepAllowlist`; each group keeps personal data in the
  output and works against data minimisation, so use it only when the user asked for it). In-memory mode changes pixels only; the encoder you use decides what a file contains.
- FaceBlur contains no network code.
- It does not ship a model, does not judge whether your model suits your photos, and can miss faces.
  Check your setup as described in [Score threshold](score-threshold.md).

## Setup

Requirements: `minSdk` 30 (Android 11) or higher.

```kotlin
// build.gradle.kts of your app module
dependencies {
    implementation("io.github.shishark:faceblur:0.1.0")
}
```

The artifact is published on Maven Central, so `mavenCentral()` must be in the repositories of your project. For
Groovy build files: `implementation 'io.github.shishark:faceblur:0.1.0'`. Gradle picks the Android variant of this multiplatform
coordinate. ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`) comes with it as a dependency.

Import the Android calls from `io.github.shishark.faceblur`. In Kotlin the Android forms are extension functions on
`FaceMasker` (`import io.github.shishark.faceblur.maskFile`, `detectFile`, `maskJpeg`, `maskInPlace`, `applyMasks`,
`detect`). In Java they are static methods of `FaceMaskerAndroid` that take the masker as the first argument, for
example `FaceMaskerAndroid.maskFile(masker, context, uri, target)`.

## Provide your model

FaceBlur ships no model. Your app bundles one, describes it in a `DetectorProfile` and hard-codes its SHA-256; a file that
does not match is refused with `ModelIntegrity` before it is parsed. The full rules, the Java version and the hash
command are in [Providing your model](providing-your-model.md). On Android, read the model from your assets and pass
the bytes:

```kotlin
// snippets/android/ModelLoading.kt (shortened)
fun loadBundledModel(context: Context): DetectorModel {
    val bytes = context.assets.open("model.onnx").use { it.readBytes() }
    val profile = DetectorProfile(
        format = DetectionFormat.YuNetLayout(), // the layout of YOUR model
        inputWidth = 640,
        inputHeight = 640,
        scoreThreshold = 0.5f, // example value; see "Score threshold"
        expectedSha256 = MODEL_SHA256, // the SHA-256 of YOUR model file, hard-coded in your app
    )
    return DetectorModel.load(ModelSource.fromBytes(bytes), profile)
}
```

The model, its licence and its training data are your responsibility; assess your own obligations for them. Loading
reads and hashes the whole file and cannot be cancelled: do it once, off the main thread. Then create one
`FaceMasker(model)` per worker and call `close()` when you are done with it.

## File mode

File mode reads an image file (JPEG, HEIC or PNG), decodes it with the platform decoder, covers the faces and writes a
new JPEG to a target. Nothing is written when a call is cancelled or fails before the single write (every check runs first). The two
exceptions on Android, `NewFile` without `replaceExisting` and content-Uri targets, are described under
[Targets and overwrite rules](#targets-and-overwrite-rules); after an error with a content-Uri target, treat the target
as not masked. Coordinates in results are pixels of the
working image: the source with its orientation applied, at a long side of 3,500 px by default (never scaled up). All three
forms are in `snippets/android/FileMode.kt`.

| Source | Call (Kotlin) | Notes |
|---|---|---|
| File path | `masker.maskFile(sourcePath, target)` | Member of `FaceMasker`. |
| `content:` or `file:` Uri | `masker.maskFile(context, uri, target)` | Read for this call only; the `Context` is not kept. |
| Streams | `masker.maskJpeg(input, output)` | Reads the whole source into memory (JPEG, HEIC or PNG; the name refers to the output); neither stream is closed. |

`detectFile` (path and Uri forms) finds the faces and writes nothing; `detectFile(...).toMaskPlan()` gives a plan that you
can edit with `MaskPlan.withMasks(...)` and pass to `maskFile(..., plan = plan)`.

```kotlin
fun maskUri(masker: FaceMasker, context: Context, source: Uri, target: Uri): FileResult =
    masker.maskFile(context, source, AndroidOutputTargets.newFile(target))
```

### Targets and overwrite rules

| Target | Meaning |
|---|---|
| `OutputTarget.NewFile(path)` | A new file at `path`. An existing file is not replaced: the call fails with `UnsupportedTarget`. |
| `OutputTarget.NewFile(path, replaceExisting = true)` | Replaces an existing file in one step (written next to it, then renamed over it). |
| `OutputTarget.OverwriteSource` | Replaces the source file in one step. Only for a file path or a `file:` Uri. A `content:` source is refused with `UnsupportedTarget`. |
| `AndroidOutputTargets.newFile(uri)` | An empty document, for example from `ACTION_CREATE_DOCUMENT`. The provider must allow reading and writing it; FaceBlur reads the written content back. A non-empty document is refused. |
| `AndroidOutputTargets.overwriteNonAtomic(uri)` | Explicit opt-in to replace the content of an existing document, also the source. Not atomic (see below). |

Two things to know before you choose a target:

- Android does not let apps create hard links, so a `NewFile` without `replaceExisting` is created at its path and written
  there. Until the call returns, and after a process kill, that file can hold an incomplete file of masked output (never
  anything of the source). On a reported failure it is removed. Do not read "the file exists" as "the call finished": use
  the call's result. A retry with the same `NewFile` fails with `UnsupportedTarget` until your app removes the file.
- A content-Uri target cannot be replaced in one step. **After any error from a call with a content-Uri target, treat the
  target as unmasked and do not share it.** A process kill or a provider failure inside the write can leave the target
  empty, damaged, or holding the start of the masked output followed by bytes of the original, which can include original
  pixels. Prefer a new document or a file target; use `overwriteNonAtomic` only when you must and accept this.

### Streams and the 64 MiB limit

The stream form reads the source into memory: the first bytes must show a JPEG, HEIC or PNG, then at most 64 MiB
(67,108,864 bytes) are read. This limit is fixed. A longer stream fails with `SourceTooLarge` (it has
`limitBytes`) before anything is decoded, and nothing is written to the output stream. **For larger inputs use the file
path or the Uri form**: the platform decoder reads those from the file or the provider, and the limit does not apply.
The buffer counts in the memory check of parallel calls; for sources near the limit, run one stream call at a time.
If the output stream itself fails during the single write, it may hold part of the masked JPEG (never anything of the
source); making that atomic is your part.

### File changed between detection and masking

A plan from `detectFile` is bound to the decoded pixels. If the file changes before `maskFile` runs, the call fails with
`SourceChanged` and writes nothing. Run `detectFile` again on the current file and, if your app shows masks to a person
for review, show the new result again before masking. A plan built with the public `MaskPlan` constructor is refused with
`MaskPlanMismatch` in file mode.

## In-memory Bitmap mode

Use this when your app already holds a `Bitmap`. `detect(bitmap)` finds faces; `maskInPlace(bitmap)` detects and masks in
place; `applyMasks(bitmap, plan)` writes a plan you made or edited. All three block, so call them off the main thread.

Bitmap rules, checked before any work (a violation throws `UnsupportedImage`, and the message starts with the reason and
names the fix):

- not recycled, not `HARDWARE`, and `ARGB_8888`;
- for `maskInPlace` and `applyMasks` also **mutable and in the sRGB colour space**. A bitmap in another colour space
  (for example Display P3) is refused with reason `CONFIG`, because it would not read back exactly; `detect` still accepts
  it. FaceBlur never copies your bitmap: decode as in the example. For an immutable bitmap, copy it yourself with
  `bitmap.copy(Bitmap.Config.ARGB_8888, true)`. For another colour space, draw it once into
  `Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))` and continue
  with that bitmap.

```kotlin
// snippets/android/InMemoryBitmap.kt
fun decodeForMasking(context: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        // Very large photos: decode smaller, but keep the long side at 3,500 px or more.
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide >= 2 * 3_500) decoder.setTargetSampleSize(longSide / 3_500)
    }

fun maskAndEncode(masker: FaceMasker, bitmap: Bitmap, output: OutputStream): Boolean {
    masker.maskInPlace(bitmap) // on any exception do not encode, show or share the bitmap
    return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) // false: discard output, it may be partial
}
```

For photos much larger than needed, decode with `setTargetSampleSize` as shown, so that the long side stays at least
3,500 px (or its own size); a full-size decode of a very large photo can run out of memory before FaceBlur starts. If
`compress` returns `false`, discard `output`: it may hold a partial file.

What to know:

- If a call throws a `FaceMaskException`, the bitmap is untouched, with two exceptions: after `MaskWriteFailed` discard
  the bitmap, and after `Cancelled` requested during the final copy the bitmap is fully masked (you cannot tell the
  cases apart, so treat `Cancelled` as not masked). After any other error, such as an `OutOfMemoryError`, discard the
  bitmap. Never publish the original instead of a bitmap that was not masked, for example after `NotEnoughMemory`.
- Encode, show or share the bitmap only after the call has returned. FaceBlur does not encode or touch metadata in this
  mode; check what your own encoder writes, and never copy metadata segments from the source file into the output.
- Pass the bitmap at a long side of at least 3,500 px (or its own size if smaller) and scale it down only after masking:
  detection quality depends on the face size in pixels. Each side may be at most 65,535 px.
- Android 14 and newer: if the bitmap carries an HDR gain map, `maskInPlace` and `applyMasks` remove it (also when no face
  is found), so that a later encode cannot write the unmasked faces through it. The HDR look of the photo is lost;
  `MaskResult.gainMapRemoved` tells you.
- Apply a plan only to the bitmap it was made for: the call checks the size, not the pixels.

## Threading and cancellation

Every call on this page blocks and none is rejected on the main thread, but all of them can freeze a screen. Always call
them from a background thread and give people a way to cancel. One `FaceMasker` runs one call at a time; a second call
that enters while one is running throws `ConcurrentUse`. For parallel work create one masker per worker from the same
`DetectorModel`, and run at most 2 masking calls in parallel on phones (see
[Threading, cancellation and memory](threading-and-memory.md) for the memory rules).

Cancellation is cooperative: you pass a `CancellationToken` (one property, `isCancellationRequested`). FaceBlur reads it
before detection, between detector passes and immediately before writing; a pass already inside the runtime is not
interrupted. A cancelled call throws `Cancelled` and writes nothing in file mode.

### Kotlin coroutines

Run the call in `withContext(Dispatchers.Default)` and let the token read the coroutine's job. Use a `Mutex` so that
one masker never gets two calls at once. `kotlinx.coroutines` comes with FaceBlur as a dependency. This is plain
code in your app; FaceBlur has no coroutine wrapper in this version.

```kotlin
// snippets/android/CoroutineCancel.kt
class MaskingService(private val masker: FaceMasker) {
    private val oneAtATime = Mutex()

    suspend fun maskFile(context: Context, source: Uri, target: OutputTarget): FileResult =
        oneAtATime.withLock {
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext().job
                val token = object : CancellationToken {
                    override val isCancellationRequested: Boolean get() = !job.isActive
                }
                try {
                    masker.maskFile(context, source, target, cancellation = token)
                } catch (e: FaceMaskException.Cancelled) {
                    throw CancellationException("masking cancelled") // not masked: do not publish
                }
            }
        }
}
```

### Java: CompletableFuture

Java callers use the blocking API on their own executor. `CompletableFuture.cancel()` does not stop a running task, so
wire it to the token yourself: when the future is cancelled, set the flag that the token reads.

```java
// snippets/android/JavaAsync.java
AtomicBoolean stop = new AtomicBoolean(false);
CancellationToken token = stop::get;
CompletableFuture<FileResult> future = CompletableFuture.supplyAsync(
        () -> FaceMaskerAndroid.maskFile(
                masker, context, source, AndroidOutputTargets.newFile(target),
                new FileOptions(), null, token),
        executor);
future.whenComplete((result, error) -> {
    if (future.isCancelled()) {
        stop.set(true);
    }
});
```

The Java form of `maskFile` with a token takes the options and the plan before it (`new FileOptions()` and `null` here).
After you cancel, treat the target as not produced. As with every call, one masker handles one call at a time: give each
worker its own `FaceMasker`.

## Apps that must have no network permission

FaceBlur contains no network code and switches ONNX Runtime's telemetry off. It also removes ONNX Runtime's telemetry
start-up provider (`ai.onnxruntime.TelemetryInitializer`) from your app's manifest. ONNX Runtime, a FaceBlur dependency,
still declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`, so a plain app that adds
FaceBlur gets both permissions in its merged manifest. FaceBlur does not remove them, because a library cannot do that
without risking the permissions of other libraries.

If your app must have no network permission, add these two lines to your app's own `AndroidManifest.xml`
([`snippets/android/AndroidManifest-no-network.xml`](snippets/android/AndroidManifest-no-network.xml)):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />

    <application>
        <!-- your app as before -->
    </application>
</manifest>
```

These lines remove both permissions from the whole app, including declarations by any other library. Use them only if no
part of your app needs network access. If it does, do nothing: declare `INTERNET` as usual.

Check the result in the merged manifest (Android Studio: Merged Manifest tab) or on the built APK:

```
aapt2 dump permissions app-release.apk
```

Neither permission should be listed. Do not rely on the manifest merger report for this check: it can print
`ADDED ... REJECTED` lines for the telemetry provider although the provider is absent from the merged manifest and the APK.

FaceBlur sets the process environment variable `ORT_DISABLE_TELEMETRY=1` before its first model session and then calls
`setTelemetry(false)`. If your app also uses ONNX Runtime directly, the variable applies to your sessions too. If your
app initialises ONNX Runtime before the first FaceBlur call, the variable cannot take effect and only
`setTelemetry(false)` applies.

If you list permissions in a store form, assess the declarations of your final merged manifest for yourself.

## Bring your own detector

If your app already has a face detector, you can use FaceBlur only for the masking. `PlanMasker` loads no model and no
ONNX Runtime. `planFile(context, uri)` decodes the file and returns an empty plan bound to it; you add the masks for
your boxes with `Mask.manual(...)` and `plan.withMasks(...)`; `maskFile(context, uri, target, plan)` writes them. It
detects nothing: **a face your detector missed stays visible.**

The frame of your boxes matters. The plan uses the **upright** frame: the photo turned by its orientation tag, top-left
origin, scaled to the plan's working size. Phone photos are often stored rotated, and `BitmapFactory` ignores the
orientation tag, so boxes from a detector that works on a `BitmapFactory` bitmap are in the stored (raw) frame. Used as
they are, the masks land in the wrong place while `maskFile` reports success and the faces stay visible. Convert each
box with `UprightBox.fromRaw(left, top, right, bottom, rawWidth, rawHeight, orientation)` (orientation 1 to 8, as in EXIF;
use 1 for a missing tag), then scale by `plan.width / imageWidth` and `plan.height / imageHeight`:

```kotlin
// snippets/android/ExternalDetector.kt (the core)
val planMasker = PlanMasker()
val empty = planMasker.planFile(context, source) // first: binds the plan to the decoded pixels
val raw = detector.detect(context, source) // then: your detector reads the file
val orientation = readOrientation(context, source)
val masks = raw.boxes.map { box ->
    val upright = UprightBox.fromRaw(
        box.left, box.top, box.right, box.bottom, raw.rawWidth, raw.rawHeight, orientation,
    )
    val sx = empty.width.toFloat() / upright.imageWidth
    val sy = empty.height.toFloat() / upright.imageHeight
    Mask.manual(upright.left * sx, upright.top * sy, upright.right * sx, upright.bottom * sy, planMasker.masking)
}
return planMasker.maskFile(context, source, AndroidOutputTargets.newFile(target), empty.withMasks(masks))
```

`UprightBox.fromRaw` does not read the file, so it cannot check that the orientation value is the file's own. A wrong
value that keeps the frame's shape (a 180 degree turn, a mirror) goes unnoticed. If the file changes after `planFile`,
`maskFile` fails with `SourceChanged`; run both steps again on the current file. Do not add masks saved earlier to a plan
for a new image without detecting again.

## Errors

All errors are subclasses of `FaceMaskException` (unchecked). Messages are fixed text and never contain paths, URIs,
pixel data or coordinates. Finding codes in `ModelRejected`, `ModelOutputInvalid` and `OutputValidationFailed` are for
logs: treat every code, known or not, as a failure.

| Error | When | What to do |
|---|---|---|
| `InvalidConfiguration` (`parameter`) | A setting, mask or plan value is out of range (also the masks' summed working area above 32 times the image area). | Fix the value named by `parameter`. |
| `ImageTooLarge` | The source is over 200,000,000 pixels, or the working image is over 65,535 px per side or `maxDecodedPixels` (default 25,000,000). | Nothing was decoded. Refuse the photo or raise `FileOptions.maxDecodedPixels` within its range, if the memory allows. |
| `UnsupportedImage` | Not a JPEG, HEIC or PNG image the platform decodes; or a `Bitmap` that breaks the rules above. | Read the reason at the start of the message. |
| `UnsupportedTarget` | The target cannot be used: existing file without `replaceExisting`, `OverwriteSource` for a `content:` source, a non-empty document for `newFile`, `AndroidOutputTargets` with the common path form. | The message names the form to use. Nothing was written. |
| `SourceTooLarge` (`limitBytes`) | A stream source is over 64 MiB. | Use the file path or the Uri form. |
| `SourceChanged` | File mode: the file changed after the plan was made. | Detect again on the current file, show the result again if people review masks. |
| `MaskPlanMismatch` | The plan is for another image size, or in file mode was not made by `detectFile` or `planFile`. | Fix the code that builds the plan. |
| `IoFailed` | The model, source, target or a stream cannot be read or written; or the target does not hold exactly the written output. | Check access. After a content-Uri target error, treat the target as unmasked. |
| `ModelIntegrity` | The model's SHA-256 differs from `expectedSha256`. | Load the file you hashed. See [Providing your model](providing-your-model.md). |
| `ModelRejected` | The model or its profile was refused when loading or creating the masker. | Read the finding codes. |
| `ModelOutputInvalid` | The model's output failed the checks (with `TOO_MANY_MASKS` the score threshold is very likely too low). | See [Score threshold](score-threshold.md). Nothing was written. |
| `RuntimeUnavailable` | ONNX Runtime could not be set up on this device, or a required session option cannot be set. | Check the dependency and your ONNX Runtime usage. |
| `OutputValidationFailed` | The encoded file failed the library's own structure and metadata check. | Nothing was written. Treat it as a failure. |
| `ConcurrentUse` | A second call entered the same masker. | One masker per worker. |
| `Closed` | The masker was closed. | Create a new one. |
| `Cancelled` | Your token asked to stop. | Treat as not masked. Nothing is written in file mode. |
| `NotEnoughMemory` | The working area did not fit in the free heap. The image was not masked. | Do not publish the original. Retry later, with less in parallel or a smaller image. |
| `MaskWriteFailed` | In-memory: your bitmap failed while the masks were copied in. | Discard the bitmap. |
| `Internal` | A fault of the library itself. | Discard the image and report it. |

## Limits

| Limit | Value |
|---|---|
| Android | `minSdk` 30 |
| Input formats | JPEG, HEIC and PNG, decoded by the Android decoder; output is always JPEG |
| Working image | long side 3,500 px by default (`FileOptions.maxLongSide`: 0, which means the source size and is still bounded by the largest working image below, or at least 3,500); never scaled up |
| Largest working image | 25,000,000 px by default; a source is refused above 200,000,000 px, a side above 65,535 px |
| Stream source | 64 MiB, fixed |
| Model file | 50 MiB by default (`DetectorModel.load` `maxModelBytes`) |
| Masks per image | at most 65,536; summed working area at most 32 times the image area |
| Parallel masking | at most 2 calls at a time is recommended on phones |
| Memory of one masking call | the working area grows with the masks; worst case up to 4.3 bytes per pixel plus 32 MiB (see [Threading, cancellation and memory](threading-and-memory.md)) |

Slower phones take longer: run masking off the main thread and offer cancellation.

## What your app is responsible for

- The model, its licence and its training data, and the profile (see [Providing your model](providing-your-model.md)).
- Checking your setup on your own photos; detection can miss faces, and masks cover only what the model finds.
- Never publishing, uploading or sharing an original as if it were masked after an error or a cancellation.
- Your own legal and platform obligations (for example store declarations and data-protection duties): FaceBlur gives
  facts about what it does, not an assessment of your obligations.
