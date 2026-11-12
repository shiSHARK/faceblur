# 0012. Developer-supplied detection model: source, profile, checks and limits

Status: Accepted (2026-11-02, after code review and security review). Supersedes ADR 0006.
Date: 2026-11-02 (revised the same day after code review and security review: complete external-data positions and
walker semantics, format lock, zero-size boxes, immutable profile types, platform timeouts, small-input gap, locked
options, error codes, read rules, leak check, example profiles; no pre-NMS candidate cap, box bounds relative to the
tile; later the same day: final values of the detection cap and the small-input gap, the pass cap derived from
geometry, a spatially indexed cross-pass merge with a per-photo bound, warnings computed for the working size;
amended later: the value of the merge-candidate bound, a work bound for the merge, a test bound by operation count
instead of time, the YuNet score wording, part masks for merged faces; amended again: the expected SHA-256 is
required for every model, with a fixed load order, an internal choice of digest, a confirmation step in the demos
and guidance on bundled and downloaded models; aligned with the implementation: the merge work counts all index
work and fails only beyond the bound, with a provisional value; the pre-parse description limits and name matching;
the digest criterion fixed; amended for the bounded mask stage (ADR 0013, accepted 2026-11-02): a growth cap with a
cover rectangle for part masks, the part test's small-box rule, per-image mask limits, the graph-input limit and the
`TOO_MANY_MASKS` code; amended 2026-11-03: every limit of decision 6 is stated as a generous technical limit with a
technical rationale, the final values of the merge work bound, the per-face cap and both mask limits, a work bound
for the in-pass NMS with the code `TOO_MANY_NMS_PAIR_CHECKS`, and an actionable `TOO_MANY_MASKS` message; amended
again 2026-11-03: the in-pass NMS work bound is final, with an actionable `TOO_MANY_NMS_PAIR_CHECKS` message, and
the evaluation tool's formats and metrics are recorded in ADR 0014; amended 2026-11-03 (project owner): mask area
factor lowered from 64 to 32; the pre-parse walker is fuzzed on path-filtered push and dispatch, no schedule)

## Context

FaceBlur no longer ships a face-detection model. The project owner decided:

- No model is contained in either repository or in any published artifact (AAR, klibs, XCFramework, Swift package).
- The app developer chooses the model, attaches it to the app and passes it to FaceBlur. The developer is
  responsible for the model, its licence, its training data, the profile that describes it and its score threshold.
- **Principle: no strict model guard.** The library keeps only the checks that stop broken files and broken output,
  plus technical limits of the library itself, and it gives developers tools to check their setup. It does not judge
  whether a model, a profile or a threshold is suitable.
- Unchanged: opaque masks only, the output and metadata rules (ADR 0005), JPEG-only output, safe overwrite, no
  network code, and fail closed: a missing model, a declared format that does not match the model, or malformed
  output is an error, never an unmasked image.
- The library code has no model-specific constant: no hash, threshold or size of any named model.
- **Every model is identified by its SHA-256 (project owner's decision).** The app states the expected SHA-256 of the
  model file in the profile; FaceBlur refuses any other file before it parses it. There is no way to load a model
  without an expected hash.
- A model in the YuNet layout remains the project's internal reference model for tests only (ADR 0009). The test build
  fetches it as a fixture, pinned by SHA-256; it is never committed or published.

What FaceBlur still provides: ONNX Runtime packaging and set-up (Android, iOS, KMP, Swift), decoding, orientation,
scaling and tiling of images, decoding of the model's output in two fixed formats, masks, metadata removal and safe
writing.

Runtime facts (SOURCE unless labelled):

- ONNX Runtime Android 1.31.0 offers `OrtEnvironment.createSession(ByteBuffer, SessionOptions)`; the session reads
  the buffer's `position()` and `remaining()`, and offers `getInputInfo()` and `getOutputInfo()` with element types
  and shapes (onnxruntime-android-1.31.0 sources jar, read 2026-11-02).
- ONNX Runtime 1.20.0, the pinned iOS runtime (ADR 0010):
  - its Objective-C `ORTSession` is created only from a file path;
  - it exposes input and output names only, without types or shapes;
  - `ORTValue` exposes `tensorTypeAndShapeInfo` for an output value;
  - `ORTSessionOptions` has `addConfigEntryWithKey:value:error:` (Objective-C headers at tag v1.20.0).
- A reading of the v1.20.0 source shows that 1.20.0 resolves external-data locations written in a model file: it
  treats a special in-memory address tag as a raw address, and it joins absolute and parent-directory paths without
  the checks that later versions added. Whether a crafted model reaches this code when a session is created was not
  tested (ASSUMPTION). Version 1.31.0 rejects both forms.
- Both runtime versions load a buffer as the ORT flatbuffer format, not as ONNX, when its bytes 4-7 hold the ORT
  format identifier, and a path ending in `.ort` likewise, unless `session.load_model_format` is set to `ONNX`
  (MEASURED by reading the runtime source at both tags, 2026-11-02). A byte string can be valid ONNX for a walker and
  ORT format for the runtime at the same time.
- The places where an ONNX file can hold a tensor (and so external data) are the same in the `onnx.proto` vendored by
  both runtimes (onnx 1.16.1 for 1.20.0, onnx 1.22.0 for 1.31.0; MEASURED by comparing the two files, 2026-11-02).

## Decision

### 1. Public types (Kotlin common; Java through static factories and builders; Swift mirror in ADR 0011)

```kotlin
public class ModelSource private constructor(/* bytes or path */) {
    public companion object {
        @JvmStatic public fun fromBytes(bytes: ByteArray): ModelSource   // the array is copied once, at load time
        @JvmStatic public fun fromFile(path: String): ModelSource        // read once, at load time
    }
}

public class DetectorProfile @JvmOverloads @Throws(FaceMaskException::class) constructor(
    public val format: DetectionFormat,
    public val inputWidth: Int,                 // model input width in pixels, 128..1,280
    public val inputHeight: Int,                // model input height in pixels, 128..1,280
    public val scoreThreshold: Float,           // required, finite, 0 < t < 1; no default
    public val expectedSha256: String,          // required, no default: SHA-256 of the model file, exactly 64 hex
                                                // digits in either case; stored and returned in lower case
    public val minMaskExpandX: Float = 1.6f,    // model-specific minimum, 1.6..3.0 (raised from 1.35 before 0.1.0)
    public val minMaskExpandY: Float = 1.8f,    // model-specific minimum, 1.8..3.0 (raised from 1.55 before 0.1.0)
) { public class Builder { /* same fields; expectedSha256(String) takes a non-null value; build() validates and
                              throws InvalidConfiguration("expectedSha256") when no hash was set */ } }

public sealed class DetectionFormat {
    public class YuNetLayout public constructor() : DetectionFormat()
    public class GenericDetections @Throws(FaceMaskException::class) constructor(
        public val boxesOutput: String, public val boxColumn: Int,
        public val scoresOutput: String?,       // null: the scores are in boxesOutput
        public val scoreColumn: Int,
        public val rows: RowLayout, public val boxFormat: BoxFormat, public val coordinates: CoordinateSpace,
        public val colorOrder: ColorOrder, public val tensorLayout: TensorLayout, public val scaling: PixelScaling,
        public val nmsInGraph: Boolean = false,
    ) : DetectionFormat()
}
public class PixelScaling @Throws(FaceMaskException::class) constructor(
    divisor: Float, mean: FloatArray, std: FloatArray,   // value = (pixel / divisor - mean[c]) / std[c]
) { public val divisor: Float; public val mean: List<Float>; public val std: List<Float> }
public enum class RowLayout { ROWS, COLUMNS }            // [N, K] or [1, N, K]; [K, N] or [1, K, N]
public enum class BoxFormat { XYXY, XYWH, CXCYWH }
public enum class CoordinateSpace { INPUT_PIXELS, NORMALIZED }
public enum class ColorOrder { RGB, BGR }
public enum class TensorLayout { NCHW, NHWC }

public class DetectorModel private constructor(/* verified bytes, profile, info */) {
    public val profile: DetectorProfile
    public val info: ModelInfo
    public companion object {
        public const val DEFAULT_MAX_MODEL_BYTES: Long = 52_428_800L   // 50 MiB
        @JvmStatic @JvmOverloads @Throws(FaceMaskException::class)
        public fun load(source: ModelSource, profile: DetectorProfile,
                        maxModelBytes: Long = DEFAULT_MAX_MODEL_BYTES): DetectorModel
    }
}
public class ModelInfo        /* sha256: String (computed from the loaded bytes, lower case; equal to
                                 profile.expectedSha256 for every model that loads), sizeBytes: Long,
                                 irVersion: Long, opsetImports: Map<String, Long>,
                                 inputs: List<TensorInfo>, outputs: List<TensorInfo>,
                                 warnings: List<String> (non-blocking warning codes, decision 7) */
public class TensorInfo       /* name: String, elementType: String, shape: List<Long?> (null = symbolic dimension) */
public class MaskerModelInfo  /* model: ModelInfo, runtimeVersion: String (read from the runtime, never a literal),
                                 executionProvider: String ("XNNPACK" or "CPU"), faceBlurVersion: String */

public class FaceMasker @JvmOverloads @Throws(FaceMaskException::class) constructor(
    model: DetectorModel,
    config: FaceMaskerConfig = FaceMaskerConfig(),
) : AutoCloseable {
    public val modelInfo: MaskerModelInfo
    @JvmOverloads @Throws(FaceMaskException::class)
    public fun checkProfile(image: PixelImage, expectedFaces: List<ExpectedFace>, minIoU: Float = 0.5f,
                            cancellation: CancellationToken? = null): ProfileCheckResult
    // detect / applyMasks / maskInPlace / detectFile / maskFile / maskJpeg / close: unchanged (ADR 0002)
}
public class ExpectedFace @Throws(FaceMaskException::class) constructor(left: Float, top: Float, right: Float, bottom: Float)
public class ProfileCheckResult /* detections: List<FaceBox>, bestIoU: List<Float> (one per expected face, 0 if none),
                                   allFound: Boolean */
```

- **Why `DetectorModel` is separate from `FaceMasker`.** The file is read, capped, hashed and pre-parsed once; N
  workers then create one `FaceMasker` each from the same `DetectorModel`. An app can show the SHA-256 and the model
  report before any runtime session exists, and a pre-parse error is reported without touching the runtime.
- **The expected SHA-256 is required (project owner's decision).**
  - `expectedSha256` is a non-null constructor parameter without a default, placed before the parameters that have
    defaults. Therefore no `@JvmOverloads` overload, no Objective-C initialiser and no Swift initialiser omits it.
  - The Java builder's `expectedSha256(String)` takes a non-null value. `build()` without a hash throws
    `InvalidConfiguration("expectedSha256")`.
  - Accepted values: exactly 64 hexadecimal digits, upper or lower case. A prefix such as `sha256:`, whitespace, an
    empty string and any other length are refused with `InvalidConfiguration("expectedSha256")`.
  - The constructor stores the value in lower case, so `profile.expectedSha256` and `ModelInfo.sha256` compare with
    plain string equality.
  - Format is the only check: FaceBlur does not try to recognise placeholder values. Such a value fails at load
    with `ModelIntegrity`.
  - The hash binds the model bytes only. It does not cover the other profile values (threshold, input size,
    format). Decision 10 says where those values belong.
- **No public hashing helper.** FaceBlur offers no public function that computes the SHA-256 of a file or a byte
  array. The documented patterns never need one: the hash comes from the developer's build (bundled model) or from a
  signature-verified manifest (decision 10). A helper would turn "hash the file you are about to load and pass the
  result", which protects against nothing, into a one-line pattern. Apps that must hash at runtime use their
  platform's digest (the demos do, decision 10).
- **`ModelInfo.sha256` stays.**
  - It is the value FaceBlur computed from the exact bytes the runtime receives, not a copy of the profile value.
    The verification tests compare the two, and on iOS the carrier file is re-hashed against the same value.
  - It lets an app record from the model report alone (`ModelInfo`, `MaskerModelInfo`) which model processed a
    photo. It costs one string.
  - Its KDoc states that, for every loaded model, it equals `profile.expectedSha256`.
- **`ModelIntegrity` names no hash.** The exception has no hash property, and its message contains neither the
  expected nor the computed value. A computed hash in the error would make "catch, copy the hash, retry" a two-line
  way around the check. The guide shows how to compute the hash with standard tools (decision 10).
- **Deep immutability.** Every public profile, format, scaling and report type is immutable after construction:
  - constructors copy every array argument (a caller that changes its `FloatArray` or `ByteArray` afterwards changes
    nothing);
  - arrays are never exposed; `mean` and `std` are read-only `List<Float>` copies;
  - report collections (`opsetImports`, `inputs`, `outputs`, `shape`, `detections`, `bestIoU`) are unmodifiable
    copies; from Java, a mutation attempt throws `UnsupportedOperationException`.
  The thread-safety of `DetectorModel` and the Swift wrapper's `@unchecked Sendable` argument (ADR 0011) rest on
  this rule.
- `DetectorModel` is thread-safe; several threads may create `FaceMasker` instances from it at the same time. It keeps
  the verified bytes so that more instances can be created. A `FaceMasker` keeps only the profile and the reports, so
  an app that has created all its instances can drop the `DetectorModel`.
- `load` blocks (reading, hashing, parsing). Like the `FaceMasker` constructor (ADR 0002 decision 2), its KDoc says
  to call it off the main thread, but a call on the main thread is not rejected.
- A Java caller that passes `null` for a non-null parameter (for example the model, or `expectedSha256` in the
  constructor or the builder) gets Kotlin's `NullPointerException` from the parameter check. This happens at that
  call, before any profile exists and before any read; KDoc states it. No image is touched. A typed error for a Java
  `null` would need a nullable Kotlin type, which would mark the hash as optional in the ABI dump, the KDoc and the
  Objective-C header.
- Every public function carries `@Throws(FaceMaskException::class)` (ADR 0002 decision 9).

### 2. The two output formats (decoders are FaceBlur code; the developer declares values, never code)

- **`YuNetLayout`.** The OpenCV `FaceDetectorYN` output layout:
  - outputs `cls_s`, `obj_s`, `bbox_s` for the strides 8, 16 and 32; `kps_s` outputs are optional and ignored;
  - input BGR, values 0-255, NCHW; `inputWidth` and `inputHeight` must be multiples of 32
    (`InvalidConfiguration("inputWidth")` / `("inputHeight")` when the profile is built);
  - score = sqrt(cls × obj). Both values first pass the probability check of decision 5 and are then each limited
    to [0, 1] before the square root, exactly as OpenCV's `FaceDetectorYN` does (OpenCV source, `face_detect.cpp`,
    checked 2026-11-02; the generic-route parity test confirms bit-identical scores). This is numeric hygiene for
    reference parity, not score clamping: it acts only on values inside the ±1 × 10⁻⁶ tolerance band, keeps the
    score in [0, 1] (no square root of a negative number), and moves a score by at most 1 × 10⁻⁶ against the formula
    without the limit wherever that formula is defined. It can therefore only matter for a score within 1 × 10⁻⁶ of
    the threshold, and there it follows the reference decoder;
  - in-pass NMS at IoU 0.3 on integer boxes.

  This is the decoder that the reference test gates (ADR 0009).
- **`GenericDetections`.** Decoded detections, N rows of K values:
  - each row holds four box values starting at `boxColumn` and one face probability at `scoreColumn`, in the same
    output or in `scoresOutput`; with a separate `scoresOutput`, the scores tensor has the same `RowLayout` and the
    same N;
  - K must be static. At load, `boxColumn + 3 < K` of the boxes output and `scoreColumn < K` of the scores output
    are checked, and in a single output the score column must not lie among the four box columns (`OUTPUT_SHAPE`);
  - `XYXY` = left, top, right, bottom; `XYWH` = left, top, width, height; `CXCYWH` = centre x, centre y, width,
    height;
  - `INPUT_PIXELS` = pixels of the model input tensor; `NORMALIZED` = fractions of the input tensor's width (x
    values) and height (y values), not of the letterboxed content;
  - `PixelScaling`: channel index c is the position in the tensor that the model receives, after `colorOrder` is
    applied (c = 0 is red for `RGB`, blue for `BGR`); divisor > 0, three finite means, three std values > 0;
  - in-pass NMS at IoU 0.3, as in `YuNetLayout`, unless `nmsInGraph` is set.
- **No custom decoder, no anchor decoding inside FaceBlur for the generic format, no landmarks.** Landmarks in a
  model's output are ignored.
- **Probabilities only.** Scores must be probabilities. A value outside [−1 × 10⁻⁶, 1 + 1 × 10⁻⁶] or a non-finite
  value fails the pass (decision 5); a model with logit outputs is therefore an error, not a model whose faces
  silently disappear. A value inside the tolerance band is reported limited to [0, 1] (`FaceBox.score` never exceeds
  1); with a threshold strictly between 0 and 1 this cannot change any decision.
- **NMS inside the model (`nmsInGraph = true`).**
  - The flag only skips FaceBlur's in-pass NMS. The cross-pass merge always runs, because tiling creates
    duplicates across passes. Skipping the in-pass NMS keeps more boxes, so it cannot remove a mask.
  - Such models usually have a dynamic row count N.
  - **Plain statement for the documentation (KDoc of `nmsInGraph` and `scoreThreshold`, integration guide):** if a
    model filters scores inside its graph, faces below that cut-off are lost even when the profile's threshold is
    lower, and FaceBlur cannot detect this.

### 3. Library rules that are the same for every model

- **Tiling.** The tile is the model's input size in source pixels (1:1; tiles are never scaled). The input may be
  non-square, for example 640 × 480. Overlap is 0.25 on each axis, every image is tiled, and there is one global pass,
  which shrinks the whole working image to the input size.
- **Small inputs (plain statement for the documentation).** With overlap 0.25, a face lies whole inside some tile
  only if it is at most about a quarter of the tile side. Larger faces rely on partial detection in tiles or on the
  global pass, which shrinks them by working size / input size. A small model input can therefore miss mid-size faces
  that a larger input finds. **The gap grows with the working size**, with its square root. By geometry: the
  largest face that lies whole in a tile is about a quarter of the input side s; the global pass shrinks it to about
  s² / (4 × working size) px. With a detector that needs faces of about 10-16 px at its input (ESTIMATE, typical of
  small-face detectors), that face falls below the detector's minimum when s is below about 384 px at the default
  3,500 px working size (s² / 14,000 ≈ 10.5 px), which scales to about 580 px at an 8,000 px working size (a larger
  `maxLongSide`, `maxLongSide = 0` on a large source, or an in-memory image at native size). The value uses the lower
  end of that range, so it is an estimate of the lower bound: a model that needs larger faces has a wider
  gap. This is the developer's
  responsibility; the guide and the evaluation tool show it. The lower bound of 128 px per side stays (project owner's
  decision) and is a technical limit, not a statement that small inputs are suitable.
  **Warning.** The gap side for a working long side L is `SMALL_INPUT_GAP_SIDE` × √(L / 3,500), where the internal
  named constant `SMALL_INPUT_GAP_SIDE` = 384 px is the gap side at the 3,500 px reference working size (derived as
  above). When the smaller input side is below the gap side, the non-blocking warning `SMALL_INPUT` (decision 7) is reported:
  - in `ModelInfo.warnings`, computed at load for the default working size of 3,500 px;
  - in the `warnings` of every `Detection`, `MaskResult` and `FileResult`, computed for that call's actual working
    size (file mode: the working size that `maxLongSide` gives for the source; in-memory mode: the long side of the
    image passed in).
  Loading, creation and masking proceed normally. Extra passes at intermediate scales, which would close the gap, are a candidate for a later version,
  not for 0.1.0.
- **Letterbox.** Top-left, padded with black; never stretched and never upscaled. Boxes are mapped back by FaceBlur's
  own transform.
- **Merging.** The cross-pass merge uses IoU 0.4 or containment 0.7, with the cut-box rules. **Lowering the score
  threshold never removes or shrinks a mask:** for the same model and image, when both calls succeed, every mask found
  at a threshold t lies inside the union of the masks found at any lower threshold. (Below the validated range of
  decision 6 the detection cap or the merge bound can make the call fail instead; that fails closed.) A unit test
  proves the property, and an end-to-end rerun of the accuracy evaluation checks it.
  **Part masks.** Candidates are merged in score order, so the merge at a lower threshold first repeats, step by step,
  the merge at the higher threshold and only then adds the lower-scored candidates; a kept box therefore only grows
  (the union rule). The mask is an ellipse, and the ellipse of a larger box does not always contain the ellipse of a
  box inside it: for a face cut in half by a tile border, the half's ellipse sticks out of the whole face's ellipse
  at its sides by up to about 6 % of the box side at the default expansion, and the sliver never vanishes at any
  expansion (by geometry; up to about 2.5 % of the merged face's mask area at the default expansion). So when a kept box grows, its previous state stays as an extra mask of the
  same face, a *part mask*, unless its mask lies wholly inside the grown box's mask (exact test in box-normalised
  coordinates at the smallest allowed expansion, ×1.6 / ×1.8, which then holds for every larger expansion; a
  rectangle mask never needs one). A part mask is the mask that the higher threshold drew, bit for bit, so the
  property holds pixel for pixel, also at the image edges.
  - **Drawn geometry.** The stage draws every mask with both radii raised to at least 1 px around the same centre;
    every containment statement here is about these drawn masks, evaluated with the stage's float pixel test
    (ADR 0013).
  - **Small boxes.** The part test also answers "not covered" (the part is drawn) when any radius of the part or of
    the face at the smallest allowed expansion is below 1.05 px, because a raised radius could otherwise put a drawn
    part pixel outside the drawn face mask.
  - **Growth cap** (ADR 0013 decision 1). The merge counts how often each kept box grew. A face that grew at most
    32 times (`MAX_PART_MASK_GROWTH`) keeps its part masks as above. A face that grew more often is drawn as one
    RECTANGLE mask, the cover, whatever shape the masking config asks for, with no part masks. The cover's edges are
    computed in double precision from the final box with monotone operations and snapped outwards to whole pixels,
    so they hold every drawn earlier state of the face, and the cover at a higher threshold lies inside the cover at
    any lower threshold; the property above therefore still holds pixel for pixel. The edge formula, its proof and
    its measurements are in ADR 0013 decision 1. The cover is 4/π = 1.27 times the face's ellipse area.
  - **Work.** Each evaluation of the part test counts one unit towards the merge work bound, up to 4,096 per part.
  - `Detection.faces` still lists one box per merged face; the part boxes travel inside the `Detection`.
    `toMaskPlan()`, `maskInPlace` and `maskFile` draw them as `DETECTED` masks, so a plan can hold more masks than
    there are faces, and `MaskResult.masks` lists them. `Mask.fromFace(face)` alone builds only the face's own mask.
  - Rejected alternatives: a covering box whose ellipse contains the previous state (one mask per face, but the
    reported box is no longer the union of the detections, the mask area of a merged face grows by about 8 % in the
    median and up to about a third in simulated tile-border chains, and the box would have to stay unclipped at the
    image edge); and a box-level property only (no extra cost, but the slivers above are not masked at the lower
    threshold, which contradicts the rule as stated).
  **Algorithm.** The merge uses a spatial index (for example a uniform grid or a sort-and-sweep on box edges), so its
  cost grows sub-quadratically with the number of candidates for face-like candidate sets. Its result is identical
  to the plain pairwise merge (same boxes, same order): a test compares the two on the reference fixtures and on
  random candidate sets, including dense ones. The index does not change the worst case: a set of many small
  separate boxes followed by many large lower-scored boxes over them makes every candidate examine most kept boxes,
  so the work grows with the square of the candidate count. Two bounds therefore stop a runaway merge, each a typed
  error (`TOO_MANY_MERGE_CANDIDATES`), never a truncation (decision 6):
  - the number of candidates entering the merge per photo, `MAX_MERGE_CANDIDATES_PER_PHOTO`, checked as candidates
    are collected, before the merge starts; it also bounds the merge memory;
  - the merge work per photo, `MAX_MERGE_PAIR_CHECKS_PER_PHOTO`, which counts **all** of the index's work, not only
    pair checks: one unit for every cell lookup, every index entry read while walking a bucket or a level (whether
    it is a kept box of the queried cell, which then gets the overlap test, or an entry that is skipped), and every
    entry passed while a moved box is unlisted. Counting only the kept boxes returned would leave empty-cell lookups
    and skipped entries uncounted, and those can also grow with the square of the candidate count. The count is
    checked as it grows; the bound is the largest allowed amount of work, like the other caps: a merge of exactly
    that many units completes, and the unit that would exceed it stops the merge before any pixel is written.
  The candidates are stored in primitive arrays (four box values, the score and the cut flag per candidate), not as
  one object per box, so the memory at the bound stays at tens of megabytes. Cancellation is read in proportion to
  the work: at least once every 65,536 units of work (and every 4,096 candidates).
- **Detection floor.** 3,500 px for file mode, and the pixel limits (ADR 0002 decision 10).
- **Mask geometry.** Shift up 0.08 × box height. For **detected faces**, the expansion per axis is the larger of the
  profile's minimum and `MaskingConfig.expandX`/`expandY`; both are at least ×1.6 / ×1.8 and at most 3.0 (the floor was ×1.35 / ×1.55 in the design stage and was raised before 0.1.0).
  `Detection.masking` carries this effective `MaskingConfig`, so `toMaskPlan()` and the edit flow reproduce the
  instance's own masks. **Manual masks** (`Mask.manual`, `Mask.exact`, a caller's `MaskPlan`) keep the geometry the
  app passes, under the existing rules of ADR 0002; an app that wants the same expansion for a hand-drawn box passes
  `detection.masking`.
- **Unchanged rules.** Opacity invariant, styles, shapes, colour rule, output and metadata rules (ADR 0002,
  ADR 0005).
- **Pass cap.** `MAX_PASSES_PER_IMAGE` = 262,144, derived from geometry, not from a model (decision 6). Every image
  within the library's size limits needs fewer passes than this with any allowed input size, so the cap never refuses
  a valid call; it is a fail-closed guard against a broken pass computation. If it is ever exceeded, the call throws
  `ImageTooLarge` before the first pass; its `limit` is the pixel count of the largest square working image whose
  passes fit the cap for this input size, and its message names the model input size. A tile is never skipped. (No
  creation-time check is needed, because no allowed profile can make the cap refuse a normal photo.)

### 4. Loading and creation: steps and fail-closed checks

| Step | When | Checks | Error |
|---|---|---|---|
| 1 Profile | `DetectorProfile`, `GenericDetections`, `PixelScaling`, `ExpectedFace` constructors and builders | every number finite and in its range (decisions 1 and 2); names non-empty; columns ≥ 0; `expectedSha256` present and exactly 64 hexadecimal digits | `InvalidConfiguration(parameter)` |
| 2 Read | `DetectorModel.load` | `maxModelBytes` in 1 .. 2,147,483,639 (`InvalidConfiguration("maxModelBytes")`). Read rules below. At most `maxModelBytes` + 1 bytes are read, so a file that grows is detected; a file that shrinks is fine. `fromBytes`: size checked before the copy. The bytes actually read go into one library-owned buffer, are hashed and are the only bytes the runtime ever receives. | `IoFailed` (no path in the message); `ModelRejected[EMPTY, TOO_LARGE, NOT_REGULAR_FILE]` |
| 3 Pin | same | SHA-256 of the buffer (digest below). It must equal `expectedSha256`, always; there is no unpinned load. A file that does not match is never parsed and never reaches the runtime. | `ModelIntegrity` |
| 4 Pre-parse | same | The bounded walk below: format identifier, external data anywhere, protobuf rules, graph IO for the contract (exactly one non-initializer input, float32, rank 4, compatible with the profile; every output the format needs present and float32, with a compatible rank and static K). | `ModelRejected[NOT_ONNX, NESTING_TOO_DEEP, EXTERNAL_DATA, INPUT_COUNT, INPUT_TYPE, INPUT_SHAPE, OUTPUT_MISSING, OUTPUT_TYPE, OUTPUT_SHAPE]` |
| 5 Session | `FaceMasker(model, config)` | Runtime available (iOS: bridge registered, version 1.20.0, ADR 0010). Session created from the verified bytes with the locked options below; an option that cannot be set fails creation. | `RuntimeUnavailable`; `ModelRejected[RUNTIME_REJECTED]` |
| 6 Runtime IO | same | Android/JVM: input and output info from the runtime equal the pre-parse result. iOS: the names equal it; the 1.20.0 API has names only. | `ModelRejected[IO_MISMATCH]` |
| 7 Contract run | same | One pass on a uniform mid-grey input. Output names, types and shapes as declared; the checks of decision 5 pass; the pass ends within 30 s. Android/JVM stop the run at 30 s; iOS cannot stop a run (ADR 0010), so the limit is checked when the run returns, and a run that never returns blocks creation. A static output shape is recorded as the exact size for every later pass. The pass's duration sets the Android/JVM run timeout (decision 6). | `ModelRejected[CONTRACT_OUTPUT, CONTRACT_VALUES, TIMEOUT]` |

**Fixed order (project owner's decision): size cap, then SHA-256, then pre-parse, then runtime.**
- Each step runs only on bytes that passed the step before. Consequences:
  - a file over the cap is refused as `TOO_LARGE` without being hashed;
  - a file within the cap that does not match the hash is refused as `ModelIntegrity`, even if it is also malformed;
  - only a matching file is pre-parsed;
  - only a matching, well-formed file reaches the runtime.
- On iOS the carrier file (ADR 0010 decision 6) is hashed again with the same digest and compared with
  `expectedSha256` before its path is handed to the glue.
- Nothing in steps 4-7 is relaxed because the hash matched. The hash proves only that FaceBlur read the bytes whose
  hash the app passed; it does not prove that those bytes are benign, because an app can compute the hash from an
  untrusted file at runtime. The pre-parse therefore stays mandatory on every platform, with all its rules and its
  time and memory bounds.

**Digest (step 3).** The library uses one internal digest interface, which is not public API.
- **Default.** The common-Kotlin FIPS 180-4 implementation. It processes the buffer block by block, with 64-bit
  length arithmetic and without copying the message: the padding is built in a final block of at most 128 bytes. A
  model at the upper cap therefore needs no second buffer and no `Int` overflow can occur.
- **Platform digest.** If the common implementation is too slow near the 50 MiB default cap on the reference phones,
  that platform uses its platform digest for this step:
  - Android/JVM: `java.security.MessageDigest` with `"SHA-256"`, which Android documents as supported from API
    level 1;
  - iOS: CommonCrypto `CC_SHA256_Init` / `CC_SHA256_Update` / `CC_SHA256_Final`, present in the Kotlin/Native
    platform library `platform.CoreCrypto`. These are C calls, so no Objective-C object is created per call (ADR
    0010). The pinned buffer is fed in chunks of at most 1 MiB, because the length argument is 32-bit.
- **Rule (fixed before the run; project owner's decision).** A platform keeps the common implementation when the
  median of 5 runs hashing 50 MiB in a release build on that platform's reference phone is at most 500 ms;
  otherwise it uses its platform digest (`MessageDigest` or CommonCrypto above). The result and the choice are
  recorded in this ADR. The internal digest seam is built either way, so either outcome needs no structural change.
  The choice is internal and per platform, and the digest never sees a path, only the library-owned buffer.

**Read rules (step 2), per platform.** Only the last path component is checked for a symbolic link (iOS container
paths themselves contain system links).
- iOS (POSIX): `open(path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)`, then `fstat` must report a regular file
  (`S_ISREG`), then `O_NONBLOCK` is cleared and the file is read. A FIFO therefore never blocks the call.
- Android (`android.system.Os`): the same flags and order.
- JVM (tools and tests): `Files.readAttributes(path, BasicFileAttributes, NOFOLLOW_LINKS).isRegularFile` before
  `Files.newByteChannel(path, READ, NOFOLLOW_LINKS)`, and the `fileKey()` compared before and after the read; the
  remaining check-to-open race is a documented residual of the unpublished JVM target.

**Pre-parse (step 4), the mandatory structural check before the runtime sees any byte (it runs after the hash
check and is not relaxed by it).** A streaming walk of the ONNX protobuf in common Kotlin. Its memory is bounded by
the nesting depth and the IO description (limited by item 8), not by the node count, and its time grows with the file
size times at most the logarithm of the number of graph inputs.
1. **Format identifier.** A buffer whose bytes 4-7 equal the ORT flatbuffer format identifier is refused
   (`NOT_ONNX`), in addition to the format lock of step 5.
2. **Positions walked.** Every message that can contain a tensor is walked, recursively, at any depth, in any
   combination (for example a subgraph inside a model-local function inside a subgraph). Field numbers are those of
   the vendored `onnx.proto` (identical carrier set in onnx 1.16.1 and 1.22.0):
   - `ModelProto.graph` (7), `ModelProto.functions` (25), `ModelProto.training_info` (20);
   - `GraphProto.initializer` (5), `GraphProto.sparse_initializer` (15), `GraphProto.node` (1);
   - `NodeProto.attribute` (5);
   - `AttributeProto.t` (5), `.g` (6), `.tensors` (10, list), `.graphs` (11, list), `.sparse_tensor` (22),
     `.sparse_tensors` (23, list). Every attribute field is inspected **regardless of the declared
     `AttributeProto.type`**;
   - `SparseTensorProto.values` (1) **and** `.indices` (2);
   - `FunctionProto.node` (7) and `FunctionProto.attribute_proto` (11, default attribute values);
   - `TrainingInfoProto.initialization` (1) and `.algorithm` (2).
   The walker's field table is the union of both vendored `onnx.proto` files. A unit test pins the table, and it is
   re-checked at every runtime version change.
3. **Refusal predicate (`EXTERNAL_DATA`).** A `TensorProto` is refused when it contains **any** occurrence of
   `data_location` (14) whose decoded varint, compared as the full 64-bit value, is not 0, **or** any occurrence of
   `external_data` (13). This is a superset of what either runtime acts on: unknown enum values and encodings that a
   runtime might truncate to EXTERNAL are refused too.
4. **Every occurrence.** Protobuf merges repeated occurrences of a singular message field, so the walker could
   otherwise see a different message than the runtime. A duplicate occurrence of a singular message field
   (`ModelProto.graph`, `AttributeProto.t`, `.g`, `.sparse_tensor`, `SparseTensorProto.values`, `.indices`,
   `TrainingInfoProto.initialization`, `.algorithm`) is refused (`NOT_ONNX`). Every occurrence of every repeated
   field is walked.
5. **Wire types.** Every length is checked against the remaining buffer. A known field with an unexpected wire type
   and the deprecated group wire types (3, 4) are refused (`NOT_ONNX`). Unknown fields with a valid wire type are
   skipped within bounds.
6. **Depth.** Message nesting deeper than 100 levels is refused (`NESTING_TOO_DEEP`), never "stop descending and
   accept".
7. **IO.** The graph inputs and outputs are read for the contract. Graph inputs that are also initializers (common
   in older exports) are not counted as model inputs and are never fed. For the scalar fields read for the contract
   (for example `elem_type`, `dim_value`, `dim_param`, names), the walker applies protobuf's rule that the last
   occurrence wins, so it reads the same values as the runtime's parser. (A difference would also fail at the
   contract run, but on iOS the runtime IO check compares names only.)
   Initializer names are matched against a table of the graph-input names that is sorted by merge sort on the name
   bytes and searched by binary search, without making strings: at most n log n name comparisons for n graph
   inputs, so chosen names cannot steer the cost (no hashing), and a few bytes per input name. Of the inputs that are
   not initializers, only the first two are kept (two already decide `INPUT_COUNT`).
8. **Description limits.** These bound the walker's own memory; they are far above what a face detector declares
   and do not judge the model's graph (alternative B). Each is refused as `NOT_ONNX`, like the walker's other
   structural refusals, and the limit itself is accepted:
   - more than 1,024 outputs of the main graph;
   - more than 1,024 distinct operator-set domains in the opset imports (a repeated domain counts once; its last
     version wins);
   - more than 64 dimensions in one declared shape of a main-graph output or of any main-graph input, including an
     input that is also an initializer;
   - more than 65,536 inputs of the main graph, including inputs that are also initializers (ADR 0013 decision 5).
     This caps the sorted name table of item 7 and its memory, so the pre-parse time grows about linearly with the
     file size. `DetectorModel.load` has no cancellation; its KDoc states that it reads, hashes and checks the whole
     file in one blocking call whose time grows with the file size, once per model, off the main thread.

**Locked session options (step 5), one list for both platforms.** The glue (iOS, ADR 0010) and the Android/JVM runner
set exactly these; a CI grep gate in both repositories is an allow-list of configuration keys (any other key, and any
call in the "never" column, fails the build).

| Required (set explicitly; failure to set → `RuntimeUnavailable`) | Never set or called |
|---|---|
| `session.load_model_format` = `ONNX` | `session.allow_released_opsets_only` = `0` (the required value is `1`, set explicitly) |
| `session.allow_released_opsets_only` = `1` | `session.use_ort_model_bytes_directly`, `session.use_ort_model_bytes_for_initializers` |
| graph optimisation level "all" | custom operators: `registerCustomOpLibrary`, `registerCustomOpsUsingFunction`, iOS `registerCustomOpsUsingFunctionPointer`, `enableOrtExtensionsCustomOps` |
| session and run logging level `fatal` | `setOptimizedModelFilePath`, profiling |
| XNNPACK execution provider with CPU fallback only; intra-op pool 1 thread; spinning off (`session.intra_op.allow_spinning` = `0`) | `addExternalInitializers`, `addInitializer`, `session.model_external_initializers_file_folder_path`, any `ep.context_*` key |
| — | any other execution provider (NNAPI, Core ML, QNN and others) |

Session creation itself (parsing, graph optimisation, constant folding) has **no time limit** on either platform;
only the contract run is timed. A model can stall or exhaust memory at creation. This is a documented residual and
the developer's responsibility.

What the library does **not** check (the developer's responsibility):
- **Operators.** There is no operator allow-list and no domain, IR or opset window. Any model that the runtime
  loads with its built-in operators is accepted, and an operator the runtime lacks fails at step 5.
- **Suitability.** Whether the model detects faces well, whether the profile matches how it was trained, whether the
  input size suits the app's photos (decision 3), and whether the threshold suits them.

**Residual risk, stated plainly in the KDoc and the integration guide:** a well-formed model with a wrongly declared
profile can find no faces and still report success. Wrong colour order, value scaling or box convention are examples.
FaceBlur cannot see this from the file or the output. `checkProfile` (decision 7), the demos' "Check profile" step
and the evaluation tool exist so that developers can check their setup.

### 5. Checks on every pass (before any pixel is written)

`ModelOutputInvalid(findings)` is thrown before any pixel is written. A file target stays unchanged, and an
in-memory image stays unchanged.

- **Types and counts.** Every output is float32 (`OUTPUT_TYPE`).
  - Static shapes: exactly the count recorded by the contract run (`OUTPUT_SHAPE`).
  - Dynamic row count (NMS in the graph): the shape the runtime reports must be consistent (N × K elements, with K as
    declared, `OUTPUT_SHAPE`), N within the row cap (`TOO_MANY_ROWS`) and the element count within the element cap
    (`TOO_MANY_ELEMENTS`).
- **Values.** Every value the decoder reads must be finite (`NON_FINITE`); scores must lie in [0, 1] ± 1 × 10⁻⁶
  (`SCORE_RANGE`, decision 2).
- **Boxes.**
  - A box whose own edges are not strictly ordered, **before any clipping** (right ≤ left or bottom ≤ top, which
    includes zero width or height), is an error (`BOX_INVERTED`); a confident detection is never dropped for being
    degenerate.
  - **Technical bounds (only against broken output).** In the pixels of the pass's input tensor (width W, height H),
    every x must lie in [−2 W, 3 W] and every y in [−2 H, 3 H], and the box may be at most 5 W wide and 5 H high;
    otherwise `BOX_BOUNDS`. After mapping to image pixels these bounds stay far inside the mask-coordinate bound of
    ±4,194,304 px (ADR 0002 decision 9).
  - **Inside these bounds, a box that lies partly outside the tile or the image is normal (faces at the edge): it is
    clipped to the image and masked.** This is the output contract that ADR 0009 asserts: edges are limited to [0, width] × [0, height].
  - Only a box that becomes empty **by clipping** is dropped: it lies wholly in the letterbox padding or outside the
    image, so it covers no image pixel.
- **Caps (decision 6).** Exceeding a cap is an error (`TOO_MANY_ROWS`, `TOO_MANY_ELEMENTS`,
  `TOO_MANY_DETECTIONS`), never a truncation.
- **Run failure.** A runtime error during a pass is `RUN_FAILED`; the Android/JVM run timeout is `RUN_TIMEOUT`
  (distinct from `Cancelled`). After `RUN_TIMEOUT` the instance stays usable; the next call uses new run options
  (ASSUMPTION that a terminated run leaves the session valid, checked by a test).
- **Runtime errors stay inside.** Runtime exception objects (`OrtException`, `NSError`) are never chained as `cause`,
  and their text never enters a FaceBlur message, on every platform (runtime messages can contain paths and names).

`Internal` remains for faults of the library itself, so an app can tell "your model or profile" from "a FaceBlur
bug".

### 6. Technical limits (generous; exceeding one fails safely)

The limits below bound memory and run time on broken or runaway output; they do not judge a model. Each is a
generous technical limit, with the technical rationale given in the table. Exceeding one fails safely: the call
throws the stated error before any pixel is written, the image or file target is unchanged, and no list is ever
truncated and no box dropped to stay within a limit.

| Limit | Value | Notes |
|---|---|---|
| Model size | default 50 MiB (52,428,800 bytes); the app may set 1 .. 2,147,483,639 bytes | The upper value is the largest array size the JVM allocates reliably (2³¹ − 9; ASSUMPTION from the JDK's own array-size limit). A protobuf model above 2 GiB needs external data, which is refused anyway. KDoc states the peak memory during creation: about 3-4 × the model size (ESTIMATE). |
| Rows per output | ≤ 65,536 | Also the only bound on candidates above the threshold: there is **no separate candidate cap**, so a valid low threshold never fails because of the number of candidates. The in-pass NMS then runs on up to 65,536 candidates; its work is bounded per photo (row below). |
| Elements per output | ≤ 4,194,304 | |
| Detections after the in-pass NMS, per pass | `MAX_DETECTIONS_PER_PASS` = 40,000 | `TOO_MANY_DETECTIONS`. A generous technical limit below the row limit: it caps the boxes one pass hands to the merge. A tile of a photo holds far fewer faces than that; output above it is runaway output. With `nmsInGraph = true` there is no in-pass NMS, so it counts the rows of the pass whose score is at or above the threshold |
| In-pass NMS work per photo (all passes) | `MAX_NMS_PAIR_CHECKS_PER_PHOTO` = 240,000,000, final (project owner's decision) | `TOO_MANY_NMS_PAIR_CHECKS`, before any pixel is written; the message is actionable (decision 7). One unit per pair of boxes compared (a candidate against a kept box), summed over the passes of the photo; cancellation is read at least every 65,536 units. Technical rationale: the in-pass NMS is quadratic in the kept boxes, so one pass with 65,536 candidates that suppress little can need about 2 × 10⁹ pair checks, and a photo has many passes. The bound stops such output within the same order of work as the merge work bound; the public test fixtures with the reference model need under 1 million per photo at threshold 0.01. The time at the bound is checked on both reference phones (rule below). Not counted with `nmsInGraph = true` |
| Passes per image | `MAX_PASSES_PER_IMAGE` = 262,144 | derived from geometry (below); never binding within the size limits |
| Merge candidates per photo (all passes) | `MAX_MERGE_CANDIDATES_PER_PHOTO` = 2,500,000, a fixed value for every working size | `TOO_MANY_MERGE_CANDIDATES`. A generous technical limit that caps the memory and work of one merge. Not scaled with the pass count, for the same reason; larger working sizes at very low thresholds can exceed it and then fail safely. Adopted only after the phone measurement of merge time and memory at the bound (below). |
| Merge work per photo (units of index work, decision 3) | `MAX_MERGE_PAIR_CHECKS_PER_PHOTO` = 240,000,000, final | `TOO_MANY_MERGE_CANDIDATES` (the same code: the merge is too large). Technical rationale: 96 units of index work per candidate at the candidate bound. For face-like layouts the spatial index examines a small fixed number of kept boxes per candidate (the dense synthetic test below), so ordinary output stays far below the bound, and adversarial layouts that make the merge quadratic stop at it. The count is deterministic; the time it stands for is checked on both reference phones (rule below). |
| Part-mask growth per face | `MAX_PART_MASK_GROWTH` = 32, final | Above it the face is drawn as one RECTANGLE cover (decision 3, ADR 0013 decision 1); never an error. Technical rationale: it bounds the masks of one face at 33. A face grows only when a lower-scored box of the same face changes its edges; with overlap 0.25 a face is seen by a few tiles and the global pass, so ordinary output needs a few growth steps per face |
| Masks per image (faces plus part masks) | `MAX_MASKS_PER_IMAGE` = 65,536, final (project owner's decision) | `TOO_MANY_MASKS` for masks from the model, checked after the merge before any result object is created; a caller's plan gives `InvalidConfiguration("masks")` (ADR 0013 decision 2). Technical rationale: it bounds the per-mask memory (at most 8 MiB of per-mask arrays, ADR 0013 decision 6) and fixed costs. A photo has far fewer faces; a model reaches the limit only at a score threshold far below its useful range, which is why the message says so (decision 7) |
| Mask work per image | `MAX_MASK_AREA_FACTOR` = 32, final (lowered from 64 by the project owner after the phone measurements): the summed working-rectangle area of all masks, clipped to the image, at most 32 × max(image pixels, 1,048,576) | `TOO_MANY_MASKS` or `InvalidConfiguration("masks")` as above, checked before the mask stage reads any pixel. Technical rationale: the stage's run time grows with this sum, so the limit bounds it at 32 passes over the image (over 1 MP for small images); separate faces cover a fraction of the image once, so only heavily stacked masks reach it. A generous technical limit; exceeding it fails safely. The guides give the worst-case rule (memory and mask limit) but state no masking times; slower phones take longer. Run masking off the main thread and offer cancellation. |
| Contract-run time | 30 s | Android/JVM: the run is stopped; iOS: checked when the run returns (decision 4 step 7) |
| Run timeout per pass | Android/JVM: max(2 s, 20 × the contract-run time), through `RunOptions.setTerminate` | iOS: none. The 1.20.0 runtime has no terminate call; a pass that never returns blocks that call and that instance, and cancellation acts only between passes (platform limitation, ADR 0010) |
| Session creation | no time limit (both platforms) | documented residual (decision 4) |
| `onnxThreads` | 0 .. 4 | ADR 0002 decision 10 |

**Rule for the limits.** Every limit in this table is a generous technical limit: its value comes from the memory or
the work it stands for (the rationale in the table), not from a judgement of a model, and exceeding it fails safely.
Before a value is adopted, the project checks that the documented example models at low score thresholds stay well
below it and that the generic-format fixtures pass, and it measures on both reference phones the time and peak
memory at the bound: the merge at both merge bounds, the in-pass NMS at its work bound, and the mask stage at both
mask limits and with a 25 MP image masked almost everywhere (ADR 0013). If a phone cannot complete the work at a
bound without running out of memory, or the work at a bound takes minutes, the bound is lowered, and if that conflicts
with the check above the project owner decides. Values marked final were decided by the project owner; the phone
checks above are still made for them, and if one shows a condition of this rule, a lower value is proposed to the
owner. The values are recorded in this ADR; the evidence stays in the project's decision records.

**Derivation of `MAX_PASSES_PER_IMAGE`.** Passes for a working image of W × H with input w × h: one global pass plus
nx × ny tiles, with nx = 1 if W ≤ w, otherwise ⌈(W − w) / (0.75 w)⌉ + 1 (and ny likewise). The count is largest for
the smallest allowed input, 128 × 128, and the largest allowed image: each side at most 65,535 px and at most
2,147,483,647 pixels (the in-memory limits of ADR 0002 decision 9; file mode is lower, at most 100 MP). An exhaustive
search over all widths gives a maximum of 233,656 passes (at 40,353 × 53,217 px; file mode at most 11,289; a
3,500 × 3,500 image at 128 px needs 1,370). The cap 262,144 (2¹⁸) adds a margin of about 12 %.

**Very low thresholds (plain statement for the documentation).** Very low thresholds slow processing and can mask most
of the photo: almost every output row can become a candidate and then a mask. The threshold is never refused for the
number of candidates (the in-pass NMS grows with the square of the candidate count and is bounded by its work
bound; the merge is spatially indexed). Only at thresholds far below a model's useful range can the detection cap, a
merge bound, the NMS work bound or a mask limit fire; the call then fails safely and visibly, never with a silent
loss. For some models, at very low thresholds almost every output row is a candidate; the per-pass row limit bounds
that work. A threshold that produces more than 65,536 masks for one photo gives `TOO_MANY_MASKS`, whose message
names the mask count and the threshold and says that the threshold is very likely too low for this model
(decision 7). Even within every limit, a call at such a threshold can take long on a phone, so apps run masking off
the main thread and offer cancellation (ADR 0002 decision 8).

### 7. Tools for developers

- **Reports.** `DetectorModel.info` reports the SHA-256 and size of the exact bytes the runtime receives, the IR
  version, the opset imports, the inputs and outputs, and non-blocking warnings. `FaceMasker.modelInfo` adds the runtime version read from
  the runtime itself, the execution provider actually used and the FaceBlur version. The library never logs either.
- **Warnings.** `ModelInfo.warnings` and the `warnings` of `Detection`, `MaskResult` and `FileResult` list
  non-blocking warning codes; they never stop loading, creation or a call (decision 3 says how `SMALL_INPUT` is
  computed).
  0.1.0 set: `SMALL_INPUT`, with the developer text "Medium-size faces may be missed between the tile passes and the
  whole-photo pass; measure with faceblur-eval." The demos' "Check profile" step shows every warning. Warning codes
  follow the same stability rules as finding codes; new codes may be added in any release.
- **Finding codes.** `ModelRejected.findings` and `ModelOutputInvalid.findings` are public contracts with the rules
  of `OutputValidationFailed.findings` (ADR 0002 decision 9): each code at most once, in the order found, never
  empty; the message lists the codes; no path, file name, model content or coordinates; a code is never renamed or
  removed without a CHANGELOG "Breaking" entry; callers must not match the list exhaustively.
  - `ModelRejected`, 0.1.0 set: `EMPTY`, `TOO_LARGE`, `NOT_REGULAR_FILE`, `NOT_ONNX`, `NESTING_TOO_DEEP`,
    `EXTERNAL_DATA`, `INPUT_COUNT`, `INPUT_TYPE`, `INPUT_SHAPE`, `OUTPUT_MISSING`, `OUTPUT_TYPE`, `OUTPUT_SHAPE`,
    `RUNTIME_REJECTED`, `IO_MISMATCH`, `CONTRACT_OUTPUT`, `CONTRACT_VALUES`, `TIMEOUT`.
  - `ModelOutputInvalid`, 0.1.0 set: `RUN_FAILED`, `RUN_TIMEOUT`, `OUTPUT_TYPE`, `OUTPUT_SHAPE`, `NON_FINITE`,
    `SCORE_RANGE`, `BOX_INVERTED`, `BOX_BOUNDS`, `TOO_MANY_ROWS`, `TOO_MANY_ELEMENTS`, `TOO_MANY_DETECTIONS`,
    `TOO_MANY_MERGE_CANDIDATES` (either merge bound of decision 6: candidates or work),
    `TOO_MANY_NMS_PAIR_CHECKS` (the in-pass NMS work bound of decision 6), `TOO_MANY_MASKS` (either mask limit of
    decision 6: count or work).
  - **Actionable `TOO_MANY_MASKS` message.** When the findings contain `TOO_MANY_MASKS`, the message adds, after the
    codes, the number of masks of the plan and the profile's score threshold, and says that the threshold is very
    likely too low for this model and should be checked with faceblur-eval; for the work limit it also names the
    summed working area against the limit. Example (illustrative values): "Model output invalid: TOO_MANY_MASKS.
    80,000 masks at score threshold 0.01 exceed the limit of 65,536: the score threshold is very likely too low for
    this model; check it with faceblur-eval." The count, the areas and the threshold are numbers, not image content,
    so the message rules above still hold. The `findings` list is unchanged (codes only); the Swift facade passes the
    message through.
  - **Actionable `TOO_MANY_NMS_PAIR_CHECKS` message.** In the same style: the message adds the number of box pair
    checks the in-pass NMS of the photo reached when it stopped (one above the limit), the profile's score threshold
    and the limit, and the same advice. Example (illustrative values): "Model output invalid:
    TOO_MANY_NMS_PAIR_CHECKS. 240,000,001 box pair checks of the duplicate removal at score threshold 0.01 exceed the
    limit of 240,000,000: the score threshold is very likely too low for this model; check it with faceblur-eval."
    The documentation mentions this limit and error next to the score-threshold guidance, together with the mask
    limit.
- **`checkProfile`.** An optional helper that never runs automatically:
  - the developer passes their own test image and the face boxes they expect, in pixels of that image;
  - `ExpectedFace` values must be finite with left < right and top < bottom; the list must not be empty
    (`InvalidConfiguration("expectedFaces")`); `minIoU` must lie in (0, 1] (`InvalidConfiguration("minIoU")`);
  - it runs detection (it never writes pixels) and returns every detection, the best IoU for each expected face
    (0 when none overlaps) and whether every expected face was found at `minIoU`;
  - it never logs, and its result goes only to the caller;
  - platform overloads follow ADR 0002 decision 3; `SuspendingFaceMasker`, `AsyncFaceMasker` and the Swift
    `FaceMasker` / `FaceMaskerSync` carry it.

  No test image is shipped in any artifact.
- **Outside the library.** The demo apps' "Check profile" step uses `checkProfile` with the demos' own approved test
  images. A JVM evaluation tool in the repository (faceblur-eval), which is not published, evaluates a model and
  profile on the developer's own annotated images, including a threshold sweep; its ground-truth format, profile file
  and metric definitions are recorded in ADR 0014. FaceBlur publishes no claim from either.

### 8. Platforms

- **Android and JVM.** `DetectorModel` holds the verified bytes once, in a direct `ByteBuffer` that the library
  allocates and never exposes (position 0, limit = size). Each creation passes its own `duplicate()` to
  `createSession(ByteBuffer, options)`, so parallel creations share no buffer position. N parallel creations need the
  one shared buffer plus each session's own working memory. With the ONNX format locked, the runtime parses the
  bytes into its own model, so the buffer can be released with the `DetectorModel` (ASSUMPTION for 1.31.0, checked
  by a test that drops the `DetectorModel` and keeps using the instance).
- **iOS.** The verified bytes reach the runtime through a carrier file (ADR 0010 decision 6), because 1.20.0 loads
  only from a path. The pre-parse of decision 4 runs in common Kotlin **before** any byte reaches the bridge, and it
  is mandatory on every platform. KDoc and the guide say that FaceBlur writes a short-lived, unencrypted copy of the
  model in the app's Caches directory while an instance is created (relevant for proprietary models).
- **iOS runtime pin.** ONNX Runtime 1.20.0 stays the iOS runtime, under these conditions:
  - the pre-parse above (external data, format identifier) and the format lock of step 5;
  - the required SHA-256 (decision 1), checked before the pre-parse (decision 4). With the guidance of
    decision 10, only the model whose hash the developer put in the app, or in a manifest the app has verified,
    reaches the runtime. A model inside the signed app bundle is already covered by code signing, so there the hash
    is defence in depth;
  - the integration guide tells developers to load only models they trust and warns that a hash computed by the app
    from the file it is about to load protects against nothing (decision 10);
  - the release notes keep the known-issue entry for the pinned runtime;
  - **re-open trigger:** the acceptance lapses and is escalated to the project owner when an advisory rated HIGH or
    CRITICAL that is reachable through model loading or inference is published for 1.20.0 or its vendored onnx
    1.16.1 (checked at every milestone and release).

  A newer iOS runtime is adopted only after the measured evaluation planned for the first iOS milestone: memory over
  200 photos on the reference iPhone, a telemetry symbol scan, a network capture, an advisory check of the candidate
  version and the reference-test parity gate (ADR 0009). It is never switched silently.

### 9. Documentation examples

- The integration guide shows the shape of a profile for the two formats. The values are examples: other models need
  their own values, and none is a recommendation. FaceBlur ships no model.

  | | YuNet-layout example | Generic-detections example (box decoding in the graph) |
  |---|---|---|
  | Format | `YuNetLayout` | `GenericDetections`: `boxesOutput = "boxes"`, `boxColumn = 0`, `scoresOutput = "scores"`, `scoreColumn = 1` (softmax pair, column 1 = face), `ROWS`, `XYXY`, `NORMALIZED`, `RGB`, `NCHW`, `PixelScaling(1, [127, 127, 127], [128, 128, 128])`, `nmsInGraph = false` |
  | Input | 640 × 640 | 640 × 480 |
  | Example threshold | 0.5 | 0.5 |
  | Mask expansion minimum | 1.6 / 1.8 | 1.6 / 1.8 |
  | Input tensor | BGR, 0-255, NCHW (fixed by the format) | `input`, float32 [1, 3, 480, 640], RGB, (pixel − 127) / 128 |
  | Outputs | `cls_8/16/32`, `obj_8/16/32`, `bbox_8/16/32` (+ ignored `kps_*`) | `scores` [1, N, 2] softmax, column 1 = face; `boxes` [1, N, 4] XYXY normalised to the 640 × 480 input; N = 17,640; no NMS in the graph |

- The example profiles, with their hashes, live only in `docs/` and in test sources, never in KDoc or DocC that ship
  in sources jars, javadoc jars or the Swift package (the leak check would otherwise trip on them, and it is not
  weakened for them).
- The wording about each model's licence and training data is approved separately before publication.
- The documented examples are compiled by a test, so the docs and the code cannot drift apart. A model is used as a
  test fixture only after its fixture use has been approved; until then the generic-format tests use fixtures
  derived from the reference model (ADR 0009).

### 10. Where the model and its hash come from (documentation guidance; no further API)

- **Recommended default: bundle the model and hard-code its hash.**
  - The model file ships inside the app (Android assets, iOS app bundle).
  - Its SHA-256 is a constant in the app's code.
  - The app then always uses exactly the model the developer measured. It works offline, and a compromised server
    cannot change it.
  - Every code sample in the guide follows this pattern, for example on Android (KDoc samples show the same
    pattern with named constants only and no example values, decision 9):

    ```kotlin
    // The model is in the app's assets; its SHA-256 is a constant in the app's code.
    val bytes = context.assets.open("model.onnx").use { it.readBytes() }
    val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, MODEL_SHA256)
    val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile)
    ```

    On iOS, the bundle URL from `Bundle.main` is passed as `.file(url)`. Example hashes appear only in `docs/` and
    test sources (decision 9).
- **How to get the hash.** Run `shasum -a 256 model.onnx` on the exact file that ships. An app that updates its
  model ships the new hash with each update. The profile values that belong to the model (threshold, input size,
  format) stay in the app's code next to its hash: the hash does not cover them.
- **Advanced, allowed: over-the-air model updates.** The rules:
  - the download and network code lives in the app, never in FaceBlur;
  - model and hash come in a manifest signed by the developer. The app verifies it with a public key built into the
    app before the hash is passed to FaceBlur;
  - every profile value the app takes from the manifest is covered by the same signature;
  - every new model is measured with faceblur-eval before rollout.

  The guide gives the steps in detail, including protection against rolling back to an older signed manifest, key
  custody and what to do when verification fails.
- **Plain warning (guide and KDoc of `expectedSha256`).** The hash is only as trustworthy as its source. A hash that
  the app computes from the file it is about to load, or that it downloads unsigned from the same server as the
  model, protects against nothing. The hash proves that FaceBlur loads the file the app named. It does not prove that
  the model is safe or good.
- **Demos.** The demos ship no model, so their "Choose model" step keeps the file picker, with a confirmation step:
  1. After picking, the demo copies the file into app-private storage under the size cap.
  2. It computes the SHA-256 with the platform's digest in demo code (the demos have no FaceBlur helper for this,
     decision 1).
  3. It shows the file name, the size and the full hash, and asks "Use this model?".
  4. Only on confirmation is the hash passed into the `DetectorProfile` and `DetectorModel.load` called. Cancel
     deletes the copy and loads nothing.

  The screen says that the demo computed the hash from the picked file, for evaluation only, and that an app should
  hard-code the hash of the model it has measured. When the demo starts again, it loads its stored copy with the
  confirmed hash, so a changed copy shows `ModelIntegrity`. "Remove model" deletes the copy and the hash.

## Alternatives considered

**A. Model embedded in the artifacts (ADR 0006).** Pro: no set-up for apps, one known model. Con: FaceBlur would
distribute a model and its licence and training-data questions, and apps could not choose their model. Superseded by
the owner's decision.

**B. Operator allow-list, domain and opset window, and structural limits before the runtime.** Pro: a smaller parser
surface in the iOS runtime. Con: it judges the model rather than the file, it refuses common exports (NMS in the
graph, dynamic shapes), and it must be maintained per runtime version. Rejected under the owner's principle; only the
external-data refusal and the format checks stay, because they address known runtime issues.

**C. A mandatory self-test image in the artifacts.** Pro: turns the most likely silent failure (wrong profile) into an
error at creation. Con: ships an image in every artifact, can refuse a correct exotic model, and still proves only one
image. Rejected; the optional `checkProfile` helper with the developer's own image replaces it, and the residual risk
is documented.

**D. A fixed size cap or a threshold range.** Pro: stricter defaults. Con: model-specific values are the developer's
responsibility; no universal safe range exists across models. Rejected; the size cap is a default the app can change,
and the threshold is validated only as a probability.

**E. `FaceMasker(source, profile, config)` without `DetectorModel`.** Pro: one call fewer. Con: every worker reads and
hashes the file again, and the model report needs a session. Rejected.

**F. Custom decoders or callbacks.** Pro: any output layout. Con: the fail-closed checks of decision 5 must be FaceBlur
code. Rejected; two fixed formats.

**G. Hand the developer's path to the runtime.** Pro: no copy. Con: time-of-check/time-of-use gap, symbolic links,
external-data files next to the model, `.ort` path detection. Rejected; the runtime receives only bytes that FaceBlur
has read and checked.

**H. A walker that reads only the field matching each declared type and the first occurrence of each field.** Pro:
simpler. Con: it can see a different message than the runtime's protobuf parser (merged duplicates, tensors in
attribute fields of another declared type), which is the classic bypass of a pre-parse. Rejected; decision 4 walks
every occurrence and refuses ambiguous encodings.

**I. An optional expected hash (the earlier form of this ADR).** Pro: no hash needed to try a model. Con: an app
that forgets the hash loads any file, including a swapped or corrupted one, and its parser exposure then includes
every file that ends up at the model's location. Replaced by the project owner's decision: the hash is required.

**J. A public hashing helper (for example a function that returns the SHA-256 of a `ModelSource`).** Pro: convenient
for model pickers. Con: it makes the anti-pattern of decision 10 (hash the file you are about to load) the easiest
path, and the recommended patterns do not need it. Rejected; apps that must hash at runtime use their platform's
digest.

**K. The computed hash in `ModelIntegrity`.** Pro: easier debugging. Con: "catch, copy, retry" would defeat the check
in two lines. Rejected; the guide shows how to compute the hash with standard tools.

**L. Always the platform digest, or always the common one.** Pro: one code path. Con: the common code runs the same
everywhere and is tested on the JVM; the platform digests are likely faster on phones. The choice follows the
measurement of decision 4, per platform.

## Consequences

Positive
- No model in any artifact or repository; apps choose their model.
- Broken files and broken output fail before any pixel is written, on every platform, with finding codes that name
  the cause.
- A lower threshold can only add masks (when the call succeeds), pixel for pixel.
- Every model load is bound to a hash that the app states. A swapped, corrupted or truncated file stops at
  `ModelIntegrity` before any parsing. For an app that follows decision 10, an attacker's model never reaches the
  pre-parse or the runtime.

Negative / risks
- Every app must obtain and ship the model's hash, and every model update needs a new hash, through an app update
  or a signed manifest.
- An app can still compute the hash from an untrusted file at runtime. FaceBlur cannot detect this, and then the
  hash protects against nothing. This is why the pre-parse keeps all its limits, and why the guide warns plainly
  (decision 10).
- The hash covers the model bytes, not the profile values; a wrong or tampered threshold is not detected by it.
- Loading reads and hashes up to the size cap before anything is refused for content; iOS hashes again for each
  carrier file.
- A wrong profile can find no faces and report success (decision 4). It is mitigated only by tools and
  documentation.
- Models that filter scores inside their graph lose faces below that cut-off, which FaceBlur cannot see.
- A small model input can miss mid-size faces (decision 3); FaceBlur does not prevent it.
- Very low thresholds slow processing and can mask most of the photo; beyond the validated range the caps make the
  call fail closed (decision 6).
- A face that crosses a tile border can get one or more part masks next to its own mask (decision 3): a mask plan can
  hold more masks than there are faces, and an editing UI that removes a face's mask leaves its part masks unless it
  removes them too. The extra area is small (the slivers only).
- On iOS, models reach ONNX Runtime 1.20.0, so its parser surface is exposed to the app's model file. The pre-parse,
  the format lock and the trusted-model advice reduce this risk but do not remove it. iOS has no run timeout, and
  session creation has no time limit on either platform.
- iOS writes a short-lived copy of the model to Caches during creation.
- About a dozen new public types, each in the ABI dump and mirrored in Swift.
- Tests depend on the reference model's upstream download (cache, clear failure, never skipped).
- Creation needs extra memory for large models (decision 6).

Follow-ups
- ADR 0001 (no embedding; test-model location) and ADR 0008 (no model notices in the artifacts) are revised with
  this ADR.
- The phone measurement of merge time and memory at both merge bounds, and of the in-pass NMS at its work bound,
  which checks the final value of `MAX_NMS_PAIR_CHECKS_PER_PHOTO` (rule for the limits), before the first
  implementation milestone's code freeze; the iOS carrier-file precondition (ADR 0010 decision 6), recorded in ADR 0010 when the iOS story runs.
- Extra passes at intermediate scales for small inputs: candidate for a later version.
- The digest measurement of decision 4 on the computer and on both reference phones, before the code freeze of the
  milestone that ships each platform; the result and the digest chosen per platform are recorded in decision 4.
- Security applies the threat-model changes after acceptance; the risk record for the iOS runtime carries the
  conditions of decision 8.

## Verification

- **Profile:** every bound accepted, the next value outside each bound rejected with the right `parameter`, NaN and
  infinities rejected (Kotlin and Swift); a threshold of 0 or 1 is rejected, and the smallest float above 0 is
  accepted; a `YuNetLayout` input side that is not a multiple of 32 is rejected; `maxModelBytes` 0 and
  2,147,483,640 are rejected.
- **Immutability:** changing the caller's `FloatArray` or `ByteArray` after construction or after `load` returns, and
  a second thread changing a `ByteArray` during `load`, changes neither the profile, nor `ModelInfo.sha256`, nor the
  bytes given to the runtime; the exposed collections reject mutation from Java.
- **Read:** a file of exactly `maxModelBytes` bytes is accepted and one byte more is refused; a raised and a lowered
  cap work; a FIFO (test with a timeout, so a regression fails instead of hanging), a directory and a symbolic link
  as the last component are refused; a file that grows during the read is refused; no message contains the path.
- **Required hash:**
  - The ABI dump shows `DetectorProfile.expectedSha256` as a non-null `String`, and no constructor overload omits
    it. The builder's `build()` without a hash throws `InvalidConfiguration("expectedSha256")`. The Swift
    initialiser has a non-optional parameter without a default.
  - Upper- and lower-case hashes are accepted and read back in lower case. An empty string, 63 and 65 digits, a
    non-hex digit, a `sha256:` prefix and surrounding whitespace are refused with
    `InvalidConfiguration("expectedSha256")` (Kotlin and Swift).
  - Every `DetectorProfile` in tests, samples and docs carries a hash. A grep gate allows a placeholder of 64 equal
    digits only in negative tests.
- **Pin and order:**
  - A one-byte change (first, middle and last byte) and a file one byte shorter, each loaded with the original's
    hash, throw `ModelIntegrity`. Counting seams show zero pre-parse calls and zero session creations; on iOS the
    fake bridge records no carrier file and no `open`.
  - A file over the cap whose hash also differs gives `TOO_LARGE` and is not hashed (digest seam counter at 0).
  - Within the cap, a file that both mismatches and holds external data gives `ModelIntegrity`, not
    `EXTERNAL_DATA`.
  - A matching malformed file gives its pre-parse finding; a matching well-formed file reaches the runtime.
  - The iOS carrier file, changed after it is written (test seam), gives `ModelIntegrity` and no `open`.
  - No `ModelIntegrity` message contains either hash (checked with distinctive hashes).
- **Digest:**
  - The common implementation equals the NIST examples ("", "abc", the 448-bit two-block message, 1,000,000 × "a").
    It also equals the JDK's `MessageDigest` for every length from 0 to 1,024 bytes and for a 50 MiB buffer.
  - The padding length is computed with 64-bit arithmetic: a unit test of the length function at sizes near
    2³¹ − 9 bytes, without allocating them.
  - The hashing step allocates no second buffer of the model's size (allocation check on the JVM).
  - If a platform digest is used, it gives the same values as the common implementation on the same inputs, on
    that platform (Android host or device, iOS simulator).
  - The digest time for 50 MiB is MEASURED on the computer and on both reference phones (decision 4 rule).
- **Pre-parse (models generated at test time, never committed):**
  - one model with external data at each position of decision 4 step 4 item 2, plus one nested combination (an
    external tensor in a `Constant` inside an `If` branch inside a model-local function), each refused with
    `EXTERNAL_DATA`; on iOS the fake bridge records no `open` call;
  - an `external_data` entry with `data_location` 0; `data_location` = 2³² + 1; an unknown enum value; a tensor in an
    attribute field that does not match the declared type; an external-data initializer in a second `graph`
    occurrence: each refused;
  - a known field with the wrong wire type, a group wire type, truncated and over-long lengths: `NOT_ONNX` without a
    crash; nesting of 101 levels: `NESTING_TOO_DEEP`;
  - a polyglot (valid ONNX for the walker, ORT identifier at bytes 4-7) and an ORT-format conversion of the reference
    fixture: refused by the pre-parse; through an internal seam on the JVM runtime 1.31.0 only, the runtime with the
    locked options refuses an ORT-format buffer (never run against 1.20.0 on a device);
  - wrong input count, type and shape, missing outputs, a column index ≥ K: their codes;
  - the description limits of decision 4 step 4 item 8 (outputs, opset domains, dimensions): accepted at each limit and
    `NOT_ONNX` one beyond it; many graph inputs with equally many initializers are matched by name and only two
    non-initializer inputs are kept, and the memory retained after the walk stays below the file size;
  - the field table equals the union of the two vendored `onnx.proto` files;
  - **differential oracle (JVM):** for every generated and fuzzed model that a full protobuf parser (test-only,
    generated from the pinned `onnx.proto`; licence checked) parses and in which a reflective walk finds a non-zero
    `data_location` or an `external_data` entry, the Kotlin walker has refused; the walker is also fuzzed for
    crash-freedom (workflow `fuzz.yml`) with a fixed seed and a logged random seed, on every push that changes the
    walker, the exception it refuses with, its tests, the test-model sources or the harness, and on manual dispatch;
    a random-seed fuzz run is part of every release. It has no schedule.
- **Runtime:** a model with an operator from an unknown domain gives `RUNTIME_REJECTED`. The grep gate fails on any
  configuration key outside the allow-list and on every call in the "never" column; a planted key makes it fail.
- **Contract and output:** fake runners give wrong shapes, a float64 output, NaN, Inf, a score of 1.01, a score of
  −0.01, a box with x1 = x2, an inverted box, a box with a corner at −2.01 W or 3.01 W and one 5.01 W wide (and
  the same for H), boxes exactly at these bounds (accepted, clipped and masked), and row, element and detection counts
  above each cap; 65,536 candidates above a low threshold are not refused for their number. Each gives its finding code with the image or target unchanged. A slow fake run
  gives `RUN_TIMEOUT` on Android/JVM, and the next call on the same instance succeeds. A planted pass count above
  `MAX_PASSES_PER_IMAGE` (test seam) throws `ImageTooLarge` before the first pass; the pass formula's maximum over the
  size limits equals the derivation in decision 6.
- **Merge:** the spatially indexed merge equals the pairwise merge on the reference fixtures and on random and dense
  candidate sets; `MAX_MERGE_CANDIDATES_PER_PHOTO` + 1 candidates give `TOO_MANY_MERGE_CANDIDATES` with the image
  unchanged. The work is bounded by operation count, never by wall-clock time (a timing assertion is flaky across
  machines): through a test seam, the units of work (decision 3) are counted; a dense face-like synthetic set
  examines at most a small fixed number of kept boxes per candidate (the number is fixed in the test); the
  adversarial set described in decision 3 shows the quadratic growth of the count (doubling the set about quadruples
  it). With the bound lowered through the seam to a merge's exact work count, that merge completes with the same
  result; one unit less gives `TOO_MANY_MERGE_CANDIDATES`, and through the masker the image is unchanged (negative
  control: a merge without the work count runs to the end). Cancellation inside one long candidate scan is read
  within 65,536 units of work.
- **In-pass NMS work:** one unit per pair check, summed per photo over all passes (also when only the global pass
  runs) and reset for each photo. With the bound lowered through the test seam to a photo's exact count, the call
  completes with the same result; one unit less gives `ModelOutputInvalid` with `TOO_MANY_NMS_PAIR_CHECKS` and the
  image or target unchanged. Cancellation is read within 65,536 units. With `nmsInGraph = true` nothing is counted.
  The `TOO_MANY_NMS_PAIR_CHECKS` message contains the code, the pair checks reached, the profile's score threshold,
  the limit and the advice to check the threshold with faceblur-eval, and no coordinate, path or model content.
- **`TOO_MANY_MASKS` message:** for the count limit and for the work limit, the message contains the codes, the mask
  count, the profile's score threshold and the advice to check the threshold with faceblur-eval, and no coordinate,
  path or model content; `findings` holds only the codes.
- **Edge faces:** boxes partly outside the image are clipped and masked; a box wholly in the padding is dropped;
  tested on every format.
- **Union property:** masks(t) lie inside the union of masks(t′) for t′ < t, checked pixel by pixel with the
  independent pixel oracle, on the reference fixtures and on random detections, for calls that succeed: for both
  shapes, at the default expansion and at 3.0, with candidate sets that include faces cut in half and in quarters by
  tile borders, thin cut strips and merged faces at the image edges. Negative control: the same merge without part
  masks fails the pixel check on a cut-half case. `Detection.faces` holds one box per merged face; `toMaskPlan()`
  contains the part masks.
- **Non-square input:** tiling with a 640 × 480 input covers every pixel and maps boxes back exactly.
- **Effective expansion:** the profile minimum and `MaskingConfig` combine per axis for detected faces;
  `toMaskPlan()` reproduces the instance's own masks; `Mask.manual` keeps the geometry it is given.
- **Leak check:** every published artifact is scanned after decompressing every entry recursively (AAR →
  `classes.jar`, klib archives, the XCFramework zip → static library, sources and javadoc jars, the Swift package
  archive). Signals: three 64-byte windows of the reference fixture and of each derived fixture, raw and base64 at all
  three alignments; the fixtures' SHA-256 hex; entry names ending in `.onnx` or `.ort`. A planted copy inside a
  deflated nested entry makes the check fail (negative control).
- **`checkProfile`:** a correct profile finds the expected faces on the reference fixtures (`allFound = true`); the
  generic fixture declared with a wrong box format (centre instead of corner form) gives `allFound = false` and still
  no error, which is the documented residual risk; invalid `ExpectedFace` values and `minIoU` are rejected; the helper
  writes no pixel.
- **Warnings:** a profile whose smaller input side is 383 px gets `SMALL_INPUT` in
  `ModelInfo` and still loads, creates and masks; one at 384 px gets none. With an input side that is fine at
  3,500 px, a call whose working long side is 8,000 px reports `SMALL_INPUT` in its result when the side is below
  the scaled gap side, and a 3,500 px call does not.
- **Reports:** `info.sha256` equals an independent hash of the loaded bytes and `profile.expectedSha256`; a recording
  runtime seam shows that the bytes the runtime receives have that hash; `modelInfo.runtimeVersion` equals the
  pinned version of the platform; neither report appears in a log capture of load and creation.
- **Docs:** the documented profiles and the bundled-model samples of decision 10 compile in a test.
- **Demos (UI tests on the emulator and simulator, with a test model placed by the harness):**
  - the full hash is shown before anything is loaded;
  - Cancel leads to no load call and leaves no copy;
  - confirming passes exactly the hash shown;
  - a stored copy changed between two starts shows `ModelIntegrity`.
