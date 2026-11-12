<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur Android demo

A small Kotlin app (Android Views, no extra libraries) that shows file mode end to end: choose a photo with the system
picker, look at the detected faces and the masks the library would apply, keep a mask unmasked or add one with
buttons (no gestures are needed), and save a new JPEG with `FaceMasker.maskFile`. Detection can miss faces: check the
result yourself.

A second screen, "In-memory pipeline", shows the library's in-memory mode for an app that does its own steps around
FaceBlur: decode (orientation applied), `FaceMasker.maskInPlace(bitmap)`, scale down only after masking, draw a
watermark, encode to JPEG once. The result is shown from memory and nothing is saved. It adds no location and shows no
timings. The code is `BitmapPipeline.kt`; the step order and the reason for each step are in its comments.

## Run it

From the repository root, with the repository's Gradle wrapper (JDK 17 and the Android SDK):

```
./gradlew :samples:android:assembleDebug
```

Install `samples/android/build/outputs/apk/debug/android-debug.apk` on a device or emulator with Android 11 or newer.
The library is a project dependency here (`implementation(project(":faceblur"))`); in your own app the one line is
`implementation("io.github.shishark:faceblur:<version>")`.

## The model

FaceBlur ships no model. The demo asks you to choose a model file (an ONNX model with the YuNet output layout and a
640 x 640 input), shows its name, size and SHA-256, and loads it only after you choose "Use this model". The file is
read into memory; the demo keeps no copy. The model, its licence and the score threshold (0.5 here, an example value)
are your responsibility.

## What the demo does and does not do

- It declares no network permission: its manifest removes the two permissions of ONNX Runtime, and the build task
  `checkReleaseMergedManifest` (part of `check`) fails if the merged release manifest holds a network, location or
  storage permission.
- While a photo is on the screen (review, saving, result, and always on the pipeline screen) the window is marked
  secure, so that Android keeps no snapshot of it in the recent-apps list; screenshots of those screens are blocked
  too.
- It stores nothing except the file you save, writes no log of images or coordinates, and keeps the original photo
  untouched: the library applies the masks from the original file when you save, so every edit starts from the
  original pixels.
- The saved file is a new JPEG without the original's metadata.

## Checks

`./gradlew :samples:android:check` runs the unit tests (state machine, texts, screen with Robolectric), ktlint, lint
with warnings as errors, the coverage rule (80 % of the demo's lines) and the manifest check.
