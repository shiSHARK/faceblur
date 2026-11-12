# 0006. Model packaging and integrity check

Status: Superseded by 0012 (2026-11-02). Previously Accepted (the owner approved embedding the model in the
published artifacts, the iOS integrity chain of decision 4 and decision 5).
Date: 2026-11-01 (revised 2026-11-01 after review; cache file written by atomic rename; accepted the same day, with
the public statement on the iOS runtime pin added to the Context; later the same day: two independent trust anchors
for the model pin; the integrity check uses one common SHA-256 implementation)

> **Superseded.** The project owner decided that FaceBlur ships no model: the app developer supplies it.
> ADR 0012 replaces decisions 1-5 below. It keeps from this ADR the common SHA-256 implementation (FIPS 180-4) and
> the rule that the runtime receives only bytes FaceBlur has read and checked immediately before use. The text below
> is the historical record and is not updated.

## Context

The library used one model in the YuNet layout, identified by its pinned SHA-256. Project rules:

- The model is loaded only from inside the library's own artifact, after its SHA-256 matches the pinned value. The
  library offers no API to load another model, so the runtime only ever parses this one known file.
- The model file is not committed to either repository unless the project decides otherwise.
  The public build needs no model; the maintainer's local gate supplies the test fixtures from a local copy
  and checks their SHA-256 (ADR 0001, decision 4).
- The project's distribution plan named "the model resource" as part of `faceblur-swift`; the owner replaced
  this with the decision below.

Public statement on the iOS runtime and the model (the README's iOS section uses the same words):

> On iOS, FaceBlur pins ONNX Runtime 1.20.0 (Swift package `microsoft/onnxruntime-swift-package-manager`, exact
> version) because later versions we tested (1.24.x) showed memory growth with every inference on devices with Apple
> A18 and M4 chips; FaceBlur loads only the model embedded in its own artifact after checking its SHA-256 (on
> iOS the model file written for ONNX Runtime is checked again immediately before it is loaded), and it offers no API
> to load any other model.

The release notes carry a matching "Known issue" entry about published advisories for the onnx version included in
the pinned runtime, for every release while the pin holds; its exact text is kept with the release checklist and
re-checked against the advisory database at each release.

Runtime facts (SOURCE):

- ONNX Runtime Android/JVM 1.31.0: `OrtEnvironment.createSession(byte[], options)` loads from bytes (used by the
  benchmark app).
- ONNX Runtime 1.20.0 Objective-C API (the iOS pin): `ORTSession` has only
  `initWithEnv:modelPath:sessionOptions:error:`, no initialiser from bytes
  (https://raw.githubusercontent.com/microsoft/onnxruntime/v1.20.0/objectivec/include/ort_session.h).
- SwiftPM does not process resources of binary targets; resources must belong to a source target of the package.
  Kotlin klibs have no supported way for a consumer app to receive a resource file (ASSUMPTION; consistent with
  KMP consumers on iOS having to bundle such files themselves).

Consumers to serve: Android (AAR), KMP apps on Android and iOS (klibs), Swift apps (XCFramework + `faceblur-swift`).

## Decision

**Embed the model bytes in the Kotlin binary for every target, generated at build time; on iOS write them to a
private file before creating the session.**

1. **Build.** A Gradle task reads the reference model file in `build/yunet/` (already SHA-checked against
   the maintainer's local pin file) and generates a Kotlin source file under `build/generated/` that holds the bytes as
   chunked string constants (each chunk below the JVM constant-pool limit) and the pinned SHA-256. The generated
   file is never in git; the sources jar of a publication does contain it.
2. **Integrity at runtime.** Every `FaceMasker` creation decodes the bytes, checks their size and computes SHA-256,
   then compares the hash with the runtime pin `YUNET_MODEL_SHA256`. A mismatch throws
   `FaceMaskException.ModelIntegrity` and no session is created. The digest is one SHA-256 implementation (FIPS 180-4)
   in common Kotlin, identical on every target, because the standard library has no common digest. It needs no
   platform API (no CoreFoundation or Objective-C objects on iOS). The iOS cache-file re-check of decision 4 uses the
   same implementation. It is tested on every target against the FIPS 180-4 test vectors, and on the JVM also against
   the platform's `MessageDigest`.
   **Two independent trust anchors.**
   - the maintainer's local pin file holds the size and the SHA-256; every local build
     step checks the file against it.
   - `YUNET_MODEL_SHA256`, a hand-written constant in the library's committed `commonMain` sources, is the value the
     runtime check compares with. It is never generated.
   The generator fails the build unless the two pins are equal and the runtime pin is declared exactly once. A unit
   test asserts that the generated constant (the hash written next to the embedded bytes) equals the runtime pin.
   A model change therefore needs two reviewed edits in two files, and a tampered or stale generated file fails both
   the test and the runtime check. The generated source is never taken from or written to the build cache, and
   release artifacts are built from a clean checkout without the build cache (build documentation).
3. **Android / JVM.** The verified bytes go straight to `createSession(bytes, options)`. No `Context`, asset or file
   is needed, which lets `FaceMasker` be created in `commonMain` (ADR 0002).
4. **iOS.** The Kotlin side writes the verified bytes once to
   `Library/Caches/io.github.shishark.faceblur/yunet-<first 16 hex digits of the SHA>.onnx` in the app's own
   container (POSIX calls, no Objective-C objects), re-reads the file and checks its SHA-256 on every creation,
   and only then passes the path to the registered ONNX Runtime bridge. If the file is missing or its hash differs,
   it is never truncated or written in place: the verified bytes go to a uniquely named temporary file in the same
   directory (created exclusively, mode `0600`), which is `fsync`ed and then renamed over the published name with
   `rename(2)` (atomic). The published name is then re-read and re-hashed before use. Several instances created in
   parallel can therefore never see a partly written file: the published name always refers to a complete file,
   either the old one or the new one. A stale temporary file from a killed process is ignored and removed at the
   next rewrite. The file is excluded from backup by location (Caches), may be purged by the system, and is
   recreated when needed.
   Residual window: between the check and ONNX Runtime opening the file, code inside the same app could replace it;
   code inside the app is trusted (it could call ONNX Runtime directly anyway).

   How this meets the rule "loaded only from inside the library's own artifact, after its SHA-256 matches": the
   bytes always originate inside the artifact (the embedded constant, checked first); the Caches file is only a
   carrier that ONNX Runtime 1.20.0 needs because it cannot load from memory; and the bytes ONNX Runtime receives
   are re-hashed from that file in the same creation, immediately before the path is handed over. No file the app or
   a user supplies is ever loaded, and a file that was not checked in the current creation is never used. Because
   the runtime does read a file outside the artifact, this reading of the rule was approved by the owner together
   with this ADR.
5. **`faceblur-swift` carries no model resource.** Swift apps get the model inside the XCFramework, KMP apps inside
   their klib-based framework. This replaces the model resource that the earlier distribution plan named for
   `faceblur-swift` (owner's decision).

## Alternatives considered

**A. Platform resources: Android asset in the AAR, SwiftPM resource in `faceblur-swift` (the distribution plan).**
- Pro: no generated code; the familiar resource mechanisms; ONNX Runtime iOS gets a bundle path directly.
- Con: requires committing the model file to `faceblur-swift` (not done; permanent once the repository is
  public); Android needs a `Context`, so `commonMain` cannot create a `FaceMasker`; KMP apps on iOS
  get neither the AAR asset nor the SwiftPM resource and would have to bundle the model themselves, which breaks
  "only from inside our artifact"; two integrity mechanisms instead of one.
- Rejected in favour of the decision (owner's decision).

**B. Java resource in the AAR's classes jar, read through the class loader.**
- Pro: no `Context`. Con: Android only; iOS still needs another route. Rejected.

**C. ONNX Runtime C API `CreateSessionFromArray` from the Swift glue on iOS (avoids the file).**
- Pro: no file in the container. Con: whether the pinned 1.20.0 Swift package exposes the C API to Swift is not
  verified; the measured setup uses the Objective-C API. Not chosen; can be revisited with the iOS runtime pin.

**D. App-supplied model file, loaded after the same SHA-256 check.** Pro: no model bytes in the artifacts. Con: gives
up "loaded only from inside our artifact" and adds setup for every consumer. Rejected.

## Consequences

Positive
- One mechanism and one integrity check for Android, KMP-on-iOS and Swift apps; no model in any git history.
- `FaceMasker` needs no platform parameters; the model cannot be swapped at runtime.

Negative
- Binary size grows by about the model size plus encoding overhead (ESTIMATE 0.25-0.5 MB per target; MEASURED by
  the performance-engineer per artifact).
- iOS writes a 233 KB file to the app's Caches directory (not a photo; privacy rule "no temp files of originals" is
  unaffected).
- The sources jar contains the generated model source.

## Verification

- Digest: the common SHA-256 matches the FIPS 180-4 test vectors on JVM, Android and iOS, and matches the JVM
  `MessageDigest` on random inputs of many lengths (including block boundaries).
- Trust anchors: a runtime pin that is missing, is not a string literal (for example one assigned from the generated
  constant), differs from the local pin file, or is declared twice fails the build;
  a generated constant that differs from the runtime pin fails the unit test (negative controls in the build's
  model-step self-test).
- Build: a modified model file in `build/yunet/` fails the build at the SHA check; a corrupted generated constant
  (test hook) makes `FaceMasker()` throw `ModelIntegrity` on JVM, Android host and iOS simulator. The test hook is an
  `internal` seam reachable only from the library's own tests, never public; the ABI dump review checks that no
  public or `@PublishedApi` symbol can supply or alter model bytes (that would be the "load another model" API this
  ADR excludes).
- iOS: deleting or altering the cached file between two creations makes the next creation rewrite it and pass; the
  session is never created from a file whose hash was not checked in the same creation.
- iOS concurrency: with the cache file deleted (and, in a second run, replaced by a truncated copy), N = 8 threads
  create instances at the same time, repeated 50 times. Every creation succeeds, no session creation fails, and a
  file-system trace shows that the published name is never opened for writing (only temporary names are written
  and renamed).
- Artifact checks (verification build): the AAR, klibs and XCFramework contain the generated model class; no
  `.onnx` file is tracked in either repository (`git ls-files`); `faceblur-swift` has no model resource.
- The YuNet reference test (ADR 0009) runs against the embedded bytes, not against a file path.
