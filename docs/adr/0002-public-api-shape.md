# 0002. Public API shape: two modes on Kotlin, Java and Swift

Status: Accepted. The protection-weakening table in decision 10 is approved by the project owner, with its additions:
the 3,500 px floor applies to detection, not to the output; the score threshold stays fixed until a measurement and
a new approval; the default style is `GRADIENT_OVAL` under the opacity invariant; the pixel limit never lowers the
detection size. (The fixed score threshold was later replaced by the required, developer-chosen profile value of
ADR 0012.) Decisions 1, 2, 5, 9 and 10 are revised for the developer-supplied model (ADR 0012); the revised parts
were accepted on 2026-11-02 after code review and security review. Decisions 7, 8 and 9 are revised for the
bounded mask stage (ADR 0013, accepted 2026-11-02); decision 9 is aligned with ADR 0013's amendment of the same day
(the remaining-heap check on Android and the JVM). Amended 2026-11-03 (project owner): decision 8 gets the
documentation rule "off the main thread, with cancellation"; decision 9 the final mask limits and the actionable
`TOO_MANY_MASKS` message; the evidence column of decision 10 gives technical rationales only. Amended again
2026-11-03 (project owner): decisions 9 and 10 name the final in-pass NMS work bound and its actionable
`TOO_MANY_NMS_PAIR_CHECKS` message. Amended 2026-11-03 (project owner): mask area factor lowered from 64 to 32
(decision 9). Decisions 2 and 4 refer to ADR 0015 (Proposed 2026-11-03) for the Android binding without a setup
call and the common/Android split of file mode. Amended 2026-11-03 (project owner): the Kotlin coroutine wrapper and
the Java `CompletableFuture` wrapper of decisions 1 and 8 are deferred to 0.2; 0.1.0 has the blocking API (plus the
Swift facade's actor), and the guides show calling it from a coroutine with cancellation. The Java-friendly shape of
the blocking API (static factories, builders, `@JvmOverloads`) stays. Amended 2026-11-03 (project owner): the stream
form reads at most 64 MiB, fixed; a longer stream throws the new `SourceTooLarge(limitBytes)` (decisions 4, 9, 10;
ADR 0015 decision 4). Amended 2026-11-03 (project owner, ADR 0015 decision 6): a file-mode plan is bound to the
decoded pixels; a changed source throws the new `SourceChanged`; `MaskPlan.withMasks` keeps the binding; file mode
refuses plans without one; the new `PlanMasker` makes and applies plans for masks from the app's own detector
without a model (decisions 1, 4, 6 and 9, Verification). Amended after the review of that batch (project owner
for the helper): the fingerprint is unreachable from Java, `planFile` warns about the frame of the app's boxes, and
the new `UprightBox.fromRaw` converts a box from the stored frame into the plan's upright frame (decisions 1, 4, 6,
10 and Verification).
Date: 2026-11-01 (revised 2026-11-01 after review: Android file-mode detection for URIs, wrapper cancellation types,
out-of-memory handling, Java async lifetime, cancellation during mask writing; configuration errors, default masking
of `toMaskPlan()`, file-mode result fields; revised again the same day for the product principle "only
privacy-reliable options" (no cosmetic styles, detection tuning not exposed, protection-weakening settings listed),
the public names, the explicit content-URI overwrite opt-in, the decoded-image pixel limit and ADR 0006's acceptance;
revised again the same day after the owner approved decision 10: detection floor with scaling after masking,
`GRADIENT_OVAL` default and the opacity invariant, pixel limit 13-100 MP that fails instead of detecting smaller,
fail-closed errors, opaque alpha inside masks with an outward alpha blend, no candidate cap; revised 2026-11-02 with
ADR 0010/0011: `close()` never throws and releases after a running call, creation runs the known-answer check,
construction thread rule, Swift colour without alpha; later on 2026-11-02: pixel-centre convention, the owner's
opacity rule with a soft-edge band width fixed in pixels, mask-coordinate bound, fatal-error rule; later on
2026-11-02: `OutputValidationFailed.findings` and the file-mode order of the per-side check; later on 2026-11-02:
the developer-supplied model of ADR 0012 (model types, construction, effective mask expansion, model errors, the
model rows of the settings table, `onnxThreads` 0 to 4); amended later: the expected SHA-256 of the model is
required (project owner's decision; `DetectorProfile` parameter order, the settings row, `ModelIntegrity`, ABI
expectations))

## Context

FaceBlur must offer two API modes with identical detection and masking:

- **File mode**: the app passes an input file (path or URI) and settings; the library decodes once, bakes the
  orientation, optionally scales down to a maximum long side, detects and masks faces, and encodes once to a new
  file or over the input. Settings at least: output target, maximum long side, output format and quality,
  detection and masking options, metadata policy.
- **In-memory mode**: the app passes an image it has already decoded; the library only detects and masks, with no
  decoding, encoding, metadata or file access, and the app's pipeline continues with the same image.

Both modes return the detected faces (boxes, scores) and timings, can re-apply user-edited masks, and give
identical masks for the same pixels; a test proves the two modes agree. Calls block and must run off the main
thread; one detector instance per thread. In 0.1.0 only the Swift facade has an async form (its actor); the Kotlin
coroutine wrapper and the Java `CompletableFuture` wrapper (`AsyncFaceMasker`) are deferred to 0.2 (decision 8).

Consumers: Android apps in Kotlin and Java, KMP apps (`commonMain`), Swift/SwiftUI apps through the hand-written
facade in `faceblur-swift` (ADR 0001). The code being extracted is organised for benchmarking: an `Anonymizer`
object, `AnonymizationConfig`, `RemovalMode`, `PixelAccess`, `FaceBox` with landmarks, `@Serializable` data
classes, and a per-detector score map. The earlier API sketch used `FaceAnonymizer` and `process`/`processJpeg`.

The app supplies the face-detection model and describes it with a profile; FaceBlur ships no model (ADR 0012).

Forces:

- Wording: public names, KDoc and docs describe data minimisation with opaque masks. A public type called
  "Anonymizer" puts a claim the library cannot make into every auto-complete list, stack trace and API page.
- Swift: a type with the same name as its module (`FaceBlur.FaceBlur`) breaks module-qualified references.
- Kotlin/Native: an exception that crosses into Objective-C/Swift without `@Throws` terminates the process.
- Java: no default arguments, no `suspend`, no value classes in signatures.
- ABI stability in 0.x: public data classes leak `componentN`/`copy` into the ABI; every public type is reviewed
  through the ABI dump (`checkKotlinAbi`).
- In-place masking cannot be undone, so "edit the masks" has to happen before masks are written.
- The developer-experience proposal for this API (internal review of names, call sites, errors, cancellation)
  was used as the starting point.

## Decision

### 1. Names (one vocabulary on every platform)

| Concept | Kotlin / Java | Swift facade | Notes |
|---|---|---|---|
| Entry point | `FaceMasker` (class, `AutoCloseable`) | `FaceMasker` (actor) | not `FaceBlur` (module clash), not `FaceAnonymizer` (wording) |
| Instance settings | `FaceMaskerConfig` | `FaceMaskerConfiguration` (struct) | masking + `onnxThreads` |
| Model source | `ModelSource` (`fromBytes`, `fromFile`) | `ModelSource` (enum: `.data`, `.file`) | ADR 0012 |
| Model profile | `DetectorProfile`, `DetectionFormat` (`YuNetLayout`, `GenericDetections`), `PixelScaling`, `RowLayout`, `BoxFormat`, `CoordinateSpace`, `ColorOrder`, `TensorLayout` | same names (structs / enums) | declared by the developer, validated by FaceBlur |
| Loaded model | `DetectorModel` (`load`) | `DetectorModel` (final class) | immutable, shared by all instances |
| Model report | `ModelInfo`, `TensorInfo`, `MaskerModelInfo` | same names (structs) | SHA-256, size, IO; per instance also the runtime version, execution provider and FaceBlur version |
| Profile check | `ExpectedFace`, `ProfileCheckResult` | same names (structs) | optional helper |
| Mask geometry and style | `MaskingConfig`, `MaskStyle`, `MaskShape` | `MaskingConfiguration`, `MaskStyle`, `MaskShape` (enums) | replaces `AnonymizationConfig`, `RemovalMode`; opaque styles only (decision 10) |
| Detected face | `FaceBox` (left, top, right, bottom, score) | `FaceBox` (struct) | pixel coordinates, no landmarks |
| One mask | `Mask` | `Mask` (struct) | final geometry + origin |
| Masks for one image size | `MaskPlan` (width, height, masks; `withMasks`) | `MaskPlan` (struct; `withMasks`) | size-checked re-application; file-mode plans bound to the decoded pixels (decision 6) |
| Masking without a model | `PlanMasker` (`planFile`, `maskFile`) | `PlanMasker` (struct) | masks from the app's own detector in file mode; no model, no ONNX Runtime (ADR 0015 decision 6) |
| Box in the plan's frame | `UprightBox` (`fromRaw`) | `UprightBox` (struct) | converts a box from the stored (unrotated) frame, given the raw size and the orientation value 1 to 8 (ADR 0015 decision 6) |
| Detection result | `Detection` | `Detection` | faces + image size + timings |
| Masking result | `MaskResult` | `MaskResult` | faces, masks, timings, flags |
| File-mode result | `FileResult` | `FileResult` | + source size, output size and bytes |
| Timings | `Timings` | `Timings` | milliseconds as `Double`; `null` = step not performed |
| Common pixel interfaces | `PixelImage` (read), `MutablePixelImage` (read/write) | not exposed (facade takes CoreGraphics/CoreVideo/vImage types) | non-premultiplied ARGB ints |
| File-mode options | `FileOptions`, `OutputTarget`, `OutputFormat`, `MetadataPolicy` | same names (struct / enums) | |
| Errors | `FaceMaskException` (sealed) | `FaceMaskError` (enum) | |
| Cancellation | `CancellationToken` (interface) | Swift `Task` cancellation | |
| Coroutine wrapper | `masker.suspending(dispatcher)` → `SuspendingFaceMasker` | — | deferred to 0.2 (decision 8); not in 0.1.0 |
| Java async wrapper | `masker.async()` / `masker.async(executor)` → `AsyncFaceMasker` (`CompletableFuture`) | — | Android and JVM source sets only; deferred to 0.2 (decision 8); not in 0.1.0 |

Verbs: `detect`, `applyMasks`, `maskInPlace`, `detectFile`, `maskFile`, `maskJpeg`, `planFile`, `withMasks`,
`checkProfile`, `close`, and `DetectorModel.load`. The earlier
`process`/`processJpeg` names are retired. Internal packages that keep bench names are not public; the ABI dump
shows any leak. A test fails if a public symbol or KDoc heading contains "anonymi" (DX check).

### 2. Construction and lifetime

```kotlin
val model = DetectorModel.load(ModelSource.fromFile(path), profile)   // once; thread-safe, shared
public class FaceMasker(model: DetectorModel, config: FaceMaskerConfig = FaceMaskerConfig()) : AutoCloseable
```

- The app supplies the model as bytes or a file path and describes it with a `DetectorProfile` (ADR 0012).
  `DetectorModel.load` reads, size-checks, hashes and pre-parses it once; each worker then creates its own
  `FaceMasker` from the same `DetectorModel`.
- Both are usable from `commonMain` on every target, because the model arrives as bytes or a path: Android needs no
  `Context` to load it.
- iOS: the ONNX Runtime bridge must be registered once per process before the first `FaceMasker` is created.
  Swift apps get this from the facade automatically; KMP apps call `FaceBlurIos.registerOnnxRuntime(factory)` at
  start-up (ADR 0010). Creating a `FaceMasker` without a registered bridge throws
  `FaceMaskException.RuntimeUnavailable`, whose message names the missing setup step.
- Android: no setup step. The Android binding is found through the platform defaults of ADR 0015 decision 1 (no
  public symbol, no app-start component), so the one dependency line is enough from Kotlin, Java and common code.
- Creating an instance creates the runtime session from the verified bytes, compares the runtime's view of the
  model's inputs and outputs with the profile, and runs one contract pass (ADR 0012 decision 4). It blocks for that
  time; KDoc says to construct off the main thread, but construction is
  not rejected on the main thread (the main-thread check applies to the methods). Create once per worker and reuse;
  `close()` never throws, is thread-safe and idempotent, and releases the session (decision 8 describes a call
  that is still running); calls after `close()` throw `FaceMaskException.Closed`.
- Java: `@JvmOverloads` constructor; `FaceMaskerConfig.Builder`, `DetectorProfile.Builder`; `ModelSource` and
  `DetectorModel.load` are static factories.

### 3. In-memory mode (common)

```kotlin
public fun detect(image: PixelImage, cancellation: CancellationToken? = null): Detection
public fun applyMasks(image: MutablePixelImage, plan: MaskPlan, cancellation: CancellationToken? = null): MaskResult
public fun maskInPlace(image: MutablePixelImage, cancellation: CancellationToken? = null): MaskResult
```

- `detect` never writes pixels. `maskInPlace` = `detect` + `applyMasks` with the default masks.
- Coordinates are pixels of the image passed in (top-left origin). KDoc of every method states this in its first
  paragraph, together with "blocks; call off the main thread".
- `PixelImage`/`MutablePixelImage` exchange blocks of non-premultiplied ARGB ints (the bench's `PixelAccess`
  contract, renamed and made public). Generic implementations are scaled with a common area-averaging resampler;
  the platform adapters below use the platform's fast path internally.
- Platform overloads (thin, same semantics):
  - Android: `detect(bitmap)`, `applyMasks(bitmap, plan)`, `maskInPlace(bitmap)` (ADR 0003 sets the bitmap rules).
  - iOS (Kotlin, used by the Swift facade and KMP apps): `MutablePixelImage.wrapRgba(address, width, height,
    bytesPerRow, layout)` and `CGContextRef.asMutablePixelImage()` (ADR 0004).
- In-memory calls never touch metadata or encode. The KDoc of `maskInPlace` says so first, because an app that
  encodes afterwards decides what its file contains.
- Size guidance (KDoc of `detect` and `maskInPlace`, integration guide, samples): scale a photo to a long side of
  no less than 3,500 px (or keep its size if it is smaller) before calling FaceBlur, and scale it further down only
  after FaceBlur has masked it. Detection quality depends on the face size in pixels, and accuracy was measured at
  native resolution; this is the same detection floor that file mode applies by itself (decision 4). The library
  cannot check this in in-memory mode, because it does not know the original size.

### 4. File mode

```kotlin
// commonMain: file-system paths (Android app storage, JVM, iOS sandbox paths)
public fun detectFile(source: String, options: FileOptions = FileOptions(), cancellation: CancellationToken? = null): Detection
public fun maskFile(source: String, target: OutputTarget, options: FileOptions = FileOptions(),
                    plan: MaskPlan? = null, cancellation: CancellationToken? = null): FileResult
// androidMain: content and file URIs (photo picker, Storage Access Framework, MediaStore), and streams
public fun detectFile(context: Context, source: Uri, options: FileOptions = FileOptions(),
                      cancellation: CancellationToken? = null): Detection
public fun maskFile(context: Context, source: Uri, target: OutputTarget, options: FileOptions = FileOptions(),
                    plan: MaskPlan? = null, cancellation: CancellationToken? = null): FileResult
public fun maskJpeg(input: InputStream, output: OutputStream, options: FileOptions = FileOptions(),
                    cancellation: CancellationToken? = null): FileResult
```

- **Two sizes, two settings.**
  - `maxLongSide` sets the **working size**: file mode decodes, detects and masks on a *working image* whose long
    side is the source's long side, reduced to `maxLongSide` when the source is larger (`maxLongSide = 0`: the
    source size). Allowed: 0, or 3,500 px and more (the detection floor, decision 10). Faces are therefore always
    detected on at least 3,500 px, or on the source size if that is smaller.
  - `maxOutputLongSide` sets a **smaller output**: `0` (default) means the output has the working size; a value of
    64 px or more (ADR 0005 decision 1) scales the *masked* working image down to that long side after masking and
    before encoding. A value at or above the working size's long side means no scaling (never upscales; documented,
    not an error). It changes only
    the size of the encoded file, never which faces are detected or how the masks are placed.
- **Coordinate space (file mode).** All coordinates that file mode returns or accepts are in the pixels of the
  working image: `Detection.faces`, `Detection.width`/`height`, the `MaskPlan` built by `toMaskPlan()`, the plan
  passed to `maskFile(..., plan)`, and `FileResult.faces`/`masks`. The masks are written in that space, so a plan
  is re-applied without any conversion and an edited plan has exactly the effect it shows; its size must equal the
  working size (`MaskPlanMismatch` otherwise). The output size is reported separately (`Detection.outputWidth`/
  `outputHeight`, `FileResult.outputWidth`/`outputHeight`). An editing UI that shows the output-size image, or any
  other preview, maps a point with the factor `outputWidth / width` (the same factor for both axes up to rounding of
  the output size) and maps user-drawn masks back with the inverse factor before adding them to the plan. The
  frame is upright: the source turned by its orientation tag, top-left origin, the same frame as FaceBlur's own
  results. A box from a detector that does not apply the tag (the stored frame, for example from Android's
  `BitmapFactory`) is converted into this frame with `UprightBox.fromRaw(left, top, right, bottom, rawWidth,
  rawHeight, orientation)` (orientation value 1 to 8, all eight tested), and `planFile` warns about the pitfall (a
  box in the wrong frame masks the wrong place while the call reports success; ADR 0015 decision 6). Because
  the plan is applied before the scale-down, the output of `maskFile(..., plan)` equals the output of
  `maskFile(...)` without a plan, for the same source and options, also when the output is smaller.
- `detectFile` decodes exactly as `maskFile` does (same orientation handling, same working size, same decoder
  settings) and returns a `Detection` in working-image coordinates, so its `toMaskPlan()` fits
  `maskFile(..., plan)` for the same source and options. This is what makes the edit flow possible for photos that
  arrive as content URIs.
- `maskJpeg` takes no plan: a stream can be read only once, so the stream form is for one-shot pipelines; editing
  flows use the path or `Uri` forms.
- `FileOptions(maxLongSide = 3500, maxOutputLongSide = 0, maxDecodedPixels = 25_000_000,
  format = OutputFormat.Jpeg(quality = 90), metadata = MetadataPolicy.StripAll)`. The library never upscales.
  `maxDecodedPixels` applies to the decoded working image (after orientation and the working-size reduction) and is
  checked from the image header before any pixel is allocated; if the working image would exceed it, the call
  fails with `ImageTooLarge` instead of decoding smaller. Larger sources are decoded at the reduced size when
  `maxLongSide` is set, up to a fixed source ceiling of 200 MP (ADR 0005 decision 1). Inputs: JPEG, HEIC, PNG; output: JPEG only (ADR 0005
  decision 7). The allowed ranges are in decision 10.
- `OutputTarget` has no default. Common: `OutputTarget.NewFile(path: String, replaceExisting = false)` and
  `OutputTarget.OverwriteSource` (atomic for file-system paths, ADR 0005 decision 2). Android adds two factories
  (static methods for Java callers), because common code has no `Uri` type:
  - `AndroidOutputTargets.newFile(uri: Uri)`: a new, empty document; a non-empty target is refused before writing.
  - `AndroidOutputTargets.overwriteNonAtomic(uri: Uri)`: the explicit opt-in for replacing an existing content URI
    (including the source), which cannot be atomic; its KDoc states that after any error the target must be treated
    as unmasked and not shared (ADR 0005 decision 4).
  `OutputTarget.OverwriteSource` with a `content:` source is refused before any work, with a message that names
  `overwriteNonAtomic`. The factories return a common `OutputTarget.PlatformTarget` (no public constructor), because
  direct subclasses of a sealed class must stay in one source set (ADR 0015 decision 2); the factory names and
  behaviour are fixed here. Which parts are common and which are Android (extension functions in a
  `FaceMaskerAndroid` file class for Java): ADR 0015 decision 3. Overwriting is always an explicit act.
- `plan = null` detects and masks automatically. A non-null plan (from `detectFile` + user edits with `withMasks`)
  is applied instead of detection; its width and height must equal the working size or the call throws
  `FaceMaskException.MaskPlanMismatch` (no silent rescaling). The plan must be bound to the source's decoded pixels
  (ADR 0015 decision 6): a plan without a binding (built with the public constructor) throws `MaskPlanMismatch`
  before the source is opened, and a source whose pixels changed since the plan was made throws
  `FaceMaskException.SourceChanged`; in both cases nothing is written.
- **Masks from the app's own detector, without a model.** `PlanMasker(masking = MaskingConfig())` needs no
  `DetectorModel` and no ONNX Runtime. `planFile(source, options)` decodes like `detectFile`, detects nothing and
  returns an empty, bound plan of the working size (upright, top-left origin); the app adds its masks with
  `withMasks` and calls `planMasker.maskFile(source, target, plan, options)`, which writes them with the
  instance's `MaskingConfig` under the same rules as `FaceMasker.maskFile`. Android adds the `Context`/`Uri`
  forms as extension functions in `FaceMaskerAndroid`. Signatures, thread safety and the order rule for the two
  calls: ADR 0015 decision 6.
- Detection and masking options come from the instance's `FaceMaskerConfig`; the file call adds output,
  size, format and metadata settings. Together they cover the required settings list.
- I/O, atomic overwrite, content URIs, metadata, orientation and ICC rules: ADR 0005.
- `maskJpeg` reads the input stream into memory (no temp file), at most 64 MiB (fixed; ADR 0005 decision 1), and
  writes the output stream once; atomicity of the caller's stream is the caller's. A longer stream throws
  `SourceTooLarge` before decoding and nothing is written; for larger inputs the path or `Uri` form is used, which
  the platform decoder reads from the file or provider; FaceBlur never holds the whole input (ADR 0015 decision 4).

### 5. Results and masks

- `FaceBox(left, top, right, bottom, score)`: final merged boxes in pixels of the image the masks are written on
  (in-memory: the caller's image; file mode: the working image). Landmarks are not exposed
  (not needed for masking; smaller API; can be added later without breaking).
- `Mask(shape, centerX, centerY, radiusX, radiusY, origin)` holds the final geometry (`origin` = `Detected` or
  `Manual`). Factories: `Mask.fromFace(box, masking)` (the expansion of `masking`, by default ×1.6 width and ×1.8
  height (raised from ×1.35 / ×1.55 before 0.1.0), and the fixed centre shift of 0.08 × height), `Mask.manual(left, top, right, bottom, masking)` (same expansion, so a hand-drawn box
  is covered like a detected one), `Mask.exact(shape, centerX, centerY, radiusX, radiusY)` (no expansion).
  The outward feather is a property of `MaskingConfig`, never of the face region.
  For detected faces, the expansion per axis is the larger of the profile's minimum (`minMaskExpandX`/`Y`) and the
  instance's `MaskingConfig`; the `MaskingConfig` carried by a `Detection` is this effective one (ADR 0012 decision 3).
  Manual masks keep the geometry the app passes, under the rules above; an app that wants the same expansion for a
  hand-drawn box passes `detection.masking` to `Mask.manual`.
- `Detection(width, height, faces, timings, hasGainMap, masking)` (`hasGainMap` is always false outside Android
  14+). `masking` is the `MaskingConfig` of the `FaceMasker` that produced the detection.
  `toMaskPlan()` builds the plan with exactly that `MaskingConfig`, so a plan from an instance with non-default
  masking has the same geometry that the instance's own `maskInPlace`/`maskFile` would write, including the part
  masks of merged faces (ADR 0012 decision 3; a plan can hold more masks than there are faces); the overload
  `toMaskPlan(masking)` uses another `MaskingConfig` on purpose. `MaskResult(width, height, faces, masks, timings,
  gainMapRemoved)`. A `Detection` from `detectFile` also carries `outputWidth`, `outputHeight` (equal to `width`,
  `height` in in-memory mode). In a `FileResult`, `width` and `height` are the working size (the size that detection
  ran on and that the coordinates of `faces` and `masks` refer to); it adds `sourceWidth`, `sourceHeight`
  (orientation applied, before any scaling), `outputWidth`, `outputHeight` (so "was it scaled" is
  `output != source`) and `outputBytes`.
- `Timings(decodeMs?, orientScaleMs?, detectMs, passes, maskMs, encodeMs?, totalMs, gcMs?)`: `null` means the mode
  did not perform the step (in-memory mode has no decode/encode; `gcMs` is iOS only). `totalMs` covers the call's
  own work and excludes `gcMs` (the iOS garbage collection after the call, ADR 0004); KDoc says that the wall time a
  caller sees is `totalMs + gcMs` plus wrapper overhead, and performance measurements use the wall clock.
- `Detection`, `MaskResult` and `FileResult` also carry `warnings: List<String>`: non-blocking warning codes computed
  for that call's working size (ADR 0012 decisions 3 and 7); usually empty.
- No public `data class`, no `@Serializable`: results and options are final classes with `equals`/`hashCode`/
  `toString` written out; options have builders for Java. This keeps `copy`/`componentN` out of the ABI and
  removes kotlinx-serialization from the shipped dependencies.

### 6. Re-applying edited masks

The editing flow is: `detect` (read-only) → `detection.toMaskPlan()` → the app shows, removes, adds or moves
masks → `applyMasks(image, plan)` once. `maskInPlace` is the shortcut for apps without an editing UI. A mask that
was already written cannot be removed; adding masks afterwards is allowed (`applyMasks` with only the new masks).
An edited plan is made with `plan.withMasks(editedMasks)`, which keeps the plan's size (and, in file mode, its
binding); the public `MaskPlan` constructor stays for in-memory plans that an app builds itself.
File mode has the same flow on paths and on Android URIs: `detectFile(source)` → `toMaskPlan()` → user edits →
`plan.withMasks(editedMasks)` → `maskFile(source, target, options, plan = editedPlan)`, with the same
`FileOptions`. `maskFile` re-decodes the source and applies the plan without detecting again; a plan whose size
does not match the working size throws `MaskPlanMismatch`. A plan built by `toMaskPlan()` from `detectFile` (or by
`planFile`) is bound to the decoded pixels: `maskFile` throws `SourceChanged` for a source that changed between the
two calls, and `MaskPlanMismatch` for a plan without a binding, before the source is opened (ADR 0015 decision 6).
On `SourceChanged` nothing was written; the app runs `detectFile` again on the current file and, if it shows the
masks to the user for review, shows the new result again before masking.

### 7. Agreement guarantee between the modes

File mode decodes into the same platform pixel container that in-memory mode accepts (Android: mutable
ARGB_8888 software `Bitmap`; iOS: an RGBA buffer wrapped exactly as `wrapRgba` wraps a caller buffer) and then
calls the same internal function as `maskInPlace`. For the same pixels and the same `FaceMaskerConfig`, both
modes therefore return identical `FaceBox` lists (bit-identical floats) and write identical mask pixels on the
working image; a final scale-down to a smaller output (decision 4) happens after that and is outside the
comparison.
The guarantee covers the platform adapters; a custom `PixelImage` implementation uses the common resampler and can
give slightly different boxes, which KDoc states.
`maskInPlace` equals `detect` + `applyMasks` with `detection.toMaskPlan()` except for the error type of the
per-image mask limits: masks from the model give `ModelOutputInvalid` with `TOO_MANY_MASKS`, a caller's plan gives
`InvalidConfiguration("masks")` (ADR 0013 decision 2). The `maskInPlace` KDoc ("error rules as there") names this
exception. In-memory and file mode report the same source of masks the same way.

### 8. Threading, wrappers and cancellation

- Every `FaceMasker` method blocks. On Android and iOS, a call from the main thread throws
  `FaceMaskException.MainThread` before any work (all build types; deterministic and testable).
- One call at a time per instance; for parallel work, one instance per worker. (This is the meaning of "one
  detector instance per thread": no concurrent use, not thread affinity; the wrappers below may run consecutive
  calls on different pool threads.) A cheap atomic guard detects concurrent entry and throws
  `FaceMaskException.ConcurrentUse` instead of corrupting the reused tensor buffer. `close()` never throws. If a
  call is running when `close()` is called, `close()` marks the instance closed and returns at once. The running
  call completes, and the session and its buffers are released when that call returns. Buffers are therefore never
  released under a running call, and later calls throw `Closed`. This is the usual `AutoCloseable` behaviour and
  matches the Swift facade (ADR 0011). KDoc states the rule in these words.
- **Wrappers deferred to 0.2 (project owner, 2026-11-03).** 0.1.0 ships neither `SuspendingFaceMasker` nor
  `AsyncFaceMasker`. Apps call the blocking methods off the main thread (a background dispatcher or executor) and
  cancel through a `CancellationToken`; the Android and KMP guides show this from a coroutine, with coroutine
  cancellation setting the token. Java callers use the blocking API on their own executor; the guide shows a short
  `CompletableFuture.supplyAsync` example whose cancellation sets the `CancellationToken`. The two bullets below are the design for 0.2 and are not part of the 0.1.0 API
  or ABI dump.
- `masker.suspending(dispatcher = Dispatchers.Default)` returns a `SuspendingFaceMasker` with the same method
  names as `suspend` functions; it serialises calls with a `Mutex` and maps coroutine cancellation to the
  `CancellationToken`. When the coroutine is cancelled, the wrapper throws the coroutine's `CancellationException`
  (not `FaceMaskException.Cancelled`), so structured concurrency works. This adds `kotlinx-coroutines-core` as an
  `api` dependency of `commonMain`.
- Java (Android and JVM source sets): two overloads. `masker.async()` creates and owns a single-thread executor;
  `masker.async(executor)` uses the caller's executor, which the caller shuts down. In both, `AsyncFaceMasker`
  queues calls so that only one runs at a time (any executor works) and its methods return `CompletableFuture`.
  `future.cancel(true)` sets the token and the future completes with `CancellationException` (the
  `CompletableFuture` contract). `AsyncFaceMasker` is `AutoCloseable`: `close()` stops accepting calls, waits for
  the running call, and shuts down the executor only if the wrapper created it. It never closes the `FaceMasker`;
  the code that created the `FaceMasker` closes it.
- Swift: `FaceMasker` is an `actor` whose `unownedExecutor` is a private `DispatchSerialQueue`, so the blocking
  work never runs on the cooperative thread pool; methods are `async throws`; `Task` cancellation sets the token
  and the facade throws Swift's `CancellationError` (not `FaceMaskError.cancelled`). A synchronous twin
  `FaceMaskerSync` (final class) serves code already on a background queue and throws `FaceMaskError.cancelled`.
- The blocking Kotlin/Java API throws `FaceMaskException.Cancelled`.
- Cancellation is cooperative. It is checked before detection, between detector passes (one pass takes
  milliseconds to a few tens of milliseconds), during the mask stage's computation (between masks, at least once
  every 65,536 units of work inside a mask, and once more right before the final copy; ADR 0013 decision 3), and in
  file mode after encoding and before anything is written to the target. The mask stage writes the caller's image
  only in its final copy step, and that copy is never interrupted: once it has started, the call copies every mask
  of the plan and, if cancellation was requested meanwhile, then throws `Cancelled`. An in-memory image is
  therefore either untouched (cancelled before the copy) or fully masked (completed, or cancelled during the copy),
  never half-masked; KDoc of `Cancelled` states both cases. Which other errors leave the image untouched is stated
  in decision 9. File mode writes nothing to the target and removes its temporary output on cancellation.
- **Documentation rule (project owner): off the main thread, with cancellation.** Every example and guide that
  masks a photo (KDoc and DocC samples, the integration guide, the README, the demo apps and code samples) runs the
  call off the main thread (a background dispatcher or executor, or the Swift actor) and offers the user a way to
  cancel it (through a `CancellationToken`, set for example from coroutine cancellation, or Swift `Task`
  cancellation), with a visible cancel action in the demos. From 0.2 the examples may use the wrappers. The guide says why: a call within every technical limit can still take long on a phone, for example
  at a very low score threshold or with unusual model output (ADR 0012 decision 6). The documentation never shows a
  blocking call on the main thread, also not for brevity.

### 9. Error model

`public sealed class FaceMaskException : RuntimeException` with subclasses `DecodeFailed`, `EncodeFailed`,
`ImageTooLarge(width, height, limit)`, `SourceTooLarge(limitBytes)`, `NotEnoughMemory`, `UnsupportedImage(reason)`,
`UnsupportedTarget(reason)`,
`IoFailed`, `OutputVerificationFailed`, `OutputValidationFailed(findings)`, `MaskPlanMismatch`, `SourceChanged`,
`ModelIntegrity`,
`ModelRejected(findings)`, `ModelOutputInvalid(findings)`, `RuntimeUnavailable`, `MainThread`, `ConcurrentUse`,
`Closed`, `Cancelled`, `InvalidConfiguration(parameter)`, `MaskWriteFailed`, `Internal`.

- Model errors (ADR 0012): `ModelIntegrity` = the model's SHA-256 differs from the profile's required
  `expectedSha256` (checked before any parsing; the exception carries neither hash, in a property or in its message);
  `ModelRejected(findings)` = the file or the model was refused while loading or creating an instance;
  `ModelOutputInvalid(findings)` = a pass produced output that fails the per-pass checks. Their `findings` follow
  the rules of `OutputValidationFailed.findings` below; the 0.1.0 code sets are listed in ADR 0012. With
  `TOO_MANY_MASKS`, the message also names the mask count and the profile's score threshold and says that the
  threshold is very likely too low for this model; check it with faceblur-eval (ADR 0012 decision 7). With
  `TOO_MANY_NMS_PAIR_CHECKS` (the in-pass NMS of one photo compared more than 240,000,000 pairs of boxes, a final
  limit), the message in the same style names the pair checks reached, the profile's score threshold and the limit,
  with the same advice (ADR 0012 decision 7). `Internal` is reserved for faults of the library itself.

- **Fail closed (project decision).** FaceBlur never returns an image in which a detected or requested face was
  silently left unmasked or only partly masked. Concretely:
  - A caller-supplied box or mask (`Mask.manual`, `Mask.exact`, `MaskPlan`) with a NaN or infinite value, inverted
    edges, or a radius ≤ 0 throws `InvalidConfiguration(parameter)` when it is created; nothing is skipped.
  - Mask coordinates are bounded. The centre and radii of every mask must be finite and within ±4,194,304 px
    (2²²; 32-bit float pixel arithmetic stays exact to 0.5 px there). This applies to `Mask.exact`,
    `Mask.manual` and `Mask.fromFace`, including after their expansion. A value beyond the bound throws
    `InvalidConfiguration(parameter)` and nothing is written. The masking core refuses the same values before any
    call into the caller's image, so a huge but finite mask is never silently left unwritten. Caller-supplied masks
    are rejected by their factories with `InvalidConfiguration`; the core's own refusal is a second layer and
    surfaces as `Internal`. The bound sits next to the image-size limits of this decision.
  - A model output with a non-finite score or coordinate, a score outside [0, 1] (± 1 × 10⁻⁶), a box whose edges are
    not strictly ordered before clipping (right ≤ left or bottom ≤ top, zero size included), or a box beyond the
    technical bounds relative to the tile throws `ModelOutputInvalid` before any pixel is written; such a box is never
    dropped silently. (A non-finite score must not pass the threshold comparison by accident: the check is "finite
    and ≥ threshold".) Within the bounds, a box that lies partly outside the image is normal and is clipped to the
    image and masked; only a box that becomes empty by clipping is dropped, because it covers no image pixel
    (ADR 0012 decision 5).
  - Areas, block sizes and offsets are computed in 64-bit arithmetic. Two size limits apply before any call into
    the caller's `PixelImage`; each throws `ImageTooLarge`:
    - **Per side:** an image wider or taller than 65,535 px. This is JPEG's own maximum per side, and it applies in
      in-memory mode and file mode alike. In file mode it is checked on the working size computed from the source
      header, before anything is decoded (ADR 0005 decision 1 step 2); the decoded-pixel limit below still applies.
    - **Pixel count:** an image whose `width × height` exceeds 2,147,483,647 (2³¹ − 1, the largest block an
      `IntArray` can hold; a square image above 46,340 × 46,340).
    Images within both limits never overflow a block size or offset. In file mode, the decoded-pixel limit
    `maxDecodedPixels` (13-100 MP, decision 10) applies as well and is usually the tighter one.
  - **Mask limits** (ADR 0013 decision 2): at most 65,536 masks per image and a summed working area of at most
    32 × max(image pixels, 1,048,576), both final; generous technical limits, and exceeding one fails safely. A caller's plan above either limit throws
    `InvalidConfiguration("masks")` (`MaskPlan` construction for the count, `applyMasks` and `maskFile` with a plan
    for the work); masks from the model throw `ModelOutputInvalid` with `TOO_MANY_MASKS` (a code of the 0.1.0 set in
    ADR 0012). Both are checked before the mask stage reads or writes any pixel.
  - **Untouched on failure** (ADR 0013 decision 4). The mask stage does every check, every allocation and all fill
    computation before its final copy, which is the only step that writes. If `applyMasks` or `maskInPlace`
    throws, the image is untouched, with two exceptions: `MaskWriteFailed` (the caller's image failed while the
    finished masks were copied into it; discard it) and `Cancelled` requested during that copy (reported after the
    copy, with the image fully masked). Any error that is not a `FaceMaskException` (for example an
    `OutOfMemoryError` on Android) means: discard the image. KDoc contract text: "If this call throws a
    `FaceMaskException`, the image is untouched, with two exceptions. `MaskWriteFailed`: your image failed while the
    finished masks were copied into it; discard it. `Cancelled` requested during that final copy: it is reported
    after the copy, and the image is fully masked. If the call ends with any other error (for example an
    `OutOfMemoryError` on Android), discard the image."
  - **Working area** (ADR 0013 decision 6). The mask stage computes the size of its whole working area and allocates
    all of it before the first write; the copy step and FaceBlur's own adapters allocate nothing. If it does not fit
    in the memory available now, the call throws `NotEnoughMemory` and the image is untouched. The working area is
    sized per call from the plan and needed per call, not per instance: N calls running in parallel need up to N
    times it. The worst case is 4.3 bytes per image pixel plus 32 MiB (about 141 MB at 25 MP); the KDoc of
    `maskInPlace`, `applyMasks` and their platform overloads states it, with the recommended
    number of parallel calls for large photos on phones.
    Every array the stage passes to `readPixels` or `writePixels` has exactly `w × h` elements (one reserved array
    per run length), so the `PixelImage` contract below is unchanged.
  - After writing, the library reads back every block it wrote to a caller's `MutablePixelImage` and compares it with
    what it wrote; a difference (an implementation that drops writes) throws `MaskWriteFailed`. The image must then
    be discarded (KDoc).
  - `MutablePixelImage` contract (KDoc): after `writePixels`, `readPixels` of the same pixels returns exactly the
    written values; write-only, lossy or deferred implementations are not supported. The one permitted difference is
    a declared storage form: the platform adapters for premultiplied 8-bit buffers (ADR 0004) declare it, and the
    comparison then expects the written value after one round trip through that form (exact for alpha 255, so exact
    in every opaque core; soft-edge pixels with alpha below 255 compare after the round trip). Caller
    implementations cannot declare a storage form in 0.x and must round-trip exactly.
- `ImageTooLarge` is also thrown when the working image of file mode (decision 4) would exceed `maxDecodedPixels`;
  the library never decodes below the detection floor to fit the limit (decision 10). Its message names
  `maxDecodedPixels` and `maxLongSide`.
- `SourceTooLarge(limitBytes: Long)`: the stream given to `maskJpeg` is longer than the fixed stream-source cap
  (`limitBytes` = 67,108,864 in 0.1.0); thrown before decoding, nothing written, the output stream untouched. Its
  message names the limit and tells the developer to use the file (path) or `Uri` form for larger inputs; it names
  no path. It is not pixel-shaped, so `ImageTooLarge` is not reused. Swift: `FaceMaskError.sourceTooLarge(limitBytes:)`
  (ADR 0011 decision 4; the 0.1.0 Swift facade has no stream or `Data` file-mode input, so no Swift call reaches it
  yet, but it is mapped like every case).

- `MaskPlanMismatch`: the plan does not fit the image: its size is not the image's (in file mode: the working
  size), or, in file mode only, it has no binding to the decoded pixels (ADR 0015 decision 6; thrown before the
  source is opened, with a message that names `detectFile`, `planFile` and `withMasks`). Nothing was written; the
  fix is in the code that builds the plan.
- `SourceChanged` (file mode only): the source's decoded pixels differ from those the plan was made for; nothing
  was masked or written and the target is untouched. No properties; fixed message without a path (ADR 0015
  decision 6). KDoc and guide reaction: run `detectFile` again on the current file (with the app's own detector:
  `planFile` and the detector again) and, if the app shows the masks to the user for review, show the new result
  again before masking. It is a separate subclass because the reaction differs from `MaskPlanMismatch`, and adding
  it after 0.1.0 would break exhaustive `when`/`switch` in apps. Swift: `FaceMaskError.sourceChanged`.

- `OutputValidationFailed`: the encoder output failed the rebuild step's parser (ADR 0005 decision 1 step 7) or
  the output bytes failed the runtime validator (ADR 0005 decision 6); nothing was written. It has one public property, `findings: List<String>`: the finding codes, each at most once, in the order
  they were found, never empty. The message is "Output validation failed: " followed by the same codes joined by
  commas; neither carries image data, metadata values, offsets or paths. The codes are a public contract:
  - 0.1.0 set: `NOT_JPEG`, `STRUCTURE`, `TRUNCATED`, `MISSING_EOI`, `TRAILING_BYTES`, `UNEXPECTED_MARKER`,
    `FORBIDDEN_SEGMENT` (any APPn or COM the output may not carry, including any APP0 other than the fixed JFIF
    segment, a second APP0 and any APP2/ICC), `SEGMENT_ORDER` (a missing APP0, or the APP0 or APP1 not in its
    required position), `MISSING_EXIF`, `DUPLICATE_EXIF`, `EXIF_TOO_LARGE`, `EXIF_INVALID`,
    `EXIF_TAG_NOT_ALLOWED`, `ORIENTATION_NOT_1`, `FRAME`, `NO_SCAN`.
  - A code is never renamed, removed or given another meaning without a CHANGELOG "Breaking" entry; new codes may
    be added in any release. Callers treat every code, known or not, as a failure and must not match the list exhaustively.
  - The codes are for logs and support; they never change what the library does: any finding means nothing is
    written.
  - Swift: `FaceMaskError.outputValidationFailed(findings: [String])` carries the same list (ADR 0011).
  `OutputVerificationFailed`: the read-back of a content-URI target differed from the output bytes (ADR 0005
  decision 4).
- **Configuration errors.** Every configuration type (`FaceMaskerConfig`, `MaskingConfig`, `FileOptions`,
  `OutputFormat.Jpeg`, `MetadataPolicy.KeepAllowlist`, `Mask` factories, `MaskPlan`) validates its values when it is
  constructed or built and throws `FaceMaskException.InvalidConfiguration(parameter)`, where `parameter` is the
  public property name (for example `"expandX"`). The message carries only the name and the allowed range, never
  user data. Their constructors, factories and builder `build()` methods carry `@Throws` like every other public
  function.
- **Swift.** The facade's configuration structs are plain Swift values. The facade validates every value in Swift,
  with the same ranges, before it calls Kotlin, and throws `FaceMaskError.invalidConfiguration(parameter:)`. The
  Kotlin check still runs as a second layer: thanks to `@Throws` it arrives as an `NSError` that the facade maps to
  the same case with the parameter name. Neither path can terminate the app, and neither loses the name.

- Unchecked in Java, but every public function carries `@Throws(FaceMaskException::class)` so Java signatures
  declare it and Kotlin/Native converts it to `NSError`.
- Wrapping rule, per platform:
  - iOS (Kotlin/Native → Objective-C/Swift boundary): every public entry point wraps any `Throwable` that is not a
    `FaceMaskException` into `FaceMaskException.Internal`, because an undeclared exception crossing that boundary
    terminates the app.
  - Android and JVM: the `java.lang.VirtualMachineError` family (`OutOfMemoryError`, `StackOverflowError`,
    `InternalError`, `UnknownError`) passes unchanged, never wrapped or caught. The one exception is the mask stage's
    working-area allocation (ADR 0013 decision 6), as a last-resort backstop behind the remaining-heap check: an
    `OutOfMemoryError` raised inside that function, which only allocates and calls no caller code, becomes
    `NotEnoughMemory` with the error as its cause, before any pixel is written. Only `OutOfMemoryError` is caught
    there; every other throwable passes unchanged. Every other unexpected
    `Throwable`, not only `Exception`s, is wrapped: `IoFailed` while the model file is read, `RuntimeUnavailable`
    during runtime setup, `Internal` otherwise. Common code cannot name these classes, so it matches the four standard
    classes by name; a custom `VirtualMachineError` subclass is therefore wrapped (the safe direction). The coroutine
    wrapper's `CancellationException` is never wrapped.
  - The same rule in common code means that on Kotlin/Native no name matches, so everything is wrapped, which is
    the iOS rule above. The one exception is again the working-area allocation: there `kotlin.OutOfMemoryError`
    (exact name) becomes `NotEnoughMemory` (ADR 0013 decision 6).
- `ImageTooLarge` comes only from the library's size checks. `NotEnoughMemory` means that the call needs more memory
  than is available now and that nothing was changed. It comes from the iOS free-memory headroom check before
  decoding (ADR 0005), from the reservation of the stream form's source buffer (ADR 0015 decision 4) and from the
  mask stage's working-area reservation on every platform, always from a check
  before the allocation, with a process-wide count of the bytes reserved by other running calls (ADR 0013 decision
  6). On the JVM and Android the check uses the remaining heap (`Runtime.maxMemory()` minus the heap in use minus
  that count, after one garbage collection if it does not fit at first), because an `OutOfMemoryError` on Android
  can hit any thread of the app; the narrow catch above is only a backstop. On iOS the check uses the memory the
  system reports as available, because there a failed allocation cannot be relied on to give a catchable error: the
  system usually ends an app whose memory footprint crosses its limit before any allocation fails. On an iOS device
  a reported figure of 0 is refused. Running out of memory anywhere else is avoided by the size caps, not caught and
  relabelled. KDoc of `NotEnoughMemory`: "The call needed more memory than was available, and the image was not
  masked; it is untouched. Do not publish, upload or share the image or the original instead, as if it were masked.
  Retry later, with less work running in parallel, or with a smaller image."
- Messages are fixed English text plus dimensions or counts; never pixel data, file names, paths or face
  coordinates.
- Swift: `FaceMaskError` mirrors the subclasses one-to-one (hand-written mapping in the facade, one test per case).
- `Result`-returning twins are not offered.

### 10. Configuration surface, defaults and protection-weakening settings

**Product principle (project decision; applies to this and every later version).** Developers can only choose
privacy-reliable options. FaceBlur covers faces with opaque masks that never read face pixels. No blur, pixelation or
any other reversible, transparent or partial style is offered, not even as an opt-in; cosmetic effects stay out of
the library. Every setting that could weaken protection is either not exposed or limited to a range that the project
owner approves. Adding a setting, adding a `MaskStyle` entry or widening a range needs the owner's approval and a
revision of this ADR. Narrowing a range after a release would break callers, so the first published ranges start
strict.

**Model-specific values are the developer's responsibility (project owner's decision).** The detection model, its
`DetectorProfile` and its score threshold are chosen by the app developer, who is responsible for them. FaceBlur
validates them only technically: a finite probability threshold, values in their technical ranges, a well-formed
file, a declared format that matches the model, and well-formed output (ADR 0012). It does not judge whether a model,
profile or threshold suits the app's photos, and it offers tools to check a setup (`ModelInfo`, `checkProfile`). The
rule above applies to every setting of the library itself.

**Complete public configuration** (Kotlin; the Java builders and the Swift structs have the same fields, defaults and
ranges, except that Swift's `MaskColor` has no alpha component and is opaque by construction, ADR 0011; approved by
the owner):

```kotlin
DetectorProfile(format, inputWidth, inputHeight, scoreThreshold /* required */, expectedSha256 /* required */,
                minMaskExpandX = 1.6, minMaskExpandY = 1.8)
DetectorModel.load(source, profile, maxModelBytes = 52_428_800)
FaceMaskerConfig(masking = MaskingConfig(), onnxThreads = 0)
MaskingConfig(style = MaskStyle.GRADIENT_OVAL, shape = MaskShape.ELLIPSE, expandX = 1.6, expandY = 1.8,
              feather = 0.10, color = 0xFF7F7F7F)
FileOptions(maxLongSide = 3500, maxOutputLongSide = 0, maxDecodedPixels = 25_000_000,
            format = OutputFormat.Jpeg(quality = 90), metadata = MetadataPolicy.StripAll)
```

- `MaskStyle` has exactly three entries. All are opaque inside the mask, and none reads a pixel inside any mask:
  - `GRADIENT_OVAL` (default, by the owner's decision): a solid centre in `color` that changes, as a colour
    gradient, into the colours sampled just outside the mask;
  - `SMOOTH_FILL`: fills the mask smoothly from the pixels just outside it (the style of the benchmark's reference
    runs);
  - `SOLID`: one colour.
  The fill style does not change detection. The accuracy and protection gates nevertheless run with the default
  style, `GRADIENT_OVAL`.
- `MaskShape`: `ELLIPSE` (default) or `RECTANGLE`; a rectangle with the same radii contains the ellipse.
- `color` is used by `SOLID` and by the centre of `GRADIENT_OVAL`; it must be fully opaque.
- Internal constants (not configurable; the configuration under which accuracy was measured with the internal
  reference model; the same for every model, ADR 0012 decision 3):
  - detection floor 3,500 px: the lower bound of `maxLongSide`, so file mode detects on a working image whose long
    side is at least 3,500 px, or the source's long side if that is smaller, whatever output size is requested
    (decision 4);
  - tile = the model's input size in source pixels, with overlap 0.25 on each axis, and every image tiled (no size
    threshold);
  - one global pass;
  - cross-pass merge at IoU 0.4 with containment 0.7, always; in-pass NMS at 0.3, skipped only when the profile
    declares NMS inside the model graph;
  - no truncation: every candidate above the threshold goes into NMS. The output caps of ADR 0012 decision 6 are
    generous technical limits; exceeding one is an error, never a dropped candidate;
  - mask centre shifted up by 0.08 × the box height;
  - `GRADIENT_OVAL` inner solid fraction 0.45 of the radius and sampling-ring width 0.15 of the radius (at least
    2 px), with the centre colour always `color`; the core's other centre-colour source (mean of the surrounding
    ring) is not reachable from the public API, because rule 5 below assumes the centre is `color`.
  The earlier `AdvancedDetection` type and the benchmark's per-detector score map are dropped.
- Defaults follow the extraction plan's recommended values, except the default style (owner's decision). A test
  asserts every default and every bound (docs/code drift fails CI).

#### Mask opacity invariant (every style; the default `GRADIENT_OVAL` in particular)

For a mask with centre (cx, cy), radii (rx, ry) and shape S, let r(x, y) be the normalised radius of the pixel
centre (x + 0.5, y + 0.5): for `ELLIPSE` r = sqrt(((x + 0.5 − cx)/rx)² + ((y + 0.5 − cy)/ry)²); for `RECTANGLE`
r = max(|x + 0.5 − cx|/rx, |y + 0.5 − cy|/ry). For a detected face box of width w and height h, the default
geometry is rx = 0.675 w, ry = 0.775 h, cx = box centre x, cy = box centre y − 0.08 h. Let φ = max(`feather`,
1 / min(rx, ry)) be the width of the soft edge in units of r (the configured feather, but at least one pixel, as in
the measured renderer). Then, for every mask and every pixel:

**Pixel-centre convention (one convention for the library, its tests and every oracle).**
- Box edges, mask centres and radii are continuous pixel coordinates. The origin is the top-left corner of pixel
  (0, 0); x grows to the right and y downwards.
- Pixel (x, y) covers [x, x + 1) × [y, y + 1) and is represented by its centre (x + 0.5, y + 0.5). "Inside the core"
  means r ≤ 1 at that centre.
- The library evaluates r in 32-bit floating point exactly as written: the difference to the centre is divided by
  the radius, then squared and summed, then the square root is taken.
- An independent oracle that computes in higher precision may disagree only for pixels whose r lies within
  ε = 1 × 10⁻⁴ of a boundary (r = 1 or r = 1 + φ). Such pixels are not judged by the oracle. Every other pixel must
  agree exactly. ε is fixed now and is never widened after a run.

1. **Opaque core.** If r ≤ 1, the output pixel has alpha 255 (0xFF), and its colour depends only on `color` and on
   pixels outside every mask. It does not depend on any input pixel (colour or alpha) with r ≤ 1 of any mask. The
   colour may vary across the mask (the `GRADIENT_OVAL` and `SMOOTH_FILL` gradients); the opacity never does.
2. **Outward soft edge.** If 1 < r < 1 + φ (the soft edge), the output is a blend of the style's opaque fill and
   the input pixel, for colour and alpha with the same weight w. With t = (r − 1) / φ, the weight is
   w = 1 − (3t² − 2t³) (the measured renderer's smoothstep). It is below 1 everywhere beyond r = 1 and falls
   monotonically to 0 at the outer edge of the band, so the output alpha is never lower than the input alpha there.
   The renderer quantises w to steps of 1/256. Where w ≥ 255.5/256 (t ≤ about 0.0256), the step is 1, so such a
   pixel is written exactly like the core. That ring is at most 0.0256 × φ × max(rx, ry) px wide: with the default
   feather 0.10 about 0.26 % of the larger radius (about 1 px at a 400 px radius, about 7.7 px at a 3,000 px
   radius). Where the input alpha is already high, rounding can likewise give alpha 255 further out (for input
   alpha 254, wherever w ≥ about 0.5: 127.5/256 with the renderer's rounding).
   This only covers more, never less.
3. **Untouched outside.** If r ≥ 1 + φ for every mask (beyond every soft edge), the output pixel is bit-identical
   to the input pixel.
4. **No transparency anywhere.** No style writes a pixel whose alpha is lower than the input alpha at that position,
   and no style uses transparency to show the input through a mask. Transparency exists only in the input's own
   alpha outside the opaque core.
5. **`GRADIENT_OVAL` colours.** Pixels with r ≤ 0.45 (the inner solid fraction) have exactly `color`. For
   0.45 < r ≤ 1 the colour changes, as a colour gradient, from `color` towards the colours sampled in a ring just
   outside the mask (outside every mask); alpha stays 255 throughout (rule 1).

Where masks overlap, rule 1 of any mask takes precedence over rules 2 and 3 of another. Where two soft edges overlap,
the blends are applied one after the other; rules 2 and 4 still hold (the alpha never decreases, and the result
stays between the fills and the input). The soft edge equals the
configured `feather` (for example r up to 1.10 by default) only for masks whose smaller radius is at least
1 / `feather` pixels (10 px by default); smaller masks get a one-pixel soft edge.

**Opacity rule (the project owner's wording).** "Every pixel inside the ellipse is fully opaque. Outside it, only
the outward feather may change pixels; its weight never rises outward and it stays within a band whose width is
fixed in pixels before the tests run." Rules 1-5 implement it:
- **Inside the ellipse** {r ≤ 1}: every pixel is fully opaque and holds no face information (rules 1 and 5). Any
  gradient is a colour gradient, never transparency.
- **Outside it:** only the outward soft edge changes pixels, as a blend whose weight never rises outward (rules 2
  and 3).
- **Band width in pixels**, fixed now, before the tests run:
  - The band is the set of pixels with 1 < r < 1 + φ, where φ = max(`feather`, 1 / min(rx, ry)).
  - Measured outward from the ellipse, its width is at most W = φ × max(rx, ry) pixels:
    max(`feather` × max(rx, ry), max(rx, ry) / min(rx, ry)) px. Along the minor axis it is
    max(`feather` × min(rx, ry), 1) px.
  - Example with the default feather 0.10 and a 600 × 700 face box (rx = 405, ry = 542.5): at most 40.5 px
    horizontally and 54.25 px vertically.
  - **The band width (this formula and the approved `feather` range of 0 to 0.5) is not widened without the user's
    approval.**
- **An extra ring of full cover from rounding is acceptable**, because it only adds protection. 8-bit rounding of a
  weight just below 1 can make pixels just beyond the ellipse fully covered: the inner 2.56 % of the band, at most
  0.0256 × φ × max(rx, ry) px (rule 2). An input that was already almost opaque can do the same further out.
- **Face-box corners.** The corners of a detected face box lie at r ≈ 1.05, in the soft edge (at most about 0.5 % of
  the box pixels with the default geometry). This is the measured configuration and is kept (owner's decision).

Tests:
- (a) A differential test runs for every `MaskStyle` entry and for both shapes. Two inputs that differ only at
  pixels with r ≤ 1 (random colour and random alpha, including alpha 0) give bit-identical output everywhere.
- (b) For every pixel with r ≤ 1, the output alpha is 255, also for inputs with alpha 0 in the mask.
- (c) In the soft edge, the output alpha is ≥ the input alpha. A pixel with r ≥ 1 + φ for every mask is
  bit-identical to the input.
- (c2) Weight check, on an input whose alpha is 0 around the mask, so that the output alpha equals 255 × w after
  rounding. These bounds are fixed now and never widened after a run:
  - the output alpha is 255 for r ≤ 1;
  - along every ray from the centre it never rises outward by more than 1 level (a 2-level rise fails);
  - it is 255 beyond r = 1 only where t ≤ 0.05;
  - it is 0 for r ≥ 1 + φ;
  - the largest distance in pixels of any changed pixel beyond the ellipse is reported and is at most W.
  The self-tests include a large mask (smaller semi-axis ≥ 400 px) with input alpha 0 and 254, and the check runs
  once against the core's real output before the masking stories are accepted. The oracle's geometric "no opaque
  pixel beyond the ellipse" check is replaced by (b), (c) and (c2): (b) is the opaque core, (c) is "only the
  outward soft edge may change pixels, never inward", and (c2) is the bounded weight.
- (d) `Mask.fromFace` with the defaults yields exactly the radii and centre above.
- (e) File-mode differential pair with one fixed mask plan: lossless PNG inputs A and B that differ only at pixels
  with r ≤ 1 of the plan's masks. `detectFile(A)` gives the plan; `maskFile(A, plan)` and `maskFile(B, plan)` run
  with the same options (once at the working size, once with `maxOutputLongSide = 1000`). The plan from
  `detectFile(A)` is bound to A's pixels, so `maskFile(B, plan)` throws `SourceChanged` (asserted, nothing written);
  B is therefore masked by the same `FaceMasker` with `PlanMasker().planFile(B).withMasks(plan.masks)`, the same
  masks bound to B's pixels through the public API (ADR 0015 decision 6). The two output files are byte-identical, and the independent pixel oracle finds the decoded images identical everywhere. The oracle is a
  decode-and-compare tool in `verification/` that decodes both JPEGs with a decoder independent of the library (for
  example the JDK's ImageIO) and compares every pixel. It is a separate story owned by qa-automation and reviewed by
  security; the metadata and structure checker does not compare pixels and is not used for this proof.
- (f) `GRADIENT_OVAL` with an input whose pixels outside the masks are uniform in one colour C: every pixel with
  r ≤ 0.45 equals `color`, and every pixel with r ≤ 1 has alpha 255 and each colour channel between the
  corresponding channels of `color` and C (inclusive, ±1 for rounding). Tests (a)-(c) and (e) also run with
  `GRADIENT_OVAL`, and the pixel oracle repeats (b) and (f) on decoded file-mode outputs of opaque inputs.
- Negative controls (test-only styles): one that keeps the input alpha inside the core fails (b); one that reads one
  pixel inside the core fails (a) and (e); one that lowers the alpha in the soft edge fails (c); one whose centre is
  `color` ± 2 fails (f).

#### Protection-weakening settings

The table lists every public parameter of the configuration and option types and every setting of the extracted
code that was considered, how it could weaken protection, and how the API handles it. The owner approved the table
with the additions written into it (detection floor, score threshold, default style, pixel-limit rule); the model
rows follow the owner's later decision that the model, its profile and its threshold are the developer's
responsibility (ADR 0012). The evidence column gives the technical rationale for each value; the project's own
evaluation results are not published in this repository. Values that the benchmark needs for like-for-like runs outside these ranges are set only
through an internal test seam that is reachable from the library's own tests and absent from the ABI dump.
In this table, "opt-in" means that the caller must ask for the behaviour explicitly (pass a plan, an allowlist or the
named target factory); whether a Kotlin `@RequiresOptIn` marker is added as well is a separate decision.

| Setting | How it could weaken protection | Handling | Default | Allowed range | Status | Evidence |
|---|---|---|---|---|---|---|
| Model file and `DetectorProfile` format fields (format, output names and columns, row layout, box format, coordinate space, colour order, tensor layout, pixel scaling, `nmsInGraph`) | a wrong model or a wrongly declared profile can find no faces without any error | exposed, required; the developer's responsibility | none | well-formed values; the declared format must match the model's inputs and outputs (ADR 0012) | approved by the owner (developer responsibility) | residual risk documented plainly; `checkProfile`, the demos' check step and the evaluation tool help developers check their setup. A model that filters scores inside its graph loses faces below that cut-off, which FaceBlur cannot see |
| `DetectorProfile.scoreThreshold` | a higher threshold masks fewer faces | exposed, required; the developer's responsibility | none (required) | finite, 0 < t < 1 | approved by the owner (any probability) | depends on the model. A higher threshold drops the lower-scored detections, which are typically small, turned, blurred or partly hidden faces, so they stay unmasked; the guide tells developers to choose the value with faceblur-eval on their own photos. A lower threshold never removes or shrinks a mask when the call succeeds (union property, ADR 0012 decision 3, tested); there is no candidate cap, so a low threshold is never refused for its candidate count. Next to this guidance the documentation names the mask limit: a threshold far below the model's useful range can produce more than 65,536 masks for one photo; the call then fails safely with `ModelOutputInvalid` and `TOO_MANY_MASKS`, whose message gives the mask count and the threshold and says that the threshold is very likely too low for this model (check it with faceblur-eval). In the same way the documentation names the in-pass NMS work bound: such a threshold can make the duplicate removal compare more than 240,000,000 pairs of boxes in one photo; the call then fails safely with `ModelOutputInvalid` and `TOO_MANY_NMS_PAIR_CHECKS`, whose message gives the pair checks reached, the threshold and the same advice |
| `DetectorProfile.inputWidth`, `inputHeight` | smaller inputs mean smaller tiles: a face larger than about a quarter of the tile side may not lie whole in any tile, and the one global pass shrinks faces by working size / input size, so a small input can miss mid-size faces (by geometry, below a smaller input side of about 384 px at a 3,500 px working size, ADR 0012 decision 3); more passes for small inputs | exposed, must match the model; the developer's responsibility | none | 128 to 1,280 px per side (technical limit, not a statement that small inputs are suitable); a pass cap applies | approved: the owner keeps the 128 px minimum; below the gap side (384 px at the default 3,500 px working size, derived from the tile geometry for a detector that needs faces of about 10.5 px, the lower end of the usual 10-16 px, so an estimate of the lower bound; growing with the square root of the working size, about 580 px at 8,000 px) the non-blocking warning `SMALL_INPUT` is reported in `ModelInfo.warnings` (for 3,500 px) and in the warnings of each result (for that call's working size) ("medium-size faces may be missed between the tile passes and the whole-photo pass; measure with faceblur-eval"), which the demos' check step shows | ADR 0012 decision 3; the guide and the evaluation tool show the effect; passes at intermediate scales are a later-version candidate |
| `DetectorProfile.minMaskExpandX`, `minMaskExpandY` | smaller masks | exposed, only larger | 1.6 / 1.8 (raised from 1.35 / 1.55 before 0.1.0) | default to 3.0; the effective expansion is the larger of this and `MaskingConfig` | approved | same floor as `expandX`, `expandY` |
| `DetectorProfile.expectedSha256` | without an expected hash any file at the model's location would load, including a swapped or corrupted one | exposed, required | none (required) | exactly 64 hexadecimal digits, either case (stored in lower case) | approved by the owner: required for every model | ADR 0012 decisions 1, 4 and 10. The hash is checked before any parsing. It is only as trustworthy as its source: a hash the app computes from the file it is about to load protects against nothing. It covers the model bytes, not the other profile values |
| `DetectorModel.load(maxModelBytes)` | none (memory limit) | exposed | 50 MiB | 1 to 2,147,483,639 bytes | approved (default, adjustable) | creation needs about 3-4 × the model size (ESTIMATE) |
| File mode `maxLongSide` (working size for detection and masking) | detecting on a smaller image shrinks every face | exposed, not below the detection floor | 3,500 px | 0 (source size), or 3,500 px and more | approved. A lower floor needs a measurement and a new approval | design rule carried over from the benchmark app: shrinking faces by more than about 1.4× loses small faces. 3,500 px keeps that factor at or below 1.4 for sources up to 4,900 px on the long side (about 18 MP at 4:3); larger sources are shrunk more, and an app that needs their full size raises `maxLongSide` or sets 0; smaller working sizes are not offered |
| Tile side | larger tiles shrink faces before the model, and small faces are lost | not exposed | = the model's input size | fixed rule | approved | tiles are never scaled; the global pass is. The configuration is validated with 1:1 tiles at the model's input size only. Design rule from the benchmark app: shrinking faces by more than about 1.4× loses small faces |
| Tile overlap | less overlap cuts faces at tile borders in every pass | not exposed | 0.25 | fixed | approved | measured configuration |
| Tiling threshold, tiling off | images below the threshold get only the global pass, which shrinks small faces | not exposed | 0 (every image tiled) | fixed | approved | the benchmark's earlier threshold (1,600 px) was replaced by "tile every image" for the measured configuration |
| Merge and NMS thresholds | lower thresholds merge neighbouring faces into one box | not exposed | 0.4 / 0.7 / 0.3 | fixed; the in-pass 0.3 is skipped only for `nmsInGraph`; no truncation | approved | measured configuration; reference test (ADR 0009) |
| Mask size `expandX`, `expandY` | smaller masks leave forehead, chin, ears or hair uncovered | exposed, only larger | 1.6 / 1.8 (raised from 1.35 / 1.55 before 0.1.0) | default to 3.0 | approved | a detector's box can sit off the face or be smaller than the head with its hair and ears, so a smaller mask can leave parts of a detected face visible. Extraction guidance: never shrink the mask to save image content. With the same centre a larger ellipse contains the smaller one |
| Mask position `shiftUp` | moves the mask off the face | not exposed | 0.08 | fixed | approved | measured configuration |
| `feather` | none: it blends only outside the opaque core (invariant above) | exposed | 0.10 | 0 to 0.5 (at least 1 px is always applied) | approved | opacity invariant, tests (a)-(c) |
| `style` | a transparent, reversible or partial style would leave face information | exposed, opaque styles only | `GRADIENT_OVAL` | `GRADIENT_OVAL`, `SMOOTH_FILL`, `SOLID`; never any other | approved (default changed by the owner) | opacity invariant, tests (a)-(f), for every `MaskStyle` entry |
| `shape` | none: the rectangle contains the ellipse | exposed | `ELLIPSE` | `ELLIPSE`, `RECTANGLE` | approved | geometry |
| `color` | a non-opaque colour would suggest that transparency is possible | exposed, opaque only | `0xFF7F7F7F` | any colour with alpha byte 0xFF | approved | the renderer writes opaque pixels; the rule makes the API say the same |
| File mode `maxOutputLongSide` (smaller output) | none: it scales only the masked image, after detection and masking at the working size | exposed | 0 (output = working size) | 0, or 64 px and more (ADR 0005 decision 1); a value at or above the working size means no scaling (never upscales) | approved (the owner's addition: the floor applies to detection, not to the output) | masks are opaque at any scale; edge pixels mix the mask only with pixels outside it |
| `maxDecodedPixels` (limit on the working image); 200 MP source ceiling | none: if the working image would exceed the limit, the call fails with `ImageTooLarge` and writes nothing; it never detects on a smaller image to fit | limit exposed, ceiling fixed | 25 MP / 200 MP | limit 13 MP to 100 MP (above the 12.25 MP of a 3,500 × 3,500 working image) | approved | memory bound. Tests that need large photos at full size raise the limit in their own configuration |
| Stream-source cap (`maskJpeg`) | none: a longer stream fails with `SourceTooLarge` and writes nothing; it is never truncated or partly decoded | not exposed, fixed | 64 MiB | fixed in 0.1.0 | approved by the owner | memory bound for the only form FaceBlur buffers; path and `Uri` forms are not capped by it |
| `onnxThreads` | none expected (no change to the model or the thresholds) | exposed | 0 (automatic) | 0 to 4 | approved (narrowed from 0 to 8, matching the measured XNNPACK pool of up to 4) | ASSUMPTION, checked by running the reference test with 1 thread and with the automatic setting |
| JPEG `quality` | none: no face pixel remains inside a mask at any quality | exposed | 90 | 1 to 100 | approved | — |
| Output format | none (JPEG rebuilt from pixels) | exposed, JPEG only | JPEG | JPEG | decided | ADR 0005 decision 7 |
| `OutputTarget.NewFile(replaceExisting)`, `OverwriteSource` on file-system paths | none: the replace is atomic and the target is unchanged on failure | exposed | `replaceExisting = false` | `true`, `false` | decided | ADR 0005 decision 2 |
| Gain map (Android) | a kept gain map holds the unmasked face | not exposed: always removed | removed | — | decided | ADR 0003 |
| Metadata `KeepAllowlist(gps, dateTime, camera)` | does not affect masks; can keep location, time and camera fields | approved opt-in | off (`StripAll`) | closed field list; GPS exact or not at all | approved opt-in; risk documented in KDoc and the integration guide | ADR 0005 decision 3 |
| `AndroidOutputTargets.overwriteNonAtomic` | after an error the target may be empty, damaged or hold original bytes | approved opt-in | off (not used) | — | approved opt-in; risk documented in KDoc and the integration guide | ADR 0005 decision 4 |
| Caller-edited mask lists (`MaskPlan`, `withMasks`, `Mask.manual`, `Mask.exact`), and all masks from the app's own detector (`PlanMasker`, which detects nothing: every face outside the app's boxes stays visible and `FileResult.faces` is empty) | an app or its user can remove, move or shrink masks, or give boxes in the wrong frame | approved opt-in | off (automatic masks unless a plan is passed) | finite coordinates, ordered edges, radii > 0, plan size equal to the image size; file mode: a plan bound to the decoded pixels, from `detectFile` or `planFile` (ADR 0015 decision 6) | approved opt-in; risk documented in KDoc and the integration guide | users fix missed and false masks by hand; apps with their own detector need no model; every mask follows the opacity invariant |

Outside the settings: an app that uses `detect` and draws its own effect from the boxes, encodes an image before
`maskInPlace` has returned, or scales a photo below the detection floor before calling FaceBlur in in-memory mode
(decision 3) is outside the library's guarantee. The KDoc of `detect` and `maskInPlace` says so.

### 11. Variants and versioning

- Published variants: Android and the two iOS targets. The `jvm` target is built and tested (reference test,
  tools) but **not published**: the JVM ONNX Runtime jar contains a telemetry client that only an environment
  variable set before process start can disable, and server-side use is out of scope. The mechanism that drops the
  JVM variant from the published Gradle module metadata is chosen and verified by devops; if no clean mechanism
  exists, this item comes back as a question.
- SemVer 0.x; a breaking change bumps the minor version with a CHANGELOG "Breaking" entry; the ABI dump is
  reviewed in every change.

## Alternatives considered

**A. Keep the sketch names (`FaceAnonymizer`, `process`, `processJpeg`).** Pro: matches the extraction plan.
Con: wording rule; `process` says nothing about what happens. Rejected.

**B. Name the entry type `FaceBlur`.** Pro: product name. Con: clashes with the Swift module `FaceBlur`; "blur"
suggests a reversible effect while the masks are opaque. Rejected (product, package and module keep the name).

**C. `expect class FaceMasker` with platform constructors (Android `Context`).** Pro: platform model loading (for
example Android assets). Con: `commonMain` cannot create an instance; KMP apps need host plumbing. Rejected: the model
arrives as bytes or a path (ADR 0012), which every target can handle in common code.

**D. Implicit copies for unsupported images, or returning a new image from in-memory calls.** Pro: works with any
image the app has. Con: doubles memory at 5,000 px and lets an app continue with the unmasked original by mistake.
Rejected (ADRs 0003, 0004).

**E. `Result`-based error API.** Pro: errors visible in the type; no exception crosses into Swift. Con: doubles the
surface; not idiomatic for blocking Java/Swift calls. Rejected.

**F. Separate artifacts for the coroutine wrapper.** Pro: no coroutines dependency for Java-only apps. Con: two
coordinates, breaks "one line"; KMP and Kotlin apps already have coroutines. Rejected; size impact is measured.

**G. Publish the JVM variant.** Pro: the bench's JVM tools could depend on it. Con: telemetry in the JVM runtime jar;
unsupported server-side use. Rejected; tools use the composite build.

**H. Abort mask writing on cancellation.** Pro: faster response to a cancel. Con: leaves a half-masked in-memory
image that an app may still encode. Rejected; decision 8 completes the masks first.

**I. `IllegalArgumentException` for invalid configuration.** Pro: idiomatic in Kotlin and Java. Con: it is not a
`FaceMaskException`, so on iOS it would either terminate the app at the boundary or be wrapped into `Internal` and
lose the parameter name. Rejected in favour of `InvalidConfiguration(parameter)`.

**J. Cosmetic styles (blur, pixelation) behind an opt-in annotation.** This was an earlier draft of this ADR. Pro:
apps with non-privacy uses get them from one library. Con: blurred and pixelated faces can be restored or
recognised; any reachable cosmetic style can end up in a privacy flow by mistake. Rejected by the product principle
(decision 10): never offered, not even as an opt-in.

**K. Expose detection tuning (tile side, overlap, tiling threshold, merge thresholds).** Pro: apps could trade
protection for speed on very large photos. Con: each knob can lower protection, and accuracy was measured only for
the fixed configuration. Rejected; a faster configuration can only come later as a fixed, measured option with the
owner's approval.

**L. No configuration at all.** Pro: smallest surface. Con: apps could not make masks larger or detection more
sensitive. Not chosen; the exposed ranges only allow equal or stronger protection, apart from the explicit opt-ins
listed in decision 10.

## Consequences

Positive
- Same nouns and verbs on Kotlin, Java and Swift; the recommended path (`maskFile`, `StripAll`) is the shortest
  call.
- The edit flow is explicit; the irreversible step is named `applyMasks`/`maskInPlace`.
- The ABI stays small: no data classes, no serialization, no landmarks, no cosmetic styles, no tuning of the
  library's own detection rules (tiling, merge, NMS, mask shift); the model, its profile and its threshold are the
  developer's.
- No setting of the library itself can lower protection below the measured configuration; the three approved
  opt-ins (caller-edited mask lists, metadata allowlist, non-atomic content-URI overwrite) are explicit and named.
  The model, its profile and its threshold are the developer's responsibility (ADR 0012).

Negative / risks
- With the coroutine wrapper (0.2), `kotlinx-coroutines-core` becomes an `api` dependency and is linked into the
  XCFramework (size and notices). Whether 0.1.0, which has no wrapper, still declares it is settled in the build
  change that removes the wrappers from the 0.1.0 scope.
- The main-thread check rejects calls that some apps make in quick prototypes; the message explains why.
- Apps cannot speed up very large photos by coarser detection. File mode always
  detects on at least 3,500 px (or the source size), even when the app wants a small output; that costs the detection
  time of a 3,500 px image plus one scale-down step.
- A `maxDecodedPixels` below what the working image needs makes the call fail instead of silently detecting on a
  smaller image.
- A change of a range after a release is a breaking change or needs a new approval.
- A cancellation requested during mask writing is reported only after the masks are complete, so the call may
  take up to one mask-writing step longer to react.
- The read-back after mask writing doubles the reads of the mask-writing step and needs one extra block buffer (mask
  regions only, not the whole image).
- Every caller `MutablePixelImage` must return exactly what was written (no write-only, lossy or deferred
  implementations); the platform adapters for premultiplied buffers must compare in their declared storage form so
  that semi-transparent soft-edge pixels do not raise `MaskWriteFailed` falsely.
- In-memory images above 2³¹ − 1 pixels are rejected.

Follow-ups
- iOS bridge registration: ADR 0010.
- Swift facade (complete mapping table, DocC, `Sendable` conformance): ADR 0011.
- Devops: mechanism to keep the JVM variant out of the published metadata.

## Verification

- ABI dump (`checkKotlinAbi`) contains exactly the types above; a test greps public symbols and KDoc for "anonymi".
- ABI expectations for the required model hash:
  - `DetectorProfile.expectedSha256` is a non-null `String` property;
  - the constructor takes it as the fifth parameter, after `scoreThreshold` and before the parameters with
    defaults. The `@JvmOverloads` overloads are therefore (format, width, height, threshold, hash), the same plus
    `minMaskExpandX`, and the full form; none omits the hash;
  - `DetectorProfile.Builder.expectedSha256` takes a non-null `String`;
  - `ModelInfo.sha256` stays;
  - `ModelIntegrity` gains no property;
  - no public symbol computes a SHA-256.
  Nothing has been published yet, so this is a pre-release change that needs no CHANGELOG "Breaking" entry; the
  committed ABI dump is updated in the same change as the code, with this reason.
- ABI expectations for the plan binding (ADR 0015 decision 6), additive:
  - `FaceMaskException.SourceChanged`: a final subclass with no public constructor and no property;
    `FaceMaskException` then has 23 subclasses, the list of decision 9, and the Swift enum 23 cases;
  - `MaskPlan.withMasks(masks: List<Mask>): MaskPlan`, a member with `@Throws`; the `MaskPlan` constructor stays;
  - `PlanMasker`: a final class with an `@JvmOverloads` constructor (`()` and `(MaskingConfig)`), the property
    `masking`, and `@JvmOverloads` members `planFile(String[, FileOptions[, CancellationToken]])` and
    `maskFile(String, OutputTarget, MaskPlan[, FileOptions[, CancellationToken]])`; not `AutoCloseable`;
  - Android dump: `FaceMaskerAndroid.planFile(PlanMasker, Context, Uri[, FileOptions[, CancellationToken]])` and
    `FaceMaskerAndroid.maskFile(PlanMasker, Context, Uri, OutputTarget, MaskPlan[, FileOptions[,
    CancellationToken]])`;
  - no public symbol reads, sets, compares or serialises the fingerprint, and `Detection` and `MaskPlan` keep their
    `equals`/`hashCode`. Kotlin `internal` is public in JVM bytecode and the dumps do not list internal members, so
    this is also a Java rule: the fingerprint class is internal and regular, and every member that takes or returns
    it is synthetic (Java-hidden); the JVM boundary scan includes the type and has a negative control (ADR 0015
    decision 6 and Verification);
  - `UprightBox`: a final class with no public constructor, the six getters and the static `fromRaw(float, float,
    float, float, int, int, int)`; `Throws(FaceMaskException)`.
  The additions above are in the JVM, Android and Kotlin library (klib) dumps; the `FaceMaskerAndroid` overloads
  are in the Android dump only.
- Agreement test (Android host + device, iOS simulator + device): one fixture through `maskFile` (with
  `maxLongSide = 0`, internal hook capturing the pre-encode pixels) and through `maskInPlace` on the same decoded
  pixels → identical `FaceBox` lists and identical pixels; then identical JPEG bytes when encoded with the same
  encoder and quality.
- Edit-flow agreement (Android, content `Uri` from a test provider, and paths on every platform): `detectFile` →
  `toMaskPlan()` → `maskFile(..., plan)` produces output bytes identical to `maskFile(...)` without a plan, at the
  default `maxLongSide`, at a larger one and with `maxOutputLongSide = 1000` (plan in working coordinates, output
  scaled), once with the default `MaskingConfig` and once with a non-default
  one (other expansion, style and feather); a plan with a different size throws `MaskPlanMismatch`; a plan edited
  with `withMasks` (one mask removed, one manual mask added) writes exactly its masks; the same plan rebuilt with
  the public constructor throws `MaskPlanMismatch` before the source is opened.
- Fixtures for tests that compare mask regions or pixels near masks are lossless (PNG), or any difference between
  inputs is confined to whole 16 × 16 JPEG blocks; no test expects byte-identical output from lossy inputs that
  differ only inside a mask, because JPEG blocks spread such a difference to neighbouring pixels on correct code.
- Threading tests: main-thread call throws `MainThread`; two threads on one instance → `ConcurrentUse`;
  (from 0.2) `SuspendingFaceMasker` from 8 coroutines completes without error; cancellation between passes leaves the
  in-memory image unchanged (checksum) and leaves no file-mode output; a cancellation requested during mask writing
  (test hook) yields a fully masked image and then `Cancelled`.
- Documentation rule of decision 8: the documentation review (and the UX review of the demos) checks that every
  masking example runs off the main thread and offers cancellation, and that each demo has a working cancel action.
- Wrapper cancellation types: a cancelled Swift `Task` gets `CancellationError`; the blocking API gets
  `FaceMaskException.Cancelled`. From 0.2: a cancelled coroutine gets `CancellationException`;
  `CompletableFuture.cancel(true)` completes with `CancellationException`.
- From 0.2, `AsyncFaceMasker`: after `close()`, no thread created by `async()` is alive (thread count before/after);
  an executor passed to `async(executor)` is not shut down.
- 0.1.0 ABI dump: no `SuspendingFaceMasker`, `AsyncFaceMasker`, `suspending` or `async` symbol.
- Error-boundary tests: on iOS a forced internal error surfaces in Swift as `FaceMaskError.internal`, not a crash;
  on Android/JVM a forced `OutOfMemoryError` inside a call propagates unchanged (not `Internal`), except inside the
  mask stage's working-area allocation, where it becomes `NotEnoughMemory` (ADR 0013); parallel calls whose working
  areas do not fit together are refused by the check with `NotEnoughMemory`, not by the catch (ADR 0013); calls after
  `close()` throw `Closed`; on iOS, creating a `FaceMasker` with no registered bridge throws `RuntimeUnavailable`.
- `@Throws` coverage: a build check over the generated Objective-C header (or the ABI dump) fails if any public
  function of `FaceMasker`, the wrappers, the iOS entry points, or any constructor, factory or builder `build()` of
  the configuration types lacks the `NSError` out-parameter / `@Throws`.
- Configuration errors: one Kotlin test per configuration type with an out-of-range value expects
  `InvalidConfiguration` with the right `parameter`; one Swift test (for example `expandX = 1.0`) expects
  `FaceMaskError.invalidConfiguration(parameter: "expandX")` and no crash, and one Swift test bypasses the facade
  check (test hook) to prove the Kotlin `NSError` path maps to the same case.
- Messages: every `FaceMaskException` raised in the test suite is checked to contain no path, file name or face
  coordinate (fixtures use distinctive names and coordinates so the check can fail).
- Java compile test in `samples/android` uses every public entry point without Kotlin-only constructs.
- Defaults and ranges test: asserts every default in decision 10, including the profile fields, `maxModelBytes` and
  `onnxThreads` 0 to 4; every bound of every exposed setting is accepted;
  the next representable value outside each bound, NaN and infinities throw `InvalidConfiguration` with the right
  `parameter` (Kotlin and Swift); a colour with alpha below 0xFF, `maxDecodedPixels` 12,999,999, `maxLongSide` 1 and 3,499, and
  `maxOutputLongSide` 1 and 63 are rejected; `maxOutputLongSide` 64 and 3,499 are accepted; a score threshold of 0,
  1 and NaN, an input side of 127 and 1,281, `maxModelBytes` 0 and `onnxThreads` 5 are rejected; a profile cannot
  be built without `expectedSha256`, and an empty hash, 63 and 65 digits, a non-hex digit, a prefix and whitespace
  are rejected with `parameter` = `expectedSha256`.
- Detection floor and smaller output: a 5,000 px fixture with `maxOutputLongSide = 1000` is detected and masked at
  3,500 px (test hook records the working size and the detection input) and the output long side is 1,000 px; its
  `FileResult.faces` equal those of the same call without `maxOutputLongSide`; a 2,000 px source with
  `maxOutputLongSide = 1000` is detected at 2,000 px; `maxOutputLongSide = 9000` on that source gives a 2,000 px
  output. Pixel limit: a source whose working image is 18.75 MP with
  `maxDecodedPixels = 13_000_000` throws `ImageTooLarge` (no smaller decode, no output); the same source with the
  default limit succeeds.
- Fail closed: NaN, infinite and inverted caller boxes throw `InvalidConfiguration`; a detector stub returning a NaN
  score, a score above 1 or NaN coordinates throws `ModelOutputInvalid` with the image unchanged; a `MutablePixelImage` that drops writes
  throws `MaskWriteFailed`, and a premultiplied adapter (ADR 0004) with semi-transparent soft-edge pixels does not
  raise it falsely. Size boundaries, with a fake `PixelImage` that allocates nothing:
  - 65,536 × 10, 10 × 65,536 and 46,341 × 46,341 throw `ImageTooLarge` before any `readPixels` call;
  - 65,535 × 1 and 46,340 × 46,340 do not throw for their size.
- `OutputValidationFailed.findings`: a test per code produces it from a planted output and checks the property and the
  message carry exactly that code; a test asserts the public set equals the 0.1.0 list of decision 9, so adding or
  renaming a code fails until this ADR and the CHANGELOG are updated.
- Mask opacity invariant: tests (a)-(f) in decision 10 with their negative controls, for every style and both
  shapes; (e) through the independent pixel oracle.
- Opaque-style test: the differential pixel test (two inputs that differ only inside the masks give identical output)
  runs over every `MaskStyle` entry, so a style added later fails unless it never reads inside a mask; a test-only
  style that reads inside the mask makes it fail (negative control).
- ABI dump: `MaskStyle` has exactly `SMOOTH_FILL`, `GRADIENT_OVAL` and `SOLID`; no public symbol exposes a setting
  whose row in decision 10 says "not exposed" (tile side, overlap, tiling threshold, merge or NMS thresholds, mask
  shift, `GRADIENT_OVAL` internals); no public symbol supplies or registers a custom operator library or a custom
  decoder; no opt-in annotation for cosmetic
  styles exists.
- Settings inventory: a test enumerates every public parameter of the configuration and option types from the ABI
  dump, including the `DetectorProfile` fields and `maxModelBytes`, and fails when one is missing from the decision 10
  table; the internal test seam for like-for-like benchmark
  runs is absent from the dump, and a planted public setter makes the check fail (negative control).
- The YuNet reference test (ADR 0009) runs with `onnxThreads = 1` and with the automatic setting, with the model
  loaded through `DetectorModel.load` and the documented example profile.
- Model loading, profile, contract and output checks: ADR 0012 Verification.
- Content-URI opt-in: `AndroidOutputTargets.newFile(uri)` on a non-empty target and `OverwriteSource` with a
  `content:` source are refused before writing; `overwriteNonAtomic(uri)` writes (ADR 0005 Verification).
