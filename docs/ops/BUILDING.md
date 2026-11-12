# Building and testing FaceBlur

## Requirements
- JDK 17 on `PATH` or in `JAVA_HOME` (Gradle runs on it and compiles with a JDK 17 toolchain).
- Android SDK with platform 37 (`ANDROID_HOME`, or `sdk.dir` in an untracked `local.properties`).
- For iOS targets: macOS with Xcode and at least one iOS Simulator runtime installed (Xcode > Settings >
  Components). The iOS simulator tests (`iosSimulatorArm64Test`, part of `./gradlew build` on macOS) start a
  simulator themselves. On Linux the iOS klibs are compiled (not linked) for type checking.
- Python 3 (standard library only) for the scripts in `scripts/`.
- Nothing else: the Gradle wrapper downloads Gradle 9.8.1 and verifies its SHA-256
  (`gradle/wrapper/gradle-wrapper.properties`).

## Tests that need local fixtures
The library ships no face-detection model: apps supply their own (ADR 0012). The public build needs no model and
downloads nothing: `./gradlew build` runs every test that does not need a model fixture or a local photo set.

Tests that do need such files are excluded by name in a public build and reported as NOT RUN. They are listed in
`config/local-fixture-tests.txt`. They run only in the maintainer's local gate, where the fixtures are supplied from
outside the repository and verified before use; the gate fails if a listed test did not run and pass. The fixtures
are not in the repository and never part of an artifact. A contributor does not need them: the public checks are the ones that run without them, and the maintainer runs the
rest.

The task and script entries in the table below that name fixtures (marked "local gate only") exist only in that
local gate.

## One command per job
| Job | Command |
|---|---|
| Everything (all targets, tests, ABI check, Android lint, ktlint, coverage gate) | `./gradlew build` |
| JVM tests (also runs the two own-heap tasks below) | `./gradlew :faceblur:jvmTest` |
| Own-heap mask-stage tests, each in its own JVM with a fixed heap (25 MP worst case, 2 GiB; remaining-heap check on a real heap, 380 MiB); they finalize `jvmTest`. Locally only, `-Pfaceblur.largeHeapTests=false` skips them; under CI (`CI` set and not `false`) the build refuses that switch | `./gradlew :faceblur:jvmLargeHeapTest :faceblur:jvmRealHeapTest` |
| Controls of that switch (refused under CI as `-P` and as environment property, both tasks in the graph of `jvmTest` under CI, skipped locally; dry runs only) | `sh scripts/test-large-heap-switch.sh` |
| Android host tests | `./gradlew :faceblur:testAndroidHostTest` |
| iOS simulator tests (macOS) | `./gradlew :faceblur:iosSimulatorArm64Test` |
| ABI check / update the reference dump in `faceblur/api/` | `./gradlew checkKotlinAbi` / `./gradlew updateKotlinAbi` |
| Android lint for the Android and JVM targets (warnings are errors) | `./gradlew :faceblur:lint` (reports: `faceblur/build/reports/lint-results*.html`) |
| ktlint (Kotlin coding conventions) / auto-format | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Coverage report / module gate (>= 80 % lines) | `./gradlew koverXmlReport koverHtmlReport` / `./gradlew koverVerify` |
| Coverage of changed lines (>= 80 %, after `koverXmlReport`) | `python3 -I scripts/diff-coverage.py --report faceblur/build/reports/kover/report.xml --base origin/main` |
| Test fixtures only (reference model + derived models, all pinned) (local gate only) | `./gradlew :faceblur:deriveTestModels` |
| Fuzz the ONNX pre-parse walker (crash-freedom; the seed is printed first) (local gate only) | `./gradlew :faceblur:fuzzOnnxPreParse -Pfaceblur.fuzz.seed=random -Pfaceblur.fuzz.seconds=60` |
| faceblur-eval: measure a model and profile on your own annotated photos (test-source tool, never published, not part of `build`; formats and options: `faceblur/src/jvmTest/kotlin/io/github/shishark/faceblur/eval/README.md`). Relative paths resolve against the directory Gradle is started from; JVM heap 4 GiB, change with `-Pfaceblur.eval.heap=8g` | `./gradlew :faceblur:faceblurEval --args="--model model.onnx --profile profile.json --images photos --truth ground_truth.json --thresholds 0.3,0.4,0.6,0.7"` |
| faceblur-eval smoke run on fixtures that are not in the public tree (ground truth from reference boxes; checks that the task sets `ORT_DISABLE_TELEMETRY=1` itself, exits 0, writes the totals and prints no file name; runs in the maintainer's local gate) (local gate only) | `sh scripts/eval-smoke.sh` |
| Fixture, model-leak and fuzz-harness controls (wrong hash/size, missing source, modified copy, the tests' own pin check, derived models restored, build cache, planted models in artifacts, changed pins and wrong builders, planted crash, replay) (local gate only) | `sh scripts/test-model-step.sh` |
| Model-leak check: no test-model fixture in any published artifact (three 64-byte windows of each fixture raw, base64 at all alignments and hex, also UTF-16; SHA-256 hex; `.onnx`/`.ort` names; archives to any depth) (local gate only) | `python3 -I scripts/check-model-leak.py --fixture build/test-models/<reference model file> --fixture build/test-models/derived/G0.onnx --fixture build/test-models/derived/G2.onnx --fixture build/test-models/derived/G3.onnx build/local-maven faceblur/build/dokka/html` |
| API docs (HTML in `faceblur/build/dokka/html`) | `./gradlew dokkaGeneratePublicationHtml` |
| Static XCFramework (macOS) | `./gradlew assembleFaceBlurCoreReleaseXCFramework` |
| Publish to the repository-local Maven directory `build/local-maven/` | `./gradlew publishAllPublicationsToLocalRepoRepository` |
| Publish to `~/.m2` (Maven local) | `./gradlew publishToMavenLocal` |
| Check that artifacts embed no absolute build paths (archives at any depth, gzip/tar, UTF-16) | `sh scripts/check-artifact-paths.sh build/local-maven` |
| Check the publication (no jvm variant, android + iOS variants, no model file; the Android AAR's manifest declares no permission and no component, and with onnxruntime-android as a dependency removes exactly its telemetry starter by name; the onnxruntime-android AAR's own manifest is re-read from the Gradle cache) | `sh scripts/check-publication.sh build/local-maven` |
| Check the licence index and, after `:faceblur:writeRuntimeLockfile` and `publishAllPublicationsToLocalRepoRepository`, the runtime dependencies and the notices inside the published files | `python3 -I scripts/licence-notices.py check --lockfile faceblur/build/osv/runtime.lockfile --repo build/local-maven` (index only: without the options) |
| Merged manifest of a consumer app (after the publication above): `verification/android-consumer` (standalone build) is built as C1 (app + faceblur-android + an androidx.startup initializer) and as the control C0 (onnxruntime-android without FaceBlur); the merged manifest XML and the APK's manifest (`aapt2 dump xmltree` / `dump permissions`) must show the starter absent, `ProcessLifecycleInitializer` present and the expected permissions in C1, and the C1 check must fail on C0. The merger report is never read | `sh scripts/check-consumer-manifest.sh` |
| Device tests on the physical test phone, with the phone-test log for the checked-out commit (local only, never in CI; clean checkout; emulators refused; no screenshot, no shared storage). Exit 3 = BLOCKED (phone not connected), 4 = no device tests in this revision | `FACEBLUR_PHONE_SERIAL=<serial> FACEBLUR_PHONE_MODEL=<model> sh scripts/phone-device-tests.sh <log file>` |
| Check a phone-test log for a commit / is a log required for a range | `FACEBLUR_PHONE_SERIAL=<serial> FACEBLUR_PHONE_MODEL=<model> python3 -I scripts/phone_test_log.py verify --log <log file> --rev HEAD` / `python3 -I scripts/phone_test_log.py needed --base origin/main` |
| Independent privacy checker on the JPEG outputs of the JVM file-mode tests (after `:faceblur:jvmTest`, which empties `faceblur/build/file-mode-crosscheck/` and writes the outputs plus `manifest.tsv`: name, policy, expected verdict and, for planted outputs, the exact finding codes; the set of files must match the manifest exactly) | `./gradlew -p verification :jpeg-privacy:installDist` then `sh scripts/crosscheck-file-mode.sh verification/jpeg-privacy/build/install/jpeg-privacy/bin/jpeg-privacy` (controls: `sh scripts/test-crosscheck-file-mode.sh <same launcher>`) |

The jvm target is built and tested but not published in 0.x (ADR 0002): its publish tasks are skipped and the root
module metadata is written without jvm variants.

`updateKotlinAbi` changes the public ABI reference; run it only for an intended API change and review the diff.

## Script gates (no Gradle needed)
| Gate | Command |
|---|---|
| SPDX line on every source file (LIC-001) | `sh scripts/check-spdx.sh` |
| DCO sign-off on every commit of a range (LIC-006) | `sh scripts/check-dco.sh origin/main..HEAD` |
| No "anonymi[sz]e" in the public ABI dumps (DX-009) | `sh scripts/check-api-wording.sh` |
| Wording lint (DOC-008; reports NOT RUN until the term list `config/wording/terms.txt` exists) | `sh scripts/check-wording.sh` |
| SAST: networking, logging, process/reflection and file I/O in library code (PRIV-014, PRIV-004); reviewed exceptions only in `scripts/sast/allowlist.txt`, keyed by the exact line | `OPENGREP=<opengrep 1.33.1 binary> sh scripts/run-sast.sh` (`--print-hashes` shows the keys) |
| SAST variant corpus (planted probes incl. Swift, allow-list controls) | `OPENGREP=<binary> python3 -I scripts/test-sast-probes.py` |
| Dependency verification cannot be switched off; every Gradle build has a metadata file | `sh scripts/check-dependency-verification.sh` |
| No internal process IDs (decision, question, risk, review-finding or assignment labels) or workspace paths in tracked files, their file and directory names, and commit messages; requirement/story IDs and ADR numbers are fine. The baseline `scripts/internal-id-baseline.txt` is empty and may not grow. A failure names file, line, column and kind, never the matched text (a file whose path matches is shown as its index and path hash), so CI logs stay free of IDs | `python3 -I scripts/check-internal-ids.py --tree` |
| No internal figures in tracked files and commit messages: a line with source wording (generic terms in `scripts/internal-figures-terms.txt`, never values or names of photo collections) within two lines of a number fails, unless the block (path + SHA-256 of its lines) has a reviewed entry in `scripts/internal-figures-allow.txt` (`permanent`, or `temporary` for text to be reworded; `--no-temporary` for a release). An entry that excuses nothing fails (stale). Output names path, lines and block hash, never the text. Keys for a review: `--print-keys` | `python3 -I scripts/check-internal-figures.py --tree` (messages: `--range origin/main..HEAD`) |
| ONNX Runtime telemetry switched off in code: every file that creates an `OrtEnvironment` calls `setTelemetry(false)`, JVM code also requires `ORT_DISABLE_TELEMETRY=1` before creating it, no empty catch around it | `python3 -I scripts/check-ort-telemetry.py` |
| ONNX Runtime locked session options: only the allow-listed configuration keys (`session.load_model_format` = `ONNX`, `session.allow_released_opsets_only` = `1`, `session.intra_op.allow_spinning` = `0`) as literals with their required values, no other key-like literal, none of the "never" calls of ADR 0012 decision 4 (custom operators, optimised-model path, profiling, external or added initializers, execution providers other than XNNPACK); production and test sources | `python3 -I scripts/check-ort-options.py` |
| Test tasks produced result XML with at least one executed test (CI: `jvmTest testAndroidHostTest jvmLargeHeapTest jvmRealHeapTest`) | `python3 -I scripts/check-test-results.py faceblur/build/test-results jvmTest jvmLargeHeapTest jvmRealHeapTest` |
| Planted-violation self-tests of all script gates | `OPENGREP=<binary> sh scripts/test-gates.sh` |

Phone-only Android files (`config/coverage/phone-only.txt`: only platform or ONNX Runtime calls and their
exception translation; changes need the QA lead and the code reviewer) leave the changed-lines 80 % only when the gate
gets a valid phone-test log for the exact commit: `FACEBLUR_PHONE_TEST_LOG=<log>` for `scripts/ci-local.sh` (or
`--phone-log <log>` for `diff-coverage.py`). The log must show every device test passed on the configured phone, the
same count as the `@Test` methods of the commit and every test of `config/device-tests/required-tests.txt` (changes
need the QA lead). The only accepted skip is an assumption skip of a test listed in
`config/device-tests/expected-skips.txt` on a phone below the minimum API level given there (changes need the QA
lead's written approval and an API-level reason); the check reports `N passed, K expected skips (names)` and names
the other environment where each skipped test must pass. See `scripts/phone_test_log.py` for every rule. Without
the log those lines count like all others. A range cannot relax its own gate: an entry of `phone-only.txt` or a row
of `expected-skips.txt` counts only when the base has it too (a skip with the lower, stricter minimum of the two), so
an approved list change takes effect after it is merged. CI has no phone: there the lines are reported as NOT
MEASURED with one warning per file (path and line count); `diff-coverage.py` accepts that mode only with
`GITHUB_ACTIONS=true`, and the local gate refuses to run with `GITHUB_ACTIONS` set. The gate always prints both
figures, with and without the phone-only files. `phone_test_log.py needed` (is a log required for a range) answers
"required" for an empty or unresolvable base and exits 2 on any other error; changes to the phone tooling
(`phone_test_log.py`, `phone-device-tests.sh`, `diff-coverage.py`) also require a log.

Coverage of iosMain (Kotlin/Native) cannot be measured by Kover: `diff-coverage.py` lists such files as NOT
MEASURED with a CI warning. Until the qa-lead names the replacement evidence, every iosMain change needs
tests per acceptance criterion confirmed by the code-reviewer and an explicit note in the handoff.

The git hooks in `.githooks/` (enabled with `git config core.hooksPath .githooks`) run the data-admission guard,
the SPDX check, the internal-ID gate, the wording lint and the internal-figure gate on the staged content before
each commit, and the data-admission guard, the DCO check, the internal-ID gate and the internal-figure gate on each
commit message. CI checks the tracked files and
the commit messages of each push again and is authoritative (the hook ignores `#` comment lines of a message; CI
sees the message as committed). Commit with
`git commit -s`.

## Dependency verification
`gradle/verification-metadata.xml` pins the SHA-256 of every dependency and plugin artifact Gradle resolves; an
artifact that is missing from the file or has a different checksum fails the build. After an intended dependency
or version change, regenerate it on macOS (it resolves the macOS-only artifacts too) and review the diff.
`--refresh-dependencies` is needed: without it Gradle does not write entries for module metadata (POM, `.module`)
that it already holds in its local cache, and a local build still passes while CI fails:

```
rm gradle/verification-metadata.xml
./gradlew --refresh-dependencies --no-build-cache --write-verification-metadata sha256 build \
  dokkaGeneratePublicationHtml publishAllPublicationsToLocalRepoRepository assembleFaceBlurCoreReleaseXCFramework \
  koverXmlReport koverHtmlReport :faceblur:writeRuntimeLockfile
```

Host-specific artifacts that only Linux CI resolves (the Kotlin/Native `linux-x86_64` prebuilt distribution) are
not written on macOS: add their SHA-256 by hand after downloading the artifact from Maven Central and checking it
against the published `.sha1`, and record the origin in the entry. Only the devops-engineer edits build files and
this file (one writer per path). CI fails if any build, workflow or script switches verification to lenient or off,
or if a Gradle build in the repository has no metadata file.

Not covered by Gradle's verification (residual risk, tracked internally): the toolchain dependencies that the
Kotlin/Native compiler downloads itself into `~/.konan/dependencies` (LLVM, sysroots), and Android SDK packages.
The checksums are trust on first use (written from the downloaded artifacts; signature verification is off).

## Known advisories in dependencies
- Runtime and published dependencies: CI fails on any published advisory (OSV-Scanner) in the library's resolved
  runtime classpaths (`./gradlew :faceblur:writeRuntimeLockfile` writes them to
  `faceblur/build/osv/runtime.lockfile`) and in the POMs of the published variants:
  `OSV_SCANNER=<binary> sh scripts/osv-runtime-gate.sh faceblur/build/osv/runtime.lockfile build/local-maven`.
- Build-only tools (Android lint, Dokka, ktlint, Gradle plugins): where a patched version of an affected library
  exists, `gradle/advisory-floors.properties` raises it on the plugin classpath and on every configuration of
  `:faceblur`; the tools must still pass. CI reports any remaining build-tool advisory
  (`osv-scanner scan source -L gradle/verification-metadata.xml`) without failing. Re-check at every milestone.
- After changing the floors, regenerate `gradle/verification-metadata.xml` from scratch (delete it first, so that no
  entry of an old version stays behind) and re-add the Linux-only entry described above.

## Xcode builds on this kind of machine
If Xcode's global build location is set to a custom location (Xcode > Settings > Locations), `xcodebuild
-derivedDataPath <dir>` still writes products to the shared custom location. Pass `SYMROOT=<dir>/Build/Products`
and `OBJROOT=<dir>/Build/Intermediates.noindex` explicitly on every `xcodebuild` command so that outputs stay in
your own folder. The Swift package cannot be built with plain `swift build` (the XCFramework has no macOS slice);
use `xcodebuild` with an iOS destination.

## Continuous integration
The private repositories run on the GitHub Free plan with a monthly allowance of Actions minutes, so the expensive jobs
run only when their result is needed. The set of checks that must pass before a merge is the same as before; only the
moment it runs changes.

| Event | `gates` | `build` + `verification` | `fuzz.yml` |
| --- | --- | --- | --- |
| push to a branch other than `main` | always | only if the branch changes CI or build configuration (`.github/`, `scripts/`, `gradle/`, `*.gradle.kts`, `gradle.properties`, `gradlew*`); otherwise start them by hand, see below | only if the walker or its tests changed |
| manual run (`workflow_dispatch`) on any branch | always | always (`verification` only if `verification/` exists) | by hand only |
| push to `main` | always | skipped only if the same job already passed on the same tree in the last 24 hours | only if the walker or its tests changed |

### Before a merge: the local merge gate
`sh scripts/ci-local.sh`, run from the root of a clean checkout of the commit to be merged (best a fresh worktree),
runs every check of the `gates`, `build` and `verification` jobs on your machine, in the CI order. The checks are the
steps of `scripts/ci-steps.sh`; the workflow runs exactly these steps (`sh scripts/ci-steps.sh <step>`).
`scripts/check-ci-steps.py` (a gates step, and the first thing the local gate runs) fails when a job's step list
cannot be read, is empty, or differs from the workflow's steps of that job in either direction, or when the workflow
runs an inline check outside the steps; so the local gate and CI cannot drift apart. The local gate copies the JUnit
results right after the build steps (later negative controls rerun single tests), counts them
(`scripts/ci-local-results.py`; any failure or error fails the gate) and ends with `RESULT PASS - steps=<n>`. It needs:
- the scanners at the versions pinned in `.github/workflows/ci.yml`: `FACEBLUR_GITLEAKS`, `OPENGREP`, `OSV_SCANNER`
  (a missing scanner or another version fails the gate before any check runs);
- an Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`), JDK 17 for the Gradle toolchain, network
  access (OSV database, reference fixture download), and `origin/main` (diff base of the range checks).
Every step runs even after a failure; the last line is `ci-local: RESULT PASS - steps=<n>` or
`ci-local: RESULT FAIL - steps=<n> failed: <steps>`. `python3 -I scripts/ci-local-results.py verify-log <output>`
accepts an output only if the step count is above zero and matches the PASS lines and the counted results agree with
`check-test-results`. Scanner versions must match the pins exactly (whole version, not a substring).
Not run locally, because they are not checks or only exist on the runner: scanner installs (replaced by the version
check), the Gradle wrapper validation (the gate fails if `gradle/wrapper/gradle-wrapper.jar` differs from
`origin/main`, so a new wrapper is validated by CI), JDK setup, caches, uploads, path filters (locally every job
runs), the tree-hash reuse and the job summary.

### Before a merge: run the build and verification jobs on the branch in CI
Push the branch first (the gates run), then start the full run on the branch head:

```sh
gh workflow run ci.yml --ref <branch>
gh run list --workflow ci.yml --branch <branch> --limit 1   # find the run
gh run watch <run-id> --exit-status                          # wait for it; non-zero exit if a job failed
```

A newer push or a newer manual run on the same branch cancels the older run on that branch, so start the manual run
after the last push. A clean build without any Gradle cache (for example to rule out a cache problem):
`gh workflow run ci.yml --ref <branch> -f gradle-cache=disabled`. The fuzz workflow can be started the same way:
`gh workflow run fuzz.yml --ref <branch>`.

### On `main`: tree-hash reuse
After a merge, the `gates` job always runs on `main`. The `build` and `verification` jobs are skipped only when
`scripts/ci-reuse.py` finds a run of `ci.yml` in this repository, at most 24 hours old, whose head commit has exactly
the same tree (`git rev-parse HEAD^{tree}`) and in which that job completed with success. A re-run of a `main` run
(attempt 2 or later) never reuses, and every attempt of a candidate run counts: a failure in any attempt is doubt. Trees are compared, never
commit IDs, so a fast-forward, a merge commit or a squash merge of an up-to-date branch reuses the manual run of the
branch, while a merge that changes the content (branch not up to date with `main`) runs the jobs again. The same tree
means the same sources, scripts and workflow file. The job is never skipped when anything is uncertain: no token, an
API or network error, an unexpected response, another run of the same tree in which that job failed or timed out, or
more candidates than the script inspects. The job summary of the `gates` job names the run that was reused. The
lookup uses the workflow's own token with `actions: read` for this one step. Its fail-safe behaviour is covered by the
planted cases in `scripts/test-gates.sh`.

### Jobs
- `.github/workflows/ci.yml` (Linux; no pull_request trigger while merges are local):
  - job `gates` (every push and manual run): data admission + secret scan, DCO, SPDX, API wording, wording lint (hook),
    internal-ID and internal-figure gates, SAST, dependency-verification switch check, OSV report of the build tooling
    (report only; runtime and published dependencies are gated in the build job), planted-violation self-tests,
    ONNX Runtime telemetry and locked-options gates, path filters, tree-hash reuse lookup on `main`.
  - job `build`: test fixtures (the reference model cached by the pin and re-verified in every build; derived models
    rebuilt and pinned, never cached), JVM and Android host tests with a result check, ABI check, Android lint,
    ktlint, module coverage gate and changed-lines coverage gate, iOS klib compilation, API docs, local publication +
    publication check, OSV gate on the published dependencies, fixture, model-leak and fuzz-harness controls,
    artifact path checks, model-leak check over every published artifact and the API docs. No fixture is uploaded.
    Also skipped on a branch whose every changed file (compared with its merge base with `main`) is `*.md` or under
    `docs/`; never for that reason on `main`.
  - job `verification` (only when `verification/` exists and files under `verification/`, `faceblur/`, `gradle/`,
    a Gradle script, `gradle.properties`, `gradlew*`, the CI workflow or `scripts/check-artifact-paths.*` changed;
    always on a manual run and on `main`): `./gradlew -p verification check` with a result check and a path check
    of its reports.
  - Artifacts (API docs, reports) are uploaded only when the gates job succeeded. When `main` reuses an earlier run,
    the API docs artifact of that run is the one for this tree.
  - Gradle caches (`gradle/actions/setup-gradle`, `cache-provider: basic`, the MIT-licensed provider; the default
    "enhanced" provider is a proprietary component): dependencies, wrapper and the Gradle build cache are restored in
    every Gradle job; they are written only on `main` and read-only on branches (action default), so a branch can
    never change what `main` restores. The reference fixture and the Kotlin/Native toolchain have their own caches.
- `.github/workflows/macos.yml` (manual trigger only while the repository is private): iOS simulator tests with a
  result check, release XCFramework (`--no-build-cache`, read-only Gradle caches), zip and SwiftPM checksum as a
  build artifact, after a model-leak check of the XCFramework.
- `.github/workflows/fuzz.yml` (manual, and on a push that changes the pre-parse walker, the exception it refuses
  with (and `ReadOnly.kt`), its unit, differential or cost tests, the test-model sources, the harness in
  `scripts/test-models/`, the fixture pins, the version catalog or the workflow; no schedule): fuzzes the ONNX pre-parse walker with a fixed seed and a random seed;
  both seeds are in the log and the run summary.
- Concurrency (all workflows): one group per workflow and ref; a newer run on a branch cancels the older one. Every
  run on `main` has its own group and is never cancelled.
