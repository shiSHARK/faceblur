# 0014. faceblur-eval: ground-truth format, profile file and metric definitions

Status: Accepted (2026-11-03; records the implementation on `main`; accepted after code review)
Date: 2026-11-03

## Context

FaceBlur ships no detection model (ADR 0012). The app developer chooses the model, writes the `DetectorProfile` that
describes it and chooses its score threshold, and is responsible for all three. A wrong profile (colour order, pixel
scaling, box format, coordinate space, a threshold too high for the model) can find no faces while creating the
masker and masking still report success; FaceBlur cannot see this from the model file or its output. ADR 0012
decision 7 therefore gives developers two tools to check a setup:

- `FaceMasker.checkProfile` (FR-MA-011): a library function for one test image and the faces the developer expects
  in it;
- faceblur-eval (DX-MA-001): a JVM command-line tool in this repository, not published, that evaluates a model and its
  profile on the developer's own annotated photos and sweeps score thresholds.

Both exist on `main`. The files a developer writes for faceblur-eval (the ground truth and the profile) and the
numbers it reports are contracts that developers rely on when they choose a model and a threshold, so they are
recorded here, from the code: `faceblur/src/jvmTest/kotlin/io/github/shishark/faceblur/eval/` (`EvalInputs.kt`,
`EvalMetrics.kt`, `ThresholdSweep.kt`, `FaceblurEval.kt`, the tool's `README.md`) and
`faceblur/src/commonMain/kotlin/io/github/shishark/faceblur/ProfileCheck.kt` / `FaceMasker.checkProfile`.

Forces: results go to the developer only and FaceBlur publishes no claim from them; photos and their file names can
be confidential, so the tool must not leak them into logs or reports; a privacy measure must fail closed (a photo the
tool could not process must never improve a score); the sweep must give the same numbers as separate full runs.

## Decision

### 1. Placement and run rules

- The tool is test-source code of the `:faceblur` module (`jvmTest`, package `io.github.shishark.faceblur.eval`,
  main class `FaceblurEval`). It is in no published artifact. It runs through the Gradle task
  `:faceblur:faceblurEval` (`--args="..."`; heap through `-Pfaceblur.eval.heap`, default `4g`). It is not part of
  `check`; CI runs only a smoke run on the repository's public fixtures (`scripts/eval-smoke.sh`), which checks that
  the task starts (with the telemetry variable set by the task), prints the contract report and the totals, writes
  JSON totals that list exactly the requested thresholds plus the profile's and cover every listed photo at every
  threshold with no failed photo, and prints no fixture file name. These are structure checks; no figure is checked
  there, and the metrics have their own unit and command-line tests.
- No network code (tested by a source scan). The Gradle task sets `ORT_DISABLE_TELEMETRY=1`; started any other way,
  the tool refuses to start (exit code 2) unless that variable is exactly `1`, because ONNX Runtime can send its
  start-up event before code could switch telemetry off.
- Confidentiality of inputs: no file name is printed unless `--show-names` is passed; errors in the ground truth and
  unreadable photos are named by their index (from 1), never by file name; unexpected faults print only the exception
  class, never its message or stack trace (they can contain paths); the JSON totals contain no file name. Images are
  written only with `--overlays DIR` (the masked photo with the face boxes drawn: green protected, red missed, grey
  ignored, at the profile's threshold, named `overlay-NNNN.png` by index).
- Exit codes: 0 done; 1 model refused at loading or masker creation, tool fault (a replay mismatch, decision 4) or
  unexpected fault; 2 usage or input error (including a profile refused by the library's constructors) and a missing
  or wrong telemetry variable.
- The tool loads the model through the public `DetectorModel.load` with the profile, after installing the JVM model
  reader through the library's internal runtime hook. It creates its maskers through the internal test seam
  `FaceMasker.forTest` (decision 4), which runs the library's own creation steps (session, runtime view of the inputs
  and outputs, contract run) with the default `FaceMaskerConfig`, a shared replaying runtime and the threshold as
  minimum score, as the public constructor does with the profile's threshold. It evaluates the detections and the
  mask plan that the library itself produces; it has no detection or masking code of its own.

### 2. Ground-truth file: format `faceblur-gt`, version 1

```json
{ "format": "faceblur-gt", "version": 1,
  "images": [
    { "file": "photo1.jpg", "width": 4032, "height": 3024,
      "faces": [ { "x": 100, "y": 200, "w": 80, "h": 100 },
                 { "x": 900, "y": 150, "w": 40, "h": 50, "ignore": true } ] },
    { "file": "photo2.png", "faces": [] } ] }
```

- The root is an object with `"format": "faceblur-gt"` and `"version": 1` (the number one; any other value is
  refused) and an array `images`.
- Each image: `file` is required, a non-empty path relative to the photo folder; it must not start with `/` and has
  no `..` segment (split at `/` and `\`); each `file` value appears at most once, compared as written (literal
  text: `a.jpg` and `./a.jpg`, or two spellings of one name on a case-insensitive file system, are not recognised as
  the same photo; a comparison of the resolved canonical paths is a planned follow-up). A path that resolves
  outside the folder after following links, or that is not a regular file, is treated as an unreadable photo
  (decision 5).
- `faces` is required (an empty array for a photo without faces). Each face has finite numbers `x`, `y` (top-left
  corner), `w`, `h` with `w > 0` and `h > 0`, in pixels of the photo as displayed (JPEG orientation applied), origin
  top-left. `ignore` is optional; only the JSON value `true` marks an ignored face (for example a face on a poster):
  it counts neither as found nor as missed, and a detection on it is no false mask.
- `width` and `height` are optional non-negative integers: the size the boxes refer to. When given (greater than 0)
  and different from the decoded size, the boxes are scaled per axis (x and w by decoded width / `width`, y and h by
  decoded height / `height`). Absent or 0: the boxes are used as they are.
- Every photo that is to be evaluated is listed, also photos without faces; photos of the folder that are not listed
  are not read.
- Reader rules of version 1: keys that the format does not define are ignored; when a key appears twice in one
  object, the last value counts.
- Versioning: a change that a version 1 reader would misread (a new required key, a changed meaning or unit of an
  existing key) needs `"version": 2` and a reader that refuses what it does not understand. An optional key whose
  absence keeps the version 1 meaning may be added to version 1. Changes are listed in the CHANGELOG.

### 3. Profile file

The values of a `DetectorProfile` as JSON. It has no format marker or version of its own: it follows the public
`DetectorProfile` API of the same FaceBlur version, whose changes are governed by the ABI dump and the CHANGELOG
(ADR 0002). Every value is checked by the library's own constructors; a value they refuse is an input error
("profile refused", exit 2).

```json
{ "format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
  "expectedSha256": "<64 hexadecimal digits>", "minMaskExpandX": 1.6, "minMaskExpandY": 1.8 }
```

- Required: `format` (`"YuNetLayout"` or `"GenericDetections"`), `inputWidth` and `inputHeight` (integers),
  `scoreThreshold` (number), `expectedSha256` (string). Optional: `minMaskExpandX`, `minMaskExpandY`; when absent,
  the library's defaults apply.
- `"GenericDetections"` adds an object `generic` with the fields of `DetectionFormat.GenericDetections`:
  `boxesOutput` (string), `boxColumn` (integer), `scoresOutput` (string, or null or absent for no separate score
  output), `scoreColumn` (integer), `rows` (`ROWS`, `COLUMNS`), `boxFormat` (`XYXY`, `XYWH`, `CXCYWH`),
  `coordinates` (`INPUT_PIXELS`, `NORMALIZED`), `colorOrder` (`RGB`, `BGR`), `tensorLayout` (`NCHW`, `NHWC`),
  `scaling` (an object with `divisor` and the arrays `mean` and `std`) and optionally `nmsInGraph` (only the JSON
  value `true` sets it).
- Enumeration values are the exact, case-sensitive names of the Kotlin enum constants. Keys that are not defined
  here are ignored.

### 4. Threshold sweep

- The thresholds are the values of `--thresholds` plus the profile's `scoreThreshold`, without duplicates, in
  ascending order; each must lie in (0, 1).
- One masker per threshold, each created through the library's test seam with that threshold as the minimum score
  and every other setting of the profile and the default `FaceMaskerConfig`. All maskers share one runtime session.
- Per photo, the masker of the largest threshold runs the model and records, for every detector pass, the SHA-256 of
  the input tensor (over the float bits) and the pass outputs. Every other threshold then runs through the library's
  unchanged decoding, in-pass NMS, mapping and cross-pass merge, with the stored outputs returned in order instead of
  running the model. The model outputs and the passes of a photo do not depend on the threshold, so each result
  equals a full run with that threshold in the profile (tested against such runs).
- A replay that sees a different input, asks for more passes than were recorded, or (on success) leaves recorded
  passes unused is a fault of the tool, never a result: the run stops with exit code 1 and reports no numbers.
- If the run at the largest threshold fails with a `FaceMaskException`, there is no complete recording; the other
  thresholds then run the model themselves. `FaceMaskException.Internal` (a fault of the library or of the tool)
  stops the tool; every other `FaceMaskException` is a result at that threshold (decision 5, "failed photo").

### 5. Metric definitions

Per photo and threshold the tool takes, from the library's `Detection`: the detections (`Detection.faces`, the final
merged boxes) and the masks of `Detection.toMaskPlan()` (the masks the library draws with the default `MaskingConfig`,
with their expansion and part masks; an app with larger expansions masks more). "Faces" below are the
ground-truth faces that are not ignored.

- **IoU** of two boxes: intersection area / union area, 0 when they do not overlap or the union is empty; boxes are
  floating-point rectangles with right and bottom edges exclusive.
- **Matching (one-to-one, greedy).** Every pair of a detection and a face with IoU > 0 and IoU at least the match
  IoU (`--match-iou`, default 0.5, in (0, 1]) is a candidate pair. Pairs are taken in descending IoU order, ties
  broken by detection index and then face index (ascending); a pair is accepted when neither its detection nor its
  face is taken yet. **Matched detections** (true positives) = accepted pairs.
- **False mask.** A detection that is not matched and does not hit an ignored face. It hits an ignored face when they
  overlap and either their IoU is at least the match IoU or at least max(match IoU, 0.5) of the detection's own area
  lies inside the ignored box. A second detection of an already matched face, and a detection that overlaps a face
  with an IoU below the match IoU, are false masks.
- **Protected face.** A grid of 16 × 16 sample points is placed at the cell centres of the face box (point (i, j)
  at left + (i + 0.5) · width / 16, top + (j + 0.5) · height / 16). A point is covered when it lies inside the
  opaque part of any mask of the plan (the mask's normalised radius at that point is at most 1: Euclidean for
  ellipses, Chebyshev for rectangles; the outward feather is not counted). The face is protected when the covered
  fraction is at least `--coverage` (default 0.8, in (0, 1]). An empty box has coverage 0. Protection is judged on
  the masks, not on the detection boxes: a face can be protected by a mask of a detection that does not match it.
- **Failed photo** (a `FaceMaskException` other than `Internal` at a threshold): it counts as evaluated at that
  threshold, all its faces count as missed, and it has no detections and therefore no false masks.
- **Unreadable photo** (listed but missing, not a regular file, outside the folder, without permission, or in a
  format the tool cannot decode, including HEIC, or damaged): it stays in the totals of every threshold, all its
  faces count as missed, it adds no false mask, and it is reported only as `image N: unreadable` and in the count of
  unreadable photos.

Totals per threshold, over all listed photos:

| Total | Definition |
|---|---|
| images | listed photos (failed and unreadable photos included) |
| images with faces | listed photos with at least one face |
| photos fully protected | photos with at least one face of which every face is protected (a failed or unreadable photo never counts) |
| faces | faces of all listed photos |
| protected faces | sum of protected faces |
| matched detections, false masks | sums over the photos that were processed at this threshold |
| failed images, unreadable images | counts as defined above |
| protection | protected faces / faces |
| recall | matched detections / faces |
| precision | matched detections / (matched detections + false masks); detections on ignored faces are excluded |
| false masks per photo | false masks / (images − unreadable images) |

Every ratio is 0 when its denominator is 0. Protection is the privacy measure; recall and precision describe the
boxes. The console table prints, per threshold, protected faces with protection in percent, photos fully protected
of photos with faces, recall, precision, false masks per photo and failed images, and marks the profile's threshold.
The JSON file (`--json`) holds `unreadableImages`, `matchIou`, `coverage` and an array `thresholds` with one object
per threshold, ascending: `threshold`, `images`,
`imagesWithFaces`, `imagesFullyProtected`, `faces`, `protectedFaces`, `truePositives`, `falseMasks`, `failedImages`
and `unreadableImages`. `--per-image` prints, at the profile's threshold, one line per photo by index (faces,
protected, found, false, or the error class); `--show-names` adds the file name.

The contract report printed first contains the model's SHA-256 and size, IR version, opset imports, inputs, outputs
and warnings (`ModelInfo`), the ONNX Runtime version, the execution provider actually used and the FaceBlur version.

### 6. Photo decoding in the tool, and what that means for the numbers

- Photos are read with the JVM's built-in image decoders (supported inputs: JPEG and PNG), with the JPEG orientation
  from the EXIF APP1 segment applied by the tool. HEIC is not read on the JVM: such photos are unreadable photos
  (decision 5). Other formats that the JVM decoders happen to read (for example BMP, GIF or TIFF) are evaluated
  without any orientation and are untested.
- Each photo is evaluated at its full decoded size through the in-memory path (`detect`), within the in-memory size
  limits of ADR 0002 decision 9. The file-mode pixel limit and working size (`FileOptions.maxDecodedPixels`,
  `maxLongSide`, ADR 0005) do not apply. An app that uses file mode with a smaller working size detects on a
  downscaled image, so its results can differ from the tool's; developers who depend on that configuration
  downscale the photos and the ground truth themselves (`width`/`height` scale the boxes).
- The JVM runtime uses the CPU execution provider (the desktop package has no XNNPACK); Android uses XNNPACK with
  CPU fallback and iOS its own runtime build. Small numeric differences between providers can change detections
  near the threshold. The contract report names the provider used.

### 7. `checkProfile` (library function, for comparison)

- Input: one image, a non-empty list of `ExpectedFace` (finite edges, left < right, top < bottom, in pixels of that
  image) and `minIoU` in (0, 1] (default 0.5); errors are `InvalidConfiguration("expectedFaces")` and
  `InvalidConfiguration("minIoU")`.
- It runs `detect` (never writes pixels, never logs) and returns every detection, for each expected face the best IoU
  with any detection (0 when none overlaps; boxes with exclusive right and bottom edges) and `allFound` = every best
  IoU is at least `minIoU`.
- Unlike faceblur-eval it does not match one-to-one (one detection can serve two expected faces), it judges boxes and
  not masks, and it reports no false masks: it is a quick check that the profile finds known faces, not an
  evaluation.

## Alternatives considered

- **Publish the tool as an artifact or a separate CLI download.** Pros: easier to start. Cons: a published tool is a
  supported product with its own release, signing and notices; it would also invite reading its numbers as claims.
  Rejected for 0.x; the tool stays in the repository.
- **An existing annotation format (for example COCO or WIDER FACE text files).** Pros: existing tooling. Cons: no
  "ignore" marker per face in a simple form, no display-orientation rule, and COCO needs category and annotation
  tables for a single class. Rejected; a small format with an explicit orientation and scaling rule is easier to
  write correctly. Converters can be written outside the library.
- **A versioned profile file.** Pros: independent evolution. Cons: a second schema next to the public API that can
  drift from it. Rejected for 0.x; the file mirrors the constructors and their checks.
- **Rerun the model per threshold.** Pros: no replay logic. Cons: N model runs per photo. Rejected; the replay is
  exact (decision 4) and checked against reruns, and a mismatch stops the tool.
- **Count failed or unreadable photos as skipped.** Pros: a "cleaner" ratio. Cons: a photo the library could not
  process would improve the score; this hides failures. Rejected (fail closed).
- **Measure protection by box IoU only.** Pros: simpler. Cons: says nothing about the opaque masks that are actually
  drawn. Rejected; protection samples the masks, recall and precision still describe the boxes.

## Consequences

- Positive: developers can choose a model and a threshold with evidence on their own photos, without any photo,
  name or result leaving their machine; the sweep costs one model run per pass and photo; failures lower the scores
  instead of hiding.
- Negative: HEIC photos must be converted to JPEG or PNG first; the tool evaluates at full size on the JVM CPU
  provider, which can differ from an app's file-mode working size and from the phone providers (decision 6); the
  whole decoded photo is held in memory (heap option).
- The ground-truth format and the profile file are developer-facing contracts: changes follow decisions 2 and 3 and
  appear in the CHANGELOG.
- Planned follow-up: the duplicate check of decision 2 compares the resolved canonical paths.
- Follow-ups (candidates, not decided): an option that evaluates at the file-mode working size; an option for the
  `MaskingConfig`; malformed JSON (not well-formed) in the ground truth or the profile is, as the code reads today,
  reported as an unexpected fault (exit 1, exception class only) rather than as an input error that names the
  position (exit 2), and has no test; a version 2 of `faceblur-gt` only when a field must change meaning.

## Verification

- `EvalMetricsTest`: counts equal hand-computed values; totals and ratios are exact; the ground-truth format and its
  errors name no file; the profile file gives the same profile as the constructors and its type errors are input
  errors; JPEG orientation is read and applied; unreadable photos stay in the totals with their faces missed; the
  telemetry variable must be exactly `1`; the tool has no network code.
- `EvalSweepTest`: each threshold equals a full rerun and the model runs once per pass; a threshold that fails is
  reported and the others stay exact; a replay mismatch through a masker stops the sweep; the replay refuses anything
  but the recorded passes.
- `FaceblurEvalCliTest`: counts are the known values and no file name is printed; a default run writes nothing and
  an unreadable file is never named; profile errors are input errors without a stack trace; names only when asked,
  usage on bad arguments.
- `scripts/eval-smoke.sh` in CI on public fixtures (task start, telemetry variable set by the task, output sections,
  no file names).
- `checkProfile`: the tests listed in ADR 0012 Verification (`ProfileCheckJvmTest`).
