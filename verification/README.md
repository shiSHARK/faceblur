<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur verification build

A separate Gradle build (ADR 0001, decision 3) that checks what the library produces. It never uses
`includeBuild`; modules that consume the library resolve it only from the repository-local Maven directory.

| Module | What it is | Status |
|---|---|---|
| `jpeg-privacy` | Independent JPEG output privacy checker, its planted-defect fixtures and self-test (ST-042) | available |
| `pixel-oracle` | Independent decode-and-compare pixel oracle for the pixel-level privacy proofs, its planted-defect self-test (ST-045) | available |
| `android-snippets` | Standalone Gradle build (not part of `check`): an Android app whose sources are the files of `docs/guides/snippets/android/` (Kotlin and Java), compiled against the library from `build/local-maven`, with the guide's no-network manifest as its manifest. `scripts/check-guide-snippets.sh` runs it (step `guide-snippets` of the build job) | available |
| `android-consumer` | Standalone Gradle build (not part of `check`): a minimal app that gets the library only from `build/local-maven`. `scripts/check-consumer-manifest.sh` builds it release and checks the merged manifest; its Java instrumented test runs file mode of the published library once on a device (masked pixels, metadata-free JPEG) with `scripts/consumer-device-test.sh` (local only, one device, fixtures from `testdata/` and the pinned test model) | available |

## Run

From the repository root, with the repository's pinned wrapper (JDK 17):

```
./gradlew -p verification check
```

`check` runs the tests, ktlint, the coverage gate (80 % of lines) and `verifyCheckerIndependence`.
Reports: `verification/jpeg-privacy/build/reports/tests/test/index.html`, `.../kover/`, `.../independence/`.

Check files pulled from a phone or simulator:

```
./gradlew -p verification :jpeg-privacy:run --args="--policy=strip-all --icc-sha256=<hex> /path/to/output.jpg"
./gradlew -p verification :jpeg-privacy:run --args="--policy=allowlist:gps,datetime --measure-icc /path/to/a.jpg /path/to/b.jpg"
```

The command line never prints file names, directories, offsets or metadata values, because names of outputs can be
confidential: each file is reported by its position (`file #1` is the first argument) with `PASS` or `FAIL`, the
number of failures and the finding codes, for example `FAIL file #2 failures=2 codes=GPS_NOT_ALLOWED,TRAILING_BYTES`.
A file that cannot be read is `IO_ERROR` with the exception class only. `--icc-sha256=<hex>` (repeatable) names a
reviewed ICC profile; `--measure-icc` only adds the profile hash to the line, to obtain the hash of a platform
encoder's profile: the verdict and the exit code do not change, so an unreviewed profile is still `FAIL`.
2 = usage error.

## Mutation check and dependency verification

`python3 -I verification/scripts/mutation_check.py --out results.tsv` applies the hand-written mutants to
the sources and build files of both modules one at a time (the ten `RV-` mutants are reconstructed from a review table, they are not a reviewer's script; the others are one per rule), runs the suite for each, reads the failing test names from the JUnit XML, and writes one
line per mutant (id, description, KILLED / SURVIVED / COMPILE-ERROR / PATTERN-NOT-FOUND, names of the failing
tests). It restores every file and verifies that all files are byte-identical to the state before the run. It takes
about 12 minutes. A mutant counts as killed only when at least one test fails.

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact the verification build downloads (plugins and
libraries; signatures are not verified). A new dependency, or a changed artifact, fails the build until the file is
regenerated and reviewed: `./gradlew -p verification --write-verification-metadata sha256 check`, then review the
diff. Two mutants of the script plant an unverified dependency and an altered checksum.

## The checker (`jpeg-privacy`)

`PrivacyChecker.assertPrivateOutput(bytes, CheckOptions(...))` is the one helper every test uses on every output
file (PRIV-007, PRIV-021). It walks the JPEG markers itself and shares no code with the library; its only runtime
dependency is the Kotlin standard library (`verifyCheckerIndependence` fails the build otherwise). No external
tool (exiftool, ImageMagick, Pillow) is needed.

It reports, by name (`FindingCode`):

- structure: not a JPEG, truncated or lying segment lengths, missing EOI, bytes after the final EOI, a second image
  after EOI, a second SOI, markers outside the allowed list, no frame or scan, an embedded JPEG signature inside any
  metadata segment (ICC included);
- coding segments: SOF, SOS, DRI, DQT and DHT must have exactly the length their content implies; quantisation and
  Huffman tables must be used by the frame or a scan, may not be defined twice before the first scan, and are
  capped in number, so these segments cannot carry hidden data;
- segments: only JFIF (without thumbnail), exactly one EXIF APP1, at most one ICC APP2, then the coding segments, in
  that order; XMP, IPTC/APP13, COM, MPF (secondary image or gain map), JFXX and every other APPn are failures;
- EXIF (ADR 0005 decision 3): `Orientation = 1` must be present; under `StripAll` IFD0 holds nothing else (no IFD1
  thumbnail, no Exif sub-IFD, no GPS IFD, no MakerNote); under `KeepAllowlist(gps, dateTime, camera)` only the
  closed tag list of those groups, with type, count and value bounds (PRIV-019); duplicate tags; bytes in the EXIF
  segment that no IFD refers to; segment size above 1,024 bytes;
- ICC: a profile can carry free text, so by default EVERY ICC profile fails unless its SHA-256 is in
  `allowedIccSha256` (the reviewed profiles of the platform encoders; `measureIccOnly` obtains a hash and is never
  for a privacy test). Independently of the list: byte-identical to the source's profile (`sourceIccProfile`), not
  RGB, malformed header or tag table, non-zero bytes that belong to no tag, an embedded JPEG, more than one.
  `assertIccIdenticalAcrossOutputs` (at least two outputs) checks that the profile does not depend on the input;
- canaries: any byte string you plant in the input (`canaries`) must not occur anywhere in the output, also encoded
  as UTF-16 (EXIF UserComment and ICC v4 text use it).

Three checks are silent unless the test passes the input along: `canaries`, `sourceIccProfile` (use a distinctive
profile; a common sRGB profile can equal the encoder's) and, for the allowlist policy,
`KeepAllowlist(...).withRequiredFrom(inputBytes)`, which throws if the input's EXIF cannot be read completely
(deriving fewer expected tags would be a vacuous pass).

A file the checker cannot parse (truncated, IFD loops, offsets or counts outside the segment, coding segments whose
length does not match their content) is reported as unparseable and fails (PRIV-020). Work and memory are bounded:
at most 4,096 markers and 256 findings are processed per file (more is reported as unparseable), no allocation
depends on a count read from the file, and files above 64 MiB (`maxFileBytes`) are not read. A JVM that cannot hold
the file in memory is outside this guarantee. Finding messages contain names, tag identifiers, counts and offsets,
never metadata values.

For the allowlist policy, `KeepAllowlist(...).withRequiredFrom(inputBytes)` adds the allowlisted tags the input
carried: the output must contain them (failure when missing); an allowlisted tag that is absent and was not in the
input is information only.

## Self-test and fixtures

`src/testFixtures` builds JPEGs by code (JDK `javax.imageio` for the pixels, an own TIFF/EXIF writer and segment
builder for metadata; no committed binaries, no faces, no personal data). `PlantedDefects` lists one file per
defect with the exact set of finding names expected; `PrivacyCheckerSelfTest` requires each to fail with exactly
those names and the correct strip-all shape to produce no finding at all. `HostileInputTest` covers truncation at
every byte, IFD loops, oversize counts, out-of-range offsets and a seeded mutation fuzz under a 30 s timeout.
The fixtures are published as `java-test-fixtures` so later modules (privacy end-to-end tests) can reuse them.

## Limits

- JPEG only, because the library writes JPEG output only. HEIC, PNG and other formats need their own checker before they may be output.
- The tag list and the 1,024-byte limit are this checker's own copy of ADR 0005 (`docs/adr/0005-...`, status as
  committed there). If the ADR changes, change `Policy.kt` and the planted fixtures together. Not in the ADR, chosen
  here: GPS altitude and horizontal error below 100,000 m, any calendar-valid date from year 0001 to 9999.
- A gain map is detected through its container (MPF segment, second image, bytes after EOI), not by decoding.
- Data appended inside the entropy-coded segment before the final EOI (for example a byte-stuffed JPEG after the
  last block) is caught by the JDK decode check (decoder warnings fail, `decodeCheck`), not by the marker walk; the
  decode check is a second opinion that depends on the JDK decoder noticing it, and images above 36 megapixels
  are decoded sub-sampled (the decoder still reads all the data) and images above 256 megapixels are NOT decoded,
  which is a failure (`DECODE_SKIPPED`); the decode check is switched off only with `decodeCheck = false`. A reviewed ICC profile must be one the JDK decoder accepts.
- Free text inside a profile that is on the reviewed list is reviewed by definition; the checker cannot judge text.
- iOS outputs are checked by copying them to the host and running this checker (the iOS path of ST-042 AC5 is not
  decided yet).

## The pixel oracle (`pixel-oracle`, ST-045, PRIV-023)

`PixelOracle` decodes PNG and JPEG with the JDK (`PixelDecoder`) and compares pixels; it uses no library code and none
of the ST-042 checker (`IndependenceTest` scans the sources, `verifyCheckerIndependence` lists the dependencies, and
the build refuses project, included-build and file dependencies). It derives the mask region itself from the formula
of FR-004 (`MaskEllipse.fromFaceBox`: centre x = left + w / 2, centre y = top + h / 2 - 0.08 h, semi-axes 0.8 w and
0.9 h, r from the pixel CENTRE, soft edge `max(0.10, 1 / smaller semi-axis)` in r). Pixels within 1e-4 of a
boundary are ambiguous and never judged (counted in the report). The masks are the oracle's input: consumer tests take
them from a fixed mask plan or ground truth, never from the boxes the library reports for the same call.

What "fully opaque area = the ellipse" means (ADR 0002, decision 10): the part that is always replaced completely is r <= 1 (every pixel
opaque, no face information); outside it only the outward soft edge changes pixels, with a weight that never rises
outward; beyond it pixels are bit-identical. The checks:

| Question | Method | Input conditions (otherwise the check reports it, it never passes) |
|---|---|---|
| (b) opaque core: alpha 255 for r <= 1 | `insideOpaque(input, output, masks)` | in-memory ARGB; the input needs alpha < 255 inside the masks, else `NOTHING_CHECKED` (a decoded JPEG has no alpha) |
| no face pixel survives | `insideDiffersFromSource(source, output, masks, Fidelity)` | `Exact`: output identical to the source outside the masks, else `INEXACT_OUTPUT`; survival is judged on RGB (alpha forced to 255 is not masking). On real photos a fill taken from the surroundings can equal a face pixel by chance: pass `pair` (the second input and output of the differential pair, `requireMasked` does it) and a pixel survives only if the output equals the source in both inputs. `Lossy(reference, threshold)`: `reference` is the same input encoded and decoded the same way but not masked; a pixel survives when within the threshold of it, or inside a 5 x 5 window in which the reference texture survives (output texture correlated with the reference); flat reference windows (no texture) can not be judged and above 50 % of them the check reports `CHECK_CANNOT_DISCRIMINATE` |
| pixels equal an expected colour field | `insideColourField(input, output, masks, field, tolerance)` | the input must be distinguishable from the field by more than the tolerance, else `CHECK_CANNOT_DISCRIMINATE` |
| every mask applied / none applied | `maskApplication(.., Expect.ALL_APPLIED or NONE_APPLIED, Fidelity)` | the success path needs `ALL_APPLIED` (`NOT_MASKED` when nothing was masked, `PARTLY_MASKED` for a skipped entry); `NONE_APPLIED` is the error path |
| (c) only outward changes, within the owner's band | `softEdgeOutwardOnly(input, output, masks)` | output alpha never below input alpha in the soft edge, the soft edge exists (`SOFT_EDGE_MISSING`), and no pixel changes further beyond the ellipse than the band fixed in pixels (`CHANGED_BEYOND_BAND`): W = soft-edge width x the larger semi-axis (`MaskEllipse.maxBandWidthPx`, 54.25 px for a 600 x 700 face box at feather 0.10), from the geometry and the configured feather (0 to 0.5) only, never from the output; +0.5 px for the pixel centre. The largest distance found is in the report (`maxChangedDistancePx`). Widening the band needs the owner's approval |
| (c2) bounded feather weight | `softEdgeWeightBounds(input, output, masks)` | judged on pixels whose input alpha is 0: alpha 255 for r <= 1, never rising outward by more than 1 level, 255 beyond r = 1 only where t <= 0.05, 0 beyond the soft edge. Bounds fixed in advance, never widened |
| pixels beyond every soft edge unchanged | `unchangedOutside` | satisfied by an unmasked image by definition; use with the checks above |
| two outputs identical | `identicalBytes`, `identicalPixels` | tolerance 0 = exact |
| differential pair with one fixed mask plan | `pairPrecondition` then `differentialPair` | inputs differ only inside r <= 1, by at least 32 levels in 98 % of its pixels (`minDifferingFraction`, `minInputDifference`, else `PAIR_TOO_THIN`); lossy: whole 16 x 16 blocks with one block of margin (`mcuSafeBlocks`) and a stated lower bound |
| masked area at the scaled position, size and coverage | `maskedRegionMatches` | no default tolerances: fix them from a baseline, never widen them |
| unchanged buffer after a typed error | `unchangedAfterError` | exact |
| all of the above for one success path | `requireMaskedBy(input, masks, fidelity, masker, libraryFeather)` (the only public masked verdict for exact outputs; a lossy `fidelity` or a pair tolerance is `INVALID_EVIDENCE` before the masker runs) | the oracle builds the second input, calls the masker on copies (A, B, A with alpha 0, A with noise beyond the soft edges) and judges everything below; see "Evidence". For lossy file mode use `requireMaskedLossyBy` (next row); the internal lossy entry takes a caller-supplied, TRUSTED second output and is used by the oracle's own tests only |
| the same for a lossy file-mode pipeline (JPEG output) | `requireMaskedLossyBy(input, masks, survivalThreshold, masker, unmaskedPipeline, libraryFeather)` (the only public lossy masked verdict) | `masker` and `unmaskedPipeline` take the encoded input (PNG written by the oracle) and return the output file; the oracle builds the block pair, the noise control and the reference itself; input alpha 255 everywhere; see "Evidence" |

`NOTHING_CHECKED` is reported when a check judged no pixel, `SIZE_MISMATCH` for different sizes. A check that judged
no pixel never passes; the input conditions above are enforced by the checks, not left to the caller. The one claim
this does not make: the checks decode with the JDK only, so the tolerances of lossy tests must be measured per
platform decoder. Reports contain counts and the first coordinates, never pixel values.

`PixelOracleSelfTest` plants thirteen defects into a small reference masker written for the test (blur stub, alpha 254 at
the edge, opaque area one row larger or smaller, a changed pixel outside, output depending on masked input, a skipped
entry, every mask skipped, soft-edge opacity rising outward, no soft edge, alpha lowered in the soft edge, face colours kept with alpha 255, face mixed with grey) and requires
the exact set of finding names; the clean case must report nothing. Every check that must notice an unmasked image has
a negative control (`anUnmaskedImageFailsEveryCheckThatMustNoticeIt`, and the lossy variant with a JPEG round trip).
Limits of a lossy output: it can show coverage, position, differential pairs and face holes of
the texture-window size (5 x 5 and larger, MEASURED with the JDK JPEG encoder and decoder at quality 0.9 on a noise face), it
can not show that no single pixel survives (a one-pixel hole is invisible in a JPEG) and not holes in flat faces. Prove
pixel-exact coverage on `Exact` outputs (in-memory, PNG file mode). The window parameters are in `Fidelity.Lossy`; fix them
from a baseline and never widen them after a result.
Real renderer output: a one-off check, not a test in this repository. The checks (b), (c), (c2) and the masked verdict with
differential-pair evidence were run on the output of the core's JVM `MaskPainter` for all the committed public-safe fixtures
(`testdata/pd`; a one-off run, made before those images moved out of the repository, also covered 12 of those), three styles, input alpha 255 and 0, faces and boxes from the OpenCV reference file (a fixed
plan, never from the library). The result is recorded in the QA hand-off; the consumer tests of the library stories should
carry it from now on. Not covered here: the source-scan meta-test that library tests use the oracle and the library-side
consumer tests.

## Evidence for the masked verdict

`requireMaskedBy(input, masks, fidelity, masker, libraryFeather)` is the only public way to get a masked verdict for exact
(in-memory, PNG) outputs; the caller-supplied pair or colour field of the internal `requireMasked` is not public API, because a
caller can fake it (a copy of the first output as second output cannot be told from a legitimate input-independent fill by
values). The oracle builds the second input itself (`pairInputFor`: every pixel inside the masks changed by 64 to 191
levels in every channel, alpha included, seeded), calls the masker on copies and copies the results right after each call,
so an in-place masker, a shared buffer, swapped or typed arguments and a thin pair are impossible. It also:

- runs the masker on A with alpha 0 everywhere, so the bounded feather weight (c2) and the opacity core are judged on every
  proof, whatever the input alpha (the weight rule needs alpha; file mode is proven on the ARGB path of the same renderer);
- runs the masker on A with seeded noise beyond every soft edge and requires the output to follow it there, so a masker lambda
  that ignores its argument (masks something it captured) is reported (`MASKER_IGNORES_ITS_ARGUMENT`);
- requires `libraryFeather`, the feather of the library configuration under test, to equal the feather of the masks;
- validates the evidence: buffers and pixel arrays must not be shared, `Fidelity.Exact` allows no tolerance (pair delta 0), the
  pair inputs must differ by at least 32 levels in 98 % of the pixels inside the masks (floors, not defaults to lower: lower
  values are `INVALID_EVIDENCE`);
- judges survival so that a match with the source counts unless the pair shows the output did not follow the input (the second
  output equals the first and differs from the second input), and reports `CHECK_CANNOT_DISCRIMINATE` when more than 20 % of
  the inside pixels are only excused that way.

A second output supplied by the caller can not be told from a copy of the first one (for example one output file decoded
twice), so the internal lossy entry `requireMaskedLossy` is trusted and not exported. The public lossy verdict is
`requireMaskedLossyBy(input, masks, survivalThreshold, masker, unmaskedPipeline, libraryFeather)`. Both functions are
pipelines from file bytes to file bytes: `masker` is the call under test with one fixed mask plan, `unmaskedPipeline` the
same call without masks (for the library: an empty plan). The oracle encodes A (the input), B (A with every pixel of the
block-safe 16 x 16 blocks inside r <= 1, `mcuSafeBlocks`, changed by 64 to 191 levels in R, G and B) and N (A with noise
beyond every soft edge) as PNG. It passes each pipeline its own copy of the bytes and decodes the results with the JDK. It
then requires:

- the masked verdict of `requireMasked` with the unmasked output of A as the `Fidelity.Lossy` reference (committed window
  parameters, the caller's `survivalThreshold`, fixed from a baseline before the test is Ready), and the pair (A, B)
  identical within the pair tolerance with the lossy share floor. The tolerance is capped at 0 levels
  (`LOSSY_BY_MAX_PAIR_TOLERANCE`, any other value is `INVALID_EVIDENCE`): at 8 levels a mask that keeps 1/32 (about 3 %) of
  the face colours passed. A platform encoder that needs more needs a measured baseline first;
- in the 16 x 16 blocks beyond every soft edge with one block of margin (`farBlocks`): the masked and unmasked outputs of A
  agree (`CHANGED_OUTSIDE_SOFT_EDGE`), and the masked output of N follows N within the pair tolerance (0, so exactly) as the
  unmasked one does
  (`MASKER_IGNORES_ITS_ARGUMENT`);
- the reference is validated: inside the masks the unmasked output follows its input (luma correlation at least
  `LOSSY_MIN_REFERENCE_CORRELATION` = 0.9 for A and B), beyond the soft edges it is the same for A and B and follows N
  (`INVALID_EVIDENCE` otherwise);
- input alpha 255 everywhere, at least one masks entry, one safe block (`PAIR_TOO_THIN`) and one far block
  (`CHECK_CANNOT_DISCRIMINATE`); invalid evidence is reported before either pipeline runs.

Residual floor and limits of `requireMaskedLossyBy`:

- In the block-safe blocks a leak is shown when it changes at least one decoded output level of B against A. A mask that
  keeps less than about 1/128 of the face colours changes no 8-bit level before encoding (half a level at the smallest pair
  difference of 64 levels) and can not be shown; the JPEG quantisation can hide a somewhat larger share. A 1/32 (about 3 %)
  face mix fails (`LossyVerdictTest`).
- Outside the block-safe blocks (the rim and the parts of r <= 1 not covered by whole safe blocks) only lossy survival
  judges: translucent leaks of about 10 % or less there are not shown by the lossy verdict.
- Rule: every file-mode consumer test pairs `requireMaskedLossyBy` with `requireMaskedBy` (exact, in-memory mode) for the
  same mask configuration, so pixel-exact coverage and opacity are proven on the exact path of the same renderer.

The limits of a lossy output above apply: holes smaller than the texture window and flat faces can not be shown. On the
self-test's noise face, a survival threshold of 2 lets a few rim pixels equal the reference by chance. That is a false
failure, never a false pass; the self-test uses 0, like the internal entry's tests. Library consumer tests call
`requireMaskedBy` (exact outputs) or `requireMaskedLossyBy` (JPEG files); a
library test that builds its own colour field from the output under test, or that reads a library output without the oracle, is a
violation for the source-scan meta-test.
