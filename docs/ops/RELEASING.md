# Releasing FaceBlur

Status: current for 0.1.0. Every publication (Maven Central, GitHub Release, Swift package tag) needs the
maintainer's explicit approval at the time of release. Maven Central versions are permanent.

## Versioning
SemVer, `0.x` until the API is stable. One version for the Maven artifact `io.github.shishark:faceblur`, the
`FaceBlurCore.xcframework.zip` and the `faceblur-swift` tag. The version lives in `gradle.properties`
(`VERSION_NAME`).

## Credentials and signing (never in this repository)
Maven Central rejects unsigned artifacts. The build signs every publication automatically as soon as signing
properties are present, and every Maven Central task fails before uploading when they are missing.

Put the values in `~/.gradle/gradle.properties` (user level, outside the repository) or pass them as
`ORG_GRADLE_PROJECT_<name>` environment variables:

| Purpose | Property names |
|---|---|
| Central Portal user token | `mavenCentralUsername`, `mavenCentralPassword` |
| Signing, in-memory key (ASCII-armoured secret key) | `signingInMemoryKey`, `signingInMemoryKeyId`, `signingInMemoryKeyPassword` |
| Signing, key ring file (alternative) | `signing.keyId`, `signing.password`, `signing.secretKeyRingFile` |

## Release version
Before the release build, `VERSION_NAME` in `gradle.properties`, the constant `FaceBlurInfo.VERSION` and the version
the Swift package expects from the library (`Runtime.expectedLibraryVersion` in `faceblur-swift`) must all name the
release version: the Swift package refuses a library binary whose version differs from the one it was built for.
A build with `-PVERSION_NAME=<version>` changes only the Maven coordinates and the file names, not the constant
inside the binaries.

## Release candidate checklist
1. Clean checkout of the release commit; `./gradlew build` green locally; CI green (Linux) and one manual run of the
   macOS workflow green. Every build that produces release artifacts (Maven publication, XCFramework) runs from a
   clean checkout with `--no-build-cache` and without restored caches of other builds, so no cached task output
   (for example a cached compilation) enters a published artifact. The library ships no model; the test-model
   fixtures are re-checked in every build (never cached as derived files, never part of an artifact).
2. `./gradlew publishToMavenLocal` with signing configured; check the POM (name, description, url, licenses,
   developers, scm), the `.module` file, the sources and javadoc jars, and an `.asc` signature for every file
   (`gpg --verify`).
3. `sh scripts/check-artifact-paths.sh ~/.m2/repository/io/github/shishark faceblur/build/XCFrameworks/release
   faceblur/build/dokka/html` (artifacts, XCFramework and the API docs that may be published).
4. `./gradlew assembleFaceBlurCoreReleaseXCFramework`, then from the repository root:
   `sh scripts/package-xcframework-zip.sh faceblur/build/XCFrameworks/release
   faceblur/build/XCFrameworks/release/FaceBlurCore.xcframework.zip` (the zip holds the xcframework plus `LICENSE`,
   `NOTICE` and `licenses/` at its root; the last output line is the `swift package compute-checksum` value). The zip
   is deterministic: fixed file times, sorted entries, no extra attributes, and the slices in the XCFramework's
   `Info.plist` sorted by identifier (their order differs from build to build; the binaries are identical), so the same
   sources give the same bytes, also from a second build in another folder (run it twice and compare to be sure). The zip attached to the GitHub Release must be
   exactly the file whose checksum goes into `Package.swift`: never rebuild the XCFramework after computing the
   checksum, and recompute the checksum of the uploaded file before tagging `faceblur-swift`.
5. `sh scripts/check-publication.sh build/local-maven` (after `publishAllPublicationsToLocalRepoRepository`): no
   jvm variant (not published in 0.x), android and iOS variants present, no model file. Then the model-leak check
   over the same artifacts, the XCFramework (directory and zip) and the API docs, after
   `./gradlew :faceblur:deriveTestModels`:
   `python3 -I scripts/check-model-leak.py --fixture build/test-models/<reference model file>
   --fixture build/test-models/derived/G0.onnx --fixture build/test-models/derived/G2.onnx
   --fixture build/test-models/derived/G3.onnx build/local-maven ~/.m2/repository/io/github/shishark
   faceblur/build/XCFrameworks/release faceblur/build/dokka/html` must PASS (scan only the release version's
   folders in `~/.m2` if it holds older builds).
6. `python3 -I scripts/check-internal-figures.py --tree --no-temporary` must PASS: no block of internal-data
   wording near a number may still rely on a temporary allow-list entry (text that was to be reworded).
7. Licence notices present in every artifact: `./gradlew :faceblur:writeRuntimeLockfile` then
   `python3 -I scripts/licence-notices.py check --lockfile faceblur/build/osv/runtime.lockfile --repo build/local-maven`
   must PASS (index row with the same version for every runtime dependency; `LICENSE`, `NOTICE` and the texts inside
   the AAR, the root jar and the sources and javadoc jars). `unzip -l` of the XCFramework zip shows `LICENSE`,
   `NOTICE` and `licenses/` at its root, and `unzip -l` shows `PrivacyInfo.xcprivacy` in both framework slices
   (`ios-arm64`, `ios-arm64-simulator`; the Gradle build copies `faceblur/framework/PrivacyInfo.xcprivacy` there).
   Pre-publication audit clean; maintainer approval recorded.

## Publish (maintainer runs these after approval)
All targets are published from one macOS host in one invocation (Central rejects duplicate publications):

```
./gradlew publishToMavenCentral --no-configuration-cache
```

Then review and release the deployment in the Central Portal. The GitHub Release with the XCFramework zip and the
tags of both repositories follow the same approval.

## Rollback
Maven Central versions cannot be deleted or replaced: publish a fixed patch version and mark the bad version in the
CHANGELOG. A GitHub Release or tag is removed only with the maintainer's approval; a published Swift package tag is
never moved.
