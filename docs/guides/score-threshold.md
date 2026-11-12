<!-- SPDX-License-Identifier: Apache-2.0 -->
# Score threshold: choosing it, checking it, and the per-photo limits

The score threshold (`DetectorProfile.scoreThreshold`, strictly between 0 and 1) decides which of the model's boxes
become masks: a box is kept when its probability is at or above the threshold. Your app chooses it and is responsible
for it. FaceBlur checks only that the value is in range; it does not judge whether it suits your model or your photos,
and it has no recommended value. A model that scores faces differently needs a different threshold, and a model update
can change the right value. Choose it with evidence from your own photos.

- A **higher** threshold masks fewer boxes, so more faces can be missed.
- A **lower** threshold never removes or shrinks a mask of a call that succeeds: every pixel masked at a higher
  threshold is also masked at a lower one. The price is more masks, more false masks on things that are not faces, and
  more work. A threshold far below the model's useful range can hit the limits below.
- If the model filters scores inside its own graph, faces below that cut-off are lost even when your threshold is
  lower, and FaceBlur cannot see this.

Detection can miss faces whatever the threshold.

## Step 1: check the profile on one image

A wrong profile (colour order, pixel scaling, box format, coordinate space, a threshold that is too high) can find no
faces while loading, creating the masker and masking all report success. `FaceMasker.checkProfile` is an optional
self-check: you give it a test image and the boxes of faces you know are in it; it reports the best overlap
(intersection over union) of each expected face with a detection.

```kotlin
import io.github.shishark.faceblur.ExpectedFace
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.PixelImage

/** Call off the main thread. The boxes are pixels of [image], origin top-left, right and bottom exclusive. */
fun profileFindsKnownFace(masker: FaceMasker, image: PixelImage): Boolean {
    val result = masker.checkProfile(image, listOf(ExpectedFace(left = 100f, top = 200f, right = 180f, bottom = 300f)))
    // result.bestIoU holds the best overlap per expected face; result.detections holds every detected box.
    return result.allFound // true when every expected face overlaps a detection with IoU >= 0.5 (the default)
}
```

`allFound = false` means the model and profile did not find a face you know is there: check the profile before you rely
on this setup. `allFound = true` on one image says nothing about other photos. The helper never writes to the image,
never logs, and FaceBlur ships no test image for it; use one you are allowed to use.

## Step 2: measure the threshold with faceblur-eval

faceblur-eval is a command-line tool that runs your model and profile on your own annotated photos and prints, for a
list of thresholds, how many faces are covered, how many masks land on non-faces and how many photos failed. It runs
from a checkout of this repository (it is not part of any published artifact), has no network code, prints no file
names unless you ask, and writes images only when you pass `--overlays`. The results go to you only; FaceBlur
publishes no claim from them.

Prepare a folder with your photos and three files: the model, the ground truth and the profile. Ground truth (`faceblur-gt`, version 1) lists every photo you want
measured, also photos without faces; boxes are pixels of the photo as displayed, origin top-left:

```json
{ "format": "faceblur-gt", "version": 1,
  "images": [
    { "file": "photo1.jpg", "faces": [ { "x": 100, "y": 200, "w": 80, "h": 100 } ] },
    { "file": "photo2.jpg", "faces": [] } ] }
```

The profile file holds the values of your `DetectorProfile` (the same checks apply, including the required hash):

```json
{ "format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
  "expectedSha256": "<64 hexadecimal digits>" }
```

Then, from the repository root (paths are relative to where you start Gradle):

```
./gradlew :faceblur:faceblurEval --args="--model my-eval/model.onnx --profile my-eval/profile.json --images my-eval/photos --truth my-eval/ground_truth.json --thresholds 0.3,0.4,0.6,0.7"
```

The task sets `ORT_DISABLE_TELEMETRY=1` for the tool itself. The profile's own threshold is always added to the sweep
and marked with `*`. The output starts with a contract report (model hash, size, inputs, outputs, warnings, runtime
and FaceBlur versions) and then one row per threshold:

```
threshold  protected        photos fully protected  recall   precision  false masks/photo  errors
0.3        ...
0.5*       ...
```

How to read the columns:

| Column | Meaning |
|---|---|
| protected | faces with at least 80 % of their box under the opaque part of a mask (`--coverage` changes the fraction) |
| photos fully protected | photos whose every face is protected, of the photos with at least one face |
| recall, precision | detections matched one-to-one to a face at IoU 0.5 (`--match-iou` changes it) |
| false masks/photo | masks on things that are neither a face nor an ignored face (a face with `"ignore": true` counts neither as found nor as missed), per photo |
| errors | photos where FaceBlur stopped with an error at that threshold; their faces count as missed |

"Protected" is this tool's coverage measure on your photos; it is not a guarantee that faces in other photos are covered.

Pick the threshold from your own table: lower it until the faces you must cover are covered, and stop before false
masks or errors make the result unusable for your product. Photos that cannot be read stay in the totals and count as
missed; the tool says `image N: unreadable` and never prints a name. Re-run the sweep whenever you change the model or
the profile. The tool's own reference, including `--per-image`, `--overlays` and `--json`, is in the
[tool's README](../../faceblur/src/jvmTest/kotlin/io/github/shishark/faceblur/eval/README.md).

## The limits that fail safely

Two per-photo limits exist so that a threshold that is far too low for the model cannot run for a long time or use a
large amount of memory. They are generous technical limits; exceeding one fails safely: the call throws a
`FaceMaskException.ModelOutputInvalid`, nothing is written, and the image is untouched. FaceBlur never drops boxes or
truncates a list to stay under a limit.

| Limit | Value | Error code |
|---|---|---|
| Masks per photo | 65,536; also the masks' summed working area at most 32 times max(image pixels, 1,048,576), a generous technical limit; exceeding it fails safely | `TOO_MANY_MASKS` |
| Duplicate-removal work per photo (box pair checks inside the detector passes) | 240,000,000 | `TOO_MANY_NMS_PAIR_CHECKS` |

Both errors say what was reached and which threshold was in use, and give the same advice. For example (the second
text is the output of a run with a very low threshold on a synthetic noise image; the first is the text pinned by the
library's tests):

```
Model output invalid: TOO_MANY_MASKS. 65,537 masks at score threshold 0.05 exceed the limit of 65,536: the score threshold is very likely too low for this model; check it with faceblur-eval.
Model output invalid: TOO_MANY_NMS_PAIR_CHECKS. 240,000,001 box pair checks of the duplicate removal at score threshold 0.001 exceed the limit of 240,000,000: the score threshold is very likely too low for this model; check it with faceblur-eval.
```

What to do when you see one:

1. Read the count and the threshold in the message. The threshold is the profile's value for this model.
2. The threshold is very likely too low for this model. Run the sweep in Step 2 with higher thresholds (for example
   `--thresholds 0.3,0.5,0.7`) and pick a value at which no photo fails and the faces you need are covered.
3. Do not publish the original in place of the masked image. The call produced no masked image.

The findings are string codes: treat any `ModelOutputInvalid` as a failure and do not match the list of codes
exhaustively. A plan that you build yourself with more than 65,536 masks, or whose summed working area is too large,
fails earlier with `InvalidConfiguration` (`masks`). When you call `applyMasks`, no detection runs, so only the mask
count and the summed working-area limits apply, and they fail as `InvalidConfiguration` (`masks`).

## Other warnings

- `SMALL_INPUT` (in `ModelInfo.warnings` and `Detection.warnings`): the model's input is small for the working size,
  so medium-size faces may be missed between the tile passes and the whole-photo pass. Measure with faceblur-eval.
- For photos, pass `detect` and `maskInPlace` the image at a long side of at least 3,500 px (or its own size if it is
  smaller) and scale down only after masking; detection quality depends on the face size in pixels.
