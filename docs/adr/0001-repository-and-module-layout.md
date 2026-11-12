# 0001. Repository and module layout

Status: Accepted. The 2026-11-02 revision for "no model in any artifact" (the app supplies the model, ADR 0012:
Context, layout, decisions 3 and 4, alternative C, Consequences, Verification) was accepted on 2026-11-02 after code review
and security review. Amended 2026-11-03 to follow ADR 0007 as revised by the project owner: the manifest checks of
decision 3 and of Verification expect the permissions of the ADR 0007 matrix instead of "no network permission".
Date: 2026-11-01 (revised twice on 2026-11-01 after review; demo app paths aligned with the project's demo-app
requirement; 2026-11-02: no model in any artifact, see Status; 2026-11-03: manifest check aligned with ADR 0007)

## Context

FaceBlur is a Kotlin Multiplatform (KMP) library that runs a face-detection model supplied by the app through ONNX
Runtime, covers the detected faces with opaque masks and re-encodes the image without source metadata. It is extracted from an internal benchmark
app whose code is proven but organised for benchmarking: a pure-Kotlin `core` module, an `app` module with UI and
platform services, and a dynamic iOS framework.

Three kinds of consumers must be served:

| Consumer | Channel | Expected setup |
|---|---|---|
| Android app (Kotlin or Java) | Maven Central `io.github.shishark:faceblur` | one `implementation(...)` line; ONNX Runtime arrives transitively |
| KMP app | Maven Central, same coordinate, in `commonMain` | one line, plus on iOS: add the ONNX Runtime Swift package and register a Swift bridge |
| Swift / SwiftUI app | Swift Package Manager, repository `shiSHARK/faceblur-swift` | "Add Package", nothing else |

In every case the app also supplies its own face-detection model and its profile (ADR 0012); FaceBlur ships none.

Constraints already decided for this project:

- Targets `android`, `iosArm64`, `iosSimulatorArm64`; `jvm` for tests and tools only.
- ONNX Runtime on both platforms: Android `onnxruntime-android` 1.31.0 with the XNNPACK execution provider;
  iOS ONNX Runtime 1.20.0 through Swift Package Manager, called from a Swift glue class that implements a Kotlin
  bridge interface (`OnnxBridge`). Kotlin code never links ONNX Runtime on iOS.
- The Kotlin XCFramework is static. Swift apps use a hand-written Swift facade; Kotlin's Swift export is not used.
- KMP apps use the Maven artifact only, never the Swift package as well (two Kotlin runtimes in one app).
- Package and Android namespace `io.github.shishark.faceblur`; group `io.github.shishark`.
- No model is committed to either repository or contained in any published artifact (project owner's decision;
  ADR 0012). The internal reference model for tests (a model in the YuNet layout) is taken
  from a local copy by the maintainer's local gate, checked by SHA-256 and kept in a
  git-ignored path (ADR 0009). The public build does not need it.
- Every published artifact must carry the licence and the third-party notices for what it contains or links (for
  example ONNX Runtime, MIT, including its own third-party notices). The list is maintained in `licenses/INDEX.tsv`
  (ADR 0008); no model notice is needed, because no artifact contains a model.
- Both repositories are treated as public from the first commit.

Facts this ADR relies on (checked 2026-11-01 at the primary source):

- SwiftPM: `binaryTarget(name:path:)` accepts a local artifact or archive; `binaryTarget(name:url:checksum:)` needs
  an archive with the artifact at its root and its checksum; both are Apple-only
  (docs.swift.org, PackageDescription).
- Microsoft's `onnxruntime-swift-package-manager` at tag `1.20.0` declares `swift-tools-version: 5.6`, iOS 13+ /
  macOS 11+, the library product `onnxruntime` (target `OnnxRuntimeBindings`, wrapping a prebuilt static binary) and
  a URL/checksum binary target. Its manifest switches to a local binary when an environment variable
  (`ORT_POD_LOCAL_PATH`) is set; this ADR reuses that pattern.
  (https://github.com/microsoft/onnxruntime-swift-package-manager/blob/1.20.0/Package.swift)
- The ONNX Runtime Objective-C API at `v1.20.0` creates an `ORTSession` only from a model file path
  (`initWithEnv:modelPath:sessionOptions:error:`); there is no initialiser that takes model bytes.
  (https://raw.githubusercontent.com/microsoft/onnxruntime/v1.20.0/objectivec/include/ort_session.h)
- The KMP Android library plugin (`com.android.kotlin.multiplatform.library`) has Android resources off by default,
  supports extra asset directories through `androidComponents`, and publishes consumer keep rules only with
  `optimization.consumerKeepRules.publish = true` (developer.android.com, "Set up the Android Gradle Library Plugin
  for KMP", https://developer.android.com/kotlin/multiplatform/plugin).
- Kotlin: an XCFramework registers `assemble<baseName>ReleaseXCFramework`; only `api` dependencies can be
  `export`ed into a framework (https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html).
- SwiftPM tools 6.x offers `.iOS(.v18)` in `platforms` (PackageDescription interface of the Xcode 27 toolchain,
  checked 2026-11-01).
- AGP 9.4.1's default packaging excludes remove `/META-INF/LICENSE`, `/META-INF/NOTICE` (and `.txt` variants) only
  at the top level of `META-INF/`, plus files whose names start with `_` or `.`; sub-directories of `META-INF/` other
  than `com.android.tools`, `maven`, `proguard` and `services` are not excluded (default set read from the AGP 9.4.1
  plugin jar, 2026-11-01).

## Decision

### Repository `faceblur` (Gradle, published to Maven Central)

```
faceblur/
  settings.gradle.kts            includes exactly one project: :faceblur
  build.gradle.kts
  gradle/libs.versions.toml      version catalog (single source of versions)
  gradle/test-models.properties  fixture pins, tests only (maintainer's local gate; not in the public tree)
  faceblur/                      :faceblur, the only published module
    src/commonMain               API, model loading and checks, tiling, format decoders, NMS, mask renderer, geometry,
                                 pixel access
    src/commonTest
    src/jvmMain, src/jvmTest     JVM tensor runner for tests/tools; reference test with the internal fixture
    src/androidMain              ONNX Runtime runner, Bitmap/JPEG adapters, maskFile/maskJpeg
    src/androidHostTest          Robolectric (native graphics) tests
    src/androidDeviceTest        on-device tests where the platform matters
    src/iosMain, src/iosTest     OnnxBridge interface, CoreGraphics/vImage/ImageIO adapters
    api/                         ABI dump (reviewed in every change)
    consumer-rules.pro
  samples/                       separate Gradle build, NOT published (demo apps)
    android/                     Android demo in Kotlin, plus a small Java usage example in the same app
    kmp/                         Compose Multiplatform demo: shared module, Android host and an Xcode host app for
                                 iOS (ONNX Runtime SPM + bridge registration) inside samples/kmp/
  verification/                  separate Gradle build, consumes only the published artifacts
  testdata/                      public-safe fixtures, each registered with its source and licence
  tools/                         reference-data and fixture scripts (yunet_reference_pd.py and others)
  licenses/                      full texts of third-party licences and notices
  docs/                          architecture, adr, guides
  LICENSE  NOTICE  README.md  CHANGELOG.md  CONTRIBUTING.md
```

1. **One published KMP module, `:faceblur`.** Platform code lives in source sets, not in separate modules. The
   module name gives the artifact ID `faceblur`; Gradle module metadata maps the one coordinate to the AAR and the
   iOS klibs. ONNX Runtime Android is an `implementation` dependency of `androidMain`, so its types stay out of the
   public API but it still reaches the app at runtime. The `jvm` variant is built and tested but not published in
   0.x (ADR 0002 decision 11).
2. **Samples are a separate Gradle build** (`samples/settings.gradle.kts`) that declares the library exactly as a
   consumer does (`implementation("io.github.shishark:faceblur:<version>")`). By default it includes the library
   build (`includeBuild("..")`) so that coordinate is substituted with the project during development; a Gradle
   property switches it to resolve from the repository-local Maven directory instead, which is how the release
   candidate is accepted ("works from the local artifacts"). The demo apps are `samples/android` (Kotlin, with a
   small Java example) and `samples/kmp` (Compose Multiplatform for Android and iOS from shared code); the third
   demo, `Examples/SwiftUIDemo`, lives in `faceblur-swift`. CI builds the Android and JVM parts on every push; the
   iOS parts (the iOS host of `samples/kmp` and the SwiftUI demo) run in the macOS job, which is triggered manually
   and for release candidates while the repositories are private.
3. **The verification module is a separate Gradle build** that never uses `includeBuild`. It resolves the library
   only from the repository-local Maven directory (`build/local-maven/`, produced by publishing to a file
   repository; `mavenLocal()` stays available for the bench). It checks what consumers really get: one-line
   resolution per platform, notices present and no model in any artifact (leak check, ADR 0012), consumer keep
   rules applied in a
   minified build, the merged manifest as ADR 0007 specifies it (no ONNX Runtime telemetry provider; other providers
   and androidx.startup initializers present; permissions exactly as the ADR 0007 matrix: an app with FaceBlur only
   has the two ONNX Runtime permissions, an app with the app-side recipe has none), 16 KB
   page-aligned native libraries, and the output-file privacy test on public fixtures.
4. **Test-model location (tests only).** FaceBlur embeds and packages no model (ADR 0012).
   - A Gradle task obtains the reference test model from a local file supplied by the maintainer's local gate, checks
     its SHA-256 and size, and writes it to `build/test-models/source/` (git-ignored). The public build does not need
     it. A wrong hash fails the build.
   - A second task builds the derived test fixtures (ADR 0009 tier G) from it into `build/test-models/`. Their pins
     are checked, and they are never cached in CI.
   - Only test tasks read these files: JVM and Android host tests through system properties, device and simulator
     tests through test-only resources. No main source set, packaging or publication task depends on them, and CI
     artifact uploads exclude `build/test-models/`.
   - The data-admission check rejects `*.onnx` files in any commit, independent of `.gitignore`.
   - The artifact leak check (ADR 0012 Verification) proves that no artifact contains a model.
5. **Notices.** `LICENSE` (Apache-2.0) and `NOTICE` at the root of both repositories; full third-party texts in
   `licenses/`. The build copies `LICENSE`, `NOTICE` and `licenses/*` into every published artifact whose format can
   carry files: the JVM jar and the AAR's classes jar under a library-specific directory
   (`META-INF/io.github.shishark.faceblur/`, so Android packaging does not drop or merge them with other libraries'
   `META-INF/LICENSE` files), and the root of the XCFramework zip. The POM carries the Apache-2.0 licence. That the
   files under `META-INF/io.github.shishark.faceblur/` survive app packaging is supported by AGP's default exclude
   list (see the facts above) but remains an ASSUMPTION until the verification consumer's minified APK is checked
   (Verification). ADR 0008 settles the klib case and the exact file list.

### Repository `faceblur-swift` (Swift Package Manager)

```
faceblur-swift/
  Package.swift          swift-tools-version 6.x, platforms: iOS 18
  Sources/
    FaceBlurOnnxRuntime/ ONNX Runtime glue: implements the Kotlin OnnxBridge (imports FaceBlurCore, OnnxRuntimeBindings)
    FaceBlur/            hand-written Swift facade (the only public product)
  Tests/FaceBlurTests/
  Examples/SwiftUIDemo/  SwiftUI demo app, references the package by local path
  Binaries/              git-ignored; local FaceBlurCore.xcframework for development
  LICENSE  NOTICE  licenses/  README.md  CHANGELOG.md
```

Targets in `Package.swift`:

| Target | Kind | Depends on | Public |
|---|---|---|---|
| `FaceBlurCore` | `binaryTarget`, the static Kotlin XCFramework built by `faceblur` (`assembleFaceBlurCoreReleaseXCFramework`) | - | no (implementation detail) |
| `FaceBlurOnnxRuntime` | Swift target, ONNX Runtime glue | `FaceBlurCore`, `onnxruntime` from `microsoft/onnxruntime-swift-package-manager` pinned `exact: "1.20.0"` | no |
| `FaceBlur` | Swift target, facade; creates and registers the glue itself so Swift apps need no setup | `FaceBlurCore`, `FaceBlurOnnxRuntime` | yes: library product `FaceBlur` |

6. **Three targets, one product.** The glue is its own target so ONNX Runtime is imported in exactly one place
   (small review and security surface) and the facade stays independent of the runtime's Objective-C API.
7. **Binary target source.** One rule, in two phases:
   - Before the first release, `Package.swift` contains only `binaryTarget(path: "Binaries/FaceBlurCore.xcframework")`.
     It is not resolvable remotely; that is intended, because no consumer-resolvable tag may be created without the
     owner's approval.
   - From the first release on, `Package.swift` has a switch, following Microsoft's own ONNX Runtime package: with
     `FACEBLUR_LOCAL_XCFRAMEWORK` set, `FaceBlurCore` is the local `binaryTarget(path:)`; with it unset (every remote
     consumer), it is `binaryTarget(url:checksum:)` pointing at the zipped XCFramework attached to the GitHub Release
     of the same SemVer version (checksum from `swift package compute-checksum`).
   - A tagged revision therefore resolves remotely whenever the variable is unset. The release checklist checks this
     by resolving the tag in a clean checkout with the variable unset. The release ADR (follow-up, with devops) fixes
     which repository hosts the zip.

## Alternatives considered

**A. Split published modules** (`faceblur-core` common + `faceblur-android` + `faceblur-ios`, or a separate
`faceblur-onnx` runner module).
- Pro: hard compile-time boundaries; a second runtime could be swapped in later.
- Con: several Maven coordinates or an umbrella artifact, which breaks "one Gradle line"; every module needs its
  own ABI dump, notices and publication; the XCFramework must `export` the core module (only `api` dependencies can
  be exported); other runtimes and detectors are explicitly out of scope, so the flexibility has no user.
- Rejected. KMP source sets already separate platform code. Revisit only if a second runtime enters scope.

**B. Samples and verification as subprojects of the library build** (`include(":samples:android")`, project
dependencies).
- Pro: fastest edit-build loop; one Gradle invocation.
- Con: a project dependency does not exercise Gradle module metadata, the POM, the published consumer keep rules or
  notices, so it cannot prove the release acceptance criteria; application plugins in the same build can leak
  configuration into the library build; a broken sample blocks library CI.
- Rejected for verification. For samples, the composite build in decision 2 keeps the fast loop without losing the
  consumer view.

**C. Model committed to `faceblur` (resource or generated `ByteArray`) or to `faceblur-swift` (SwiftPM resource).**
- Pro: hermetic builds without a fetch step.
- Con: both repositories become public with their full history, so a committed binary cannot be removed later; the
  model is not committed by project decision.
- Rejected. Since the owner's later decision, FaceBlur ships no model at all, not even in its artifacts (ADR 0012).

**D. `faceblur-swift` with one Swift target (glue and facade together).**
- Pro: one fewer target.
- Con: ONNX Runtime types become visible across the whole facade; harder to review that telemetry and threading
  settings live in one place.
- Rejected; the cost of a second target is a few lines in `Package.swift`.

**E. Local-path-only or URL-only binary target.**
- Local path only: a remote consumer cannot resolve it unless the XCFramework (large binary) is committed. Rejected.
- URL only: no local development or release-candidate testing before a release exists. Rejected.
- The switch in decision 7 has a precedent in the ONNX Runtime package this project already depends on.

**F. `Package.swift` inside the `faceblur` repository.**
- Pro: one repository, one tag.
- Con: SwiftPM clones the whole Gradle repository for Swift consumers; Swift tags would follow the Kotlin tags'
  history; the project's distribution decision puts the manifest in a separate repository.
- Rejected (already decided for distribution; recorded here for completeness).

## Consequences

Positive
- Android and KMP consumers add one line; Swift consumers add one package and import one module, `FaceBlur`.
- One ABI dump and one publication to review per change.
- The verification build tests the artifacts consumers get, not the source tree.
- No model enters git history or any published artifact; a wrong or tampered reference fixture fails the test
  build.
- Library code is designed to make no network access at runtime (verified by the no-traffic tests on both
  platforms; ADR 0007 covers the Android manifest and telemetry settings).
  Builds do fetch two pinned, checksum-verified binaries: the ONNX
  Runtime archive that SwiftPM downloads for `faceblur-swift` and, for tests only and unless a local copy is
  configured, the internal reference model. "No network" in public text means runtime behaviour; offline builds and
  tests need both binaries cached or provided locally. The SBOM lists ONNX Runtime; the reference model is a test
  fixture and is not part of any artifact.

Negative / risks
- **iOS model delivery has a hard constraint.** ONNX Runtime 1.20.0's Objective-C API loads a model only from a file
  path. The app supplies its model as bytes or as a file (for example a resource of its own bundle). FaceBlur reads
  and checks it, then writes the verified bytes to a carrier file of its own that it creates for each session and
  removes once the session is open (ADR 0010 decision 6, ADR 0012). The runtime never receives the app's own path.
  `faceblur-swift` carries no model resource.
- **KMP consumers on iOS need setup**: add the ONNX Runtime package and register a Swift class implementing
  `OnnxBridge`. The glue in `faceblur-swift` imports the module `FaceBlurCore`, while a KMP app's framework has its
  own module name and must expose `OnnxBridge` to Swift (for example through `export`). Whether the same glue
  source can serve both consumer types is decided in ADR 0010 (one source; a documented template for KMP apps).
- The XCFramework covers device arm64 and Apple-silicon simulators only; Intel-Mac simulators are not supported.
- Environment-variable selection in `Package.swift` is not visible to Xcode when it is opened from the Dock; local
  development uses `xcodebuild` with an iOS destination from a shell or a launch environment. The shell direction is
  TESTED (SwiftPM smoke test of 2026-11-01: a local package built for an iOS device destination with the variable
  set, and failed as expected without it; internal log); the Dock-launched case is still an ASSUMPTION. Plain `swift build` cannot work because
  the XCFramework has no macOS slice.
- Building samples in local-artifact mode requires a publish step first; the composite mode is the default for that
  reason.

Follow-ups (each its own ADR)
- Public API shape (Kotlin common, Android extensions; the `jvm` variant is not published): ADR 0002.
- Model source, checks and limits (including the iOS file-path constraint): ADR 0012, which supersedes ADR 0006.
- iOS bridge registration for Swift and KMP consumers: ADR 0010.
- Swift facade (naming map to the Kotlin API, error enum, threading): ADR 0011.
- Notices packaging (exact files per artifact, klib case, ONNX Runtime's third-party notices): ADR 0008.
- ABI validation (tool, task names, review rule).
- Release hosting of the XCFramework zip and the SPM tag process.

## Verification

- `settings.gradle.kts` of `faceblur` includes exactly one project (`:faceblur`), and the publication task produces
  one root coordinate `io.github.shishark:faceblur` whose Gradle module metadata lists exactly the decided variants
  (Android and the two iOS targets; no JVM variant, ADR 0002).
- Samples (decision 2): `samples/` builds in composite mode, and again with the switch property set, resolving the
  library only from `build/local-maven/` (a build log showing no project substitution).
- Glue isolation (decision 6): in `faceblur-swift`, `grep -rl "import OnnxRuntimeBindings" Sources/` lists only files
  under `Sources/FaceBlurOnnxRuntime/`.
- The `verification` build resolves `io.github.shishark:faceblur:<version>` from `build/local-maven/` with one
  dependency line in an Android app and in a KMP `commonMain`, and its checks (no model in any artifact, a consumer
  test that passes a model the way an app does, merged manifest as the ADR 0007 matrix expects: no telemetry
  provider, other providers and initializers present, permissions per configuration, 16 KB
  alignment, output privacy test) pass. The notices check unzips
  the minified release APK of the verification consumer app and asserts that
  `META-INF/io.github.shishark.faceblur/` holds `LICENSE`, `NOTICE` and the third-party texts.
- `faceblur-swift`: `xcodebuild` for an iOS destination succeeds with the locally built XCFramework in `Binaries/`
  (and, from the first release on, with `FACEBLUR_LOCAL_XCFRAMEWORK` set); `Examples/SwiftUIDemo` builds and runs
  on a physical iPhone.
- The data-admission check rejects a test commit containing an `.onnx` file; `git ls-files` in both repositories
  lists no model file and no XCFramework.
- A modified reference fixture fails the test-model SHA-256 check; the artifact leak check fails on a planted model
  copy (negative control).

## Update (0.1.0)

Decision 3 still holds: the verification module is a separate Gradle build, with no `includeBuild` and no project
dependency. One test-only file dependency was added: the Android host-test compilation of `:faceblur`
(`androidHostTest`) puts the jar of the independent pixel oracle (`verification/pixel-oracle`) on its classpath, so
host tests can ask the oracle for a masked verdict on real Android-encoded output. The jar is built by the
a task that runs the repository wrapper on the verification build (`-p verification :pixel-oracle:jar`; the
verification build has no wrapper of its own) and handed to the test
compilation as a file, in the same way as the guide-snippet jar. It is on no main, published or runtime-dependency
configuration; the runtime-dependency check (ADR 0007, Update) reads only the published and runtime configurations, so
the jar can never reach an artifact or the allow-list. The oracle holds no library code and the library holds none of
the oracle.
