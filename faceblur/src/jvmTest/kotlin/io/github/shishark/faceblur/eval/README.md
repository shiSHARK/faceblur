<!-- SPDX-License-Identifier: Apache-2.0 -->
# faceblur-eval

A JVM command-line tool that measures a face-detection model and its profile on your own annotated photos, so that
you can choose the model and the score threshold with evidence. FaceBlur does not judge your model, profile or
threshold: you are responsible for them, and this tool, `FaceMasker.checkProfile` and the demos' "Check profile" step
help you check them. A wrong profile (colour order, pixel scaling, box format, coordinate space, a threshold too high
for the model) can find no faces while creating the masker and masking still report success.

The tool runs from a checkout of this repository. It is not part of any published artifact.

**Results go to you only. FaceBlur publishes no claim from them.**

## What it never does

- No network code: it uploads nothing and downloads nothing.
- It prints no file name unless you pass `--show-names` (names can be confidential).
- It writes images only when you pass `--overlays DIR`. Overlays contain your photos (masked, with boxes drawn); keep
  them where the photos are allowed to be.

## Run

From the repository root:

```
./gradlew :faceblur:faceblurEval --args="--model model.onnx --profile profile.json --images photos/ \
  --truth ground_truth.json --thresholds 0.3,0.4,0.6,0.7"
```

Relative paths resolve against the directory you start Gradle from; `-Pfaceblur.eval.heap=8g` raises the heap. The
task sets `ORT_DISABLE_TELEMETRY=1` for the tool. Started any other way (main class
`io.github.shishark.faceblur.eval.FaceblurEval` on the `jvmTest` runtime classpath), the tool refuses to start
unless that variable is exactly `1`. Exit codes: 0 done, 1 model refused or tool fault, 2 usage or input error.

Options:

| Option | Meaning |
|---|---|
| `--model FILE` | the ONNX model (required) |
| `--profile FILE` | the profile JSON (required, below) |
| `--images DIR` | the photo folder, JPEG or PNG (required); JPEG orientation is applied |
| `--truth FILE` | the ground-truth JSON (required, below) |
| `--thresholds LIST` | comma-separated thresholds to sweep, each in (0, 1); the profile's threshold is always included |
| `--match-iou X` | IoU at which a detection matches a face (default 0.5) |
| `--coverage X` | fraction of a face box the masks must cover for "protected" (default 0.8) |
| `--per-image` | one line per photo, by index |
| `--show-names` | add file names to the per-photo lines |
| `--overlays DIR` | one overlay per photo at the profile's threshold, named `overlay-0001.png` and so on |
| `--json FILE` | the totals as JSON (no file names) |

## Output

1. The contract report: the model's SHA-256, size, IR version, opsets, inputs, outputs, warnings (for example
   `SMALL_INPUT`), the ONNX Runtime version and execution provider and the FaceBlur version.
2. Per threshold:
   - **protected**: faces with at least the coverage fraction of their box under the opaque part of a mask (a 16 x 16
     grid of sample points over the box);
   - **photos fully protected**: photos whose every face is protected, of the photos with at least one face;
   - **recall**: detections matched one-to-one to a face (greedy, in descending IoU order, at the match IoU) per face;
   - **precision**: matched detections per detection that is not on an ignored face;
   - **false masks per photo**: detections that match no face and no ignored face, per measured photo;
   - **errors**: photos where FaceBlur stopped with an error at this threshold (their faces count as missed).
   - Photos that cannot be read (missing, no permission, HEIC, damaged) stay in the totals: their faces count as
     missed, and the count is printed and written to the JSON. Such a photo is reported as `image N: unreadable`,
     never by name.

The sweep is exact: for each photo the model runs once per detection pass; every other threshold reuses the stored
model outputs through FaceBlur's own decoding, duplicate removal and merge, which gives the same result as a full run
with that threshold in the profile.

## Ground truth (`faceblur-gt`, version 1)

```json
{ "format": "faceblur-gt", "version": 1,
  "images": [
    { "file": "photo1.jpg", "width": 4032, "height": 3024,
      "faces": [ { "x": 100, "y": 200, "w": 80, "h": 100 },
                 { "x": 900, "y": 150, "w": 40, "h": 50, "ignore": true } ] },
    { "file": "photo2.png", "faces": [] } ] }
```

- `file`: path relative to the photo folder (no absolute paths, no `..`); each file at most once.
- Boxes: pixels of the photo as displayed (JPEG orientation applied), origin top-left, `w` and `h` greater than 0.
- `width`, `height` (optional): the size the boxes refer to; boxes are scaled when the decoded size differs.
- `ignore: true`: a face that counts neither as found nor as missed (for example a face on a poster); a detection on it
  is no false mask.
- List every photo you want measured, also photos without faces. Errors in this file name the image by its number
  (from 1), never by its file name.

## Profile

The values of a `DetectorProfile`; every value is checked by FaceBlur's own constructors.

```json
{ "format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
  "expectedSha256": "<64 hexadecimal digits>", "minMaskExpandX": 1.6, "minMaskExpandY": 1.8 }
```

A `GenericDetections` profile uses `"format": "GenericDetections"` and adds:

```json
"generic": { "boxesOutput": "boxes", "boxColumn": 0, "scoresOutput": "scores", "scoreColumn": 1,
             "rows": "ROWS", "boxFormat": "XYXY", "coordinates": "NORMALIZED", "colorOrder": "RGB",
             "tensorLayout": "NCHW", "scaling": { "divisor": 255, "mean": [0, 0, 0], "std": [1, 1, 1] },
             "nmsInGraph": false }
```
