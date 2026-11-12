# 0009. YuNet reference test: a strict gate in two tiers, plus a resampling check

Status: Accepted (the fixtures are a precondition of the Verification, not of the decision). The 2026-11-02 revision
for the developer-supplied model (reference model as an internal test fixture, generic-format tier) was accepted on
2026-11-02 after code review and security review.
Date: 2026-11-01 (revised 2026-11-01: no band in the strict tier; a-priori score bound; one-to-one matching in tier 3;
borderline pairs always IoU-gated; JVM run of the platform rules in every CI run; later the same day: reference
boxes clamped to the image for the comparison; after review: the library side is asserted, never clamped, by the
test, the clamping control is tied to a gated border-crossing fixture, and the fixture description matches the
committed set; after the final review: exact-value clamp tests replace one-pixel mutations that the IoU bar cannot
resolve; 2026-11-02: FaceBlur ships no model, so the reference model is an internal test fixture loaded through the
public API (ADR 0012), and a generic-format parity tier is added)

## Context

The library's YuNet pre/post-processing (letterbox, BGR NCHW tensor, decoding, NMS) must reproduce OpenCV's
`cv2.FaceDetectorYN`, the reference implementation, with "box parity on every platform". The test is the regression
gate for any change of runtime, runtime version, decoder or reference fixture.

FaceBlur ships no model; the app supplies it (ADR 0012). This test is therefore an **internal test of the library**:
it checks FaceBlur's preprocessing, decoders, tiling and runtime integration with one known model. It makes no
statement about any model an app chooses. The reference model is a model in the YuNet layout,
a **test fixture**: the maintainer's local fixtures (a folder outside every repository) supply it, the build
checks the SHA-256 against the pin kept there, and keeps a copy in a git-ignored build directory. It is never
committed, never cached outside the build's own fixture cache and never part of a published artifact (the artifact
leak check of ADR 0012 Verification proves this). The tests load it **through the public API**,
`DetectorModel.load(ModelSource.fromFile(...), profile)`, with the documented YuNet example profile
(`YuNetLayout`, 640 × 640, score threshold 0.5, mask expansion 1.6 / 1.8, the pinned SHA-256 as `expectedSha256`).

The benchmark app's test (being extracted) decodes four JPEG photos with the JVM decoder, downscales them with the
JVM resampler to at most 640 px, runs our side at score threshold 0.45, and passes when every reference box has a
best match with IoU ≥ 0.9 (not one-to-one) and no box of ours with score ≥ 0.55 is unmatched. It does not assert
equal counts. On its fixtures the measured worst IoU is 0.9913 and the largest score difference 0.0044; the JPEG
decoder and resampler differ from OpenCV's by about one grey level, which moves borderline scores; two reference
boxes have scores below 0.5044. Regenerating the reference data with the unmodified script reproduced it to float
noise (smallest IoU 0.99985), so the reference side is stable.

Project decisions: the library's test is stricter than the benchmark's (equal box count at the reference threshold,
IoU ≥ 0.99 per matched box, one-to-one matching); the strict deterministic tier on lossless input has **no** score
tolerance band; platform tiers may use a borderline-score band whose bound is fixed in the test in advance and is
never derived from a run of the code under test (a defect must not widen its own tolerance); committed images with
faces are limited to historic public-domain photographs taken before 1900, never
photographs of living people, so the reference images are regenerated from such images, registered before commit.
The margin subset, the candidate floor and the fixed δ below are the project's agreed
reading of the decision on the strict tier, and requirements and stories must not be weaker than this ADR.

## Decision

### Fixtures and reference data

- New fixture set: public-safe images registered in the data register, each with its source and licence: crops of
  historic public-domain photographs (taken before 1900). `testdata/pd/MANIFEST.json` records how every crop was derived
  from its source. Tests that need other images run in the maintainer's local gate; those images are not in the public tree. Two groups:
  - **Lossless group**: PNG images (sRGB, no gamma or colour-profile chunks that a decoder could apply) with long
    side ≤ 640 px, so neither side resamples; many faces of varied size, pose and contrast.
  - **Large group**: a few images with long side ≥ 2,000 px that exercise resampling and tiling.
- `tools/yunet_reference_pd.py` (OpenCV `FaceDetectorYN` on the lossless fixtures; records OpenCV version, model
  SHA-256, score and NMS thresholds)
  generates `testdata/yunet_reference.json`; the ml-engineer runs it, and the output is committed only after its
  data-register row is approved. The JSON records every OpenCV **candidate** down to a candidate floor of 0.3, not
  only the boxes ≥ 0.5, so that a box near the threshold can be paired across the two sides. (With greedy NMS a box
  is suppressed only by a higher-scored box, so the boxes ≥ 0.5 are the same at floor 0.3 as at threshold 0.5,
  provided no top-K cap cuts the candidate list. The script keeps OpenCV's `top_k = 5000`, as the benchmark's
  script does; it records the candidate count per image and fails if any image reaches the cap; the library side
  has no candidate cap at all, only the per-pass row limit and a validated detection cap that raise an error,
  ADR 0012 decision 6. A unit test checks the floor-0.3 and threshold-0.5 equality on both sides.)
- One constant, fixed now in the test source and in this ADR: **δ = 0.005** (score units). It was chosen before any
  run of the library: it is above the largest score difference the benchmark ever showed against OpenCV, and that
  was on lossy, resampled input (0.0044), while the strict tier feeds identical pixels. Changing δ requires a new
  decision; it is never set or adjusted from a run of the code under test.
- **Clamping the reference; asserting the library.** The library returns boxes clamped to the image. This is its
  documented output contract: edges limited to [0, width] × [0, height], landmarks limited to the image, and a box
  that is empty after clamping is dropped. OpenCV reports candidates that cross the image border unclamped.
  - **Reference side.** The JSON keeps the raw OpenCV values. Before matching, the test applies the clamp rule to
    every reference candidate, with an implementation of its own in the test code (not the library's function).
  - **Library side: asserted, never transformed.** The test does not clamp or otherwise change the library's
    output. It asserts that every library box and landmark already lies in [0, width] × [0, height] and that no
    library box is empty, and fails otherwise. A library that stopped clamping, or clamped beyond the image, therefore
    fails the test instead of being repaired by it. A clamp that is off by about one pixel is below what the IoU
    bar can resolve; it is caught by the exact-value tests in Verification, not by the gate.
  This is not a tolerance: it only makes the reference describe a box in the library's documented coordinate
  convention. The IoU bar, δ, the candidate floor and the margin subset are unchanged, and a reference candidate is
  never dropped for any other reason.
  Measured before this rule, on the full public-domain fixture build (a superset of the committed fixtures): the
  unclamped comparison had a worst IoU of 0.883, caused only by candidates crossing the border; with the reference
  clamped, the worst IoU was 0.99999 and no pair fell below 0.99.
  The committed fixtures include at least one image of the margin subset with a border-crossing reference
  candidate scoring ≥ 0.5, which criterion 2 gates in tier 1. It was chosen from the reference JSON only, before any library run on it, and the
  selection rule is recorded with the fixture selection. Without such a candidate the clamping rule would not be
  exercised by the gate.
- **Margin subset** of the lossless group: images in which no OpenCV candidate score lies within δ of the
  threshold, i.e. none in [0.495, 0.505). The subset must contain at least 100 reference boxes ≥ 0.5. Membership is
  computed from the reference JSON only.

### Matching and criteria (used by tiers 1 and 2)

Both sides produce candidates down to 0.3 on the same image. Per image:
1. One-to-one matching of candidates, greedy by descending IoU over pairs with IoU ≥ 0.5.
2. Every matched pair in which either score is ≥ 0.5 − δ (this includes every borderline pair) has IoU ≥ 0.99 and a
   score difference ≤ δ.
3. No unmatched candidate with score ≥ 0.5 on either side.
4. Threshold agreement: in every matched pair, both scores lie on the same side of 0.5.
5. Report (not gated): worst IoU, largest score difference, largest landmark distance, number of band exemptions
   (tier 2).

Criteria 1-4 together imply an equal box count at the threshold 0.5.

### Tier 1: strict, deterministic (JVM, every CI run), no band

Input: the margin subset, decoded losslessly on both sides (identical pixels), no resampling, the reference fixture
loaded through `DetectorModel.load` with the documented YuNet profile (Context). Criteria 1-4 apply with **no exception**. Because the margin subset keeps every reference candidate at
least δ away from 0.5 and criterion 2 bounds the score difference by δ, a pair can only disagree about the threshold
if criterion 2 has already failed; the gate is therefore deterministic and cannot be passed by a score drift.

### Tier 1b: platform rules on the JVM (every CI run)

The whole lossless group (including the near-threshold images) on the JVM, with tier 2's rules below, including the
fixed band. This catches a decoder regression that shows only near the threshold in every CI run, without waiting
for a device run. It needs no extra fixtures.

### Tier 2: platform parity (Android and iOS)

Input: the whole lossless group (including near-threshold candidates, so the band is exercised), through each
platform's runtime (ONNX Runtime Android with XNNPACK; the iOS ONNX Runtime bridge) and pixel adapters (Bitmap from
PNG; ImageIO into the RGBA buffer). Criteria 1-3 apply unchanged, including the δ bound on score differences.
Criterion 4 has one exemption, the borderline band: a matched pair may disagree about the threshold only if the
**OpenCV reference score** of that pair lies in [0.5 − δ, 0.5 + δ). Band membership is read from the reference JSON,
never from a score of the code under test, and δ is the fixed constant above.

Runs: the Android emulator locally at every milestone (in CI only if devops shows it fits the CI budget), the iOS
simulator in the manual macOS job, both reference phones at every milestone and for every runtime change.

### Tier 3: resampling parity (gated at the benchmark's level)

The large group, decoded and downscaled by each platform's own path (JVM, Android, iOS), compared on the global pass
only (an internal test hook returns the global-pass boxes; the reference is OpenCV on the image downscaled with
`INTER_AREA`, as today). Gate: with one-to-one matching (stricter than the benchmark's best-match), every reference
box ≥ 0.5 is matched with IoU ≥ 0.9 and no unmatched box of ours scores ≥ 0.55, i.e. never weaker than the
benchmark's gate. Worst IoU and score differences are reported. Tiling and merging are covered by their own unit
tests, and the file-mode/in-memory agreement by the test in ADR 0002.

### Tier G: generic-format parity (JVM, every CI run; Android and iOS with tier 2)

The `GenericDetections` decoder is gated against the `YuNetLayout` route with real weights, without any new model
or data:

- **Fixture G0.** Built at test time from the pinned reference model by a test-source ONNX writer: the YuNet decode
  is appended to the graph as ONNX operators (score = sqrt(clip(cls) × clip(obj)), grid and stride box decoding), so
  that the model has one output of decoded detections (8,400 rows, in the row order of FaceBlur's YuNet decoder),
  without NMS. The writer checks that the source's initializers and nodes are byte-identical in the output, and the
  output's SHA-256 is pinned next to the source pin; a mismatch fails the build.
- **Variants.** G1 with NMS inside the graph (`nmsInGraph = true`); G2 with RGB, NHWC and 0..1 input scaling; G3 with
  permuted columns and another box format. Each has its own profile.
- **Criteria**, fixed before the first run and never derived from a run of the code under test:
  - G0 rows against the Kotlin YuNet decoder on the same inputs: scores bit-identical, box values within 1 × 10⁻³ px;
  - G0 through the full generic route against the `YuNetLayout` route on the lossless group: equal box count, every
    matched pair IoU ≥ 0.99;
  - G0 through the generic route meets criteria 1-4 of tier 1 against OpenCV on the margin subset;
  - G2 and G3 give results identical to G0;
  - G1 against G0: every difference is explained by the in-graph NMS, and the reason for each difference is asserted;
  - writer mutants (a wrong grid offset, a swapped column) must fail these criteria.
- The derived fixtures are never committed, never cached in CI and never published; they carry a "test fixture, not
  for distribution" description. Any other model enters these tests only after its use as a fixture has been
  approved.

### Failure policy

Any failure in tier 1, 2 or G stops the change and is investigated by the architect and the ml-engineer (with the
owner for a change of the reference fixture or its pin). 0.99 is never lowered, δ is never widened, and the margin subset is never re-selected to
make a run pass; fixture changes go through the data register and are reviewed on their own.

## Alternatives considered

**A. Keep the benchmark's gate (IoU ≥ 0.9, threshold 0.45, many-to-one).** Weaker than the decision of record.
Rejected.

**B. One strict tier on JPEG input with resampling (equal counts, IoU ≥ 0.99, no band).** Pro: closest to real
photos. Con: measured margins (0.0013 above 0.99; borderline scores within the measured score noise) make it flaky
through decoder and resampler differences that are not what the gate is meant to catch. Rejected; resampling is
covered by tier 3.

**C. Reference boxes produced by our own JVM implementation (golden files).** Self-referential; would not detect a
decoder bug. Rejected; OpenCV stays the reference.

**D. A band in the strict tier, sized from the first measured run (δ = 3 × the largest measured difference).**
Pro: adapts to real runtime noise. Con: the gate calibrates itself to the code under test, so a preprocessing defect
that shifts scores would widen its own tolerance. Rejected; δ is fixed in advance and the strict tier has no band.

**E. Compare against the raw, unclamped OpenCV boxes.** Pro: no transformation of the reference. Con: fails on every
gated face (score ≥ 0.5 − δ) that crosses the image border, because of a coordinate convention, not a detection
difference; the only "fixes" would be lowering the IoU bar or excluding border faces, both weaker. Rejected; the
reference is clamped by the library's documented rule.

**F. Clamp the library side in the test as well.** Pro: symmetric. Con: the test would repair the unit under test,
so a library that stopped clamping would pass. Rejected; the library side is asserted, not transformed.

## Consequences

Positive
- The strict gate isolates the decoder and runtime from image I/O, so it can be strict without flakiness.
- No tolerance in any tier is derived from the code under test.
- Platform runtimes (XNNPACK, the older iOS runtime) are held to the same IoU bar and the same score bound.
- Resampler differences are still watched, at the level the benchmark proved.

Negative
- Depends on new synthetic fixtures and their registration before the test can be committed.
- The margin subset needs enough faces away from the threshold (at least 100 boxes), which may need more fixture
  images.
- If a platform runtime's score noise on identical pixels exceeds δ, tier 2 fails and needs an investigation and a
  new decision rather than a quick fix.

## Verification

- The reference fixture is loaded only through the public API; a build without the fixture fails the reference
  test step with a clear message and never skips it.

- The tier-1 test fails when (a) the decoder's anchor offset is changed by one pixel, (b) NMS threshold 0.3 is changed
  to 0.4, (c) one reference box is removed from the JSON, (d) a preprocessing change shifts scores by more than δ
  (for example a mean or channel-order error) (mutation checks run once and logged).
- A unit test on both sides shows that the boxes ≥ 0.5 are identical at candidate floor 0.3 and at threshold 0.5.
- Clamping:
  - A unit test checks the test's clamp rule: a box inside, crossing each edge, fully outside (dropped), and
    landmarks outside.
  - Exact values of the test's clamp rule: for given boxes crossing the left, top, right and bottom edge, the
    clamped edge is exactly 0, 0, `width` and `height`; the other edges are unchanged; landmarks outside come out
    exactly on the limit. This catches a reference clamp that is off by one pixel.
  - Exact values of the library's clamp (library unit test): a box crossing each edge comes out with that edge
    exactly 0, 0, `width` or `height`, and its landmarks exactly on the limits. This catches a library clamp to
    `width − 1`. On the gated border-crossing fixtures below, the library box's crossed edge also equals the
    clamped reference edge exactly.
  - Gate controls tied to two fixtures of the margin subset (they run in the maintainer's local gate), with gated border-crossing reference candidates
    (scores 0.5474 and 0.7644). Their raw-versus-clamped IoU is 0.978 and 0.755, below 0.99, so:
    - a mutation that skips clamping the reference makes tier 1 fail on them;
    - a mutation that removes the library's clamp makes the library-side assertion fail (a raw edge lies outside
      the image).
  - These controls are valid only while those fixtures are committed. The fixture-selection test asserts that the
    reference JSON holds at least one border-crossing candidate ≥ 0.5 in a margin-subset image.
- The margin-subset selection is a pure function of the reference JSON, with its own test.
- Tier 1 and tier 1b in CI on every push; tier 2 logs from both phones attached to each milestone; tier 3 results attached to
  the release record.
