# 0008. Notices packaging per artifact (mechanics)

Status: Accepted. The 2026-11-02 revision for "no model in any artifact" (ADR 0012: Context, decision 4,
Consequences, Verification) was accepted on 2026-11-02 after code review and security review.
Date: 2026-11-01 (revised 2026-11-01 after review; 2026-11-02: no model in any artifact)

## Context

Every published artifact must carry the library's `LICENSE` (Apache-2.0) and `NOTICE`, and the third-party notices
for what it contains or links (for example code derived from OpenCV, the Kotlin standard library and runtime and
kotlinx libraries statically linked into the XCFramework). FaceBlur ships no model (ADR 0012), so no artifact
carries a model or a model notice. Which texts are needed is maintained in
`licenses/INDEX.tsv`; this ADR decides only **how** the files get into each artifact and how that is checked. ADR 0001 decision 5 is refined here.

Facts (MEASURED 2026-11-01): AGP 9.4.1's default packaging excludes drop `/META-INF/LICENSE`, `/META-INF/NOTICE`
and their `.txt` variants only at the top level of `META-INF/`, files whose names start with `_` or `.`, and the
`META-INF/` sub-directories `com.android.tools`, `maven`, `proguard`, `services`; other sub-directories are kept.
Kotlin klibs are produced by the compiler; there is no documented way to add arbitrary files to them (ASSUMPTION).

## Decision

1. **Single source.** `licenses/` at the repository root holds one text file per component plus `licenses/INDEX.tsv`
   (component, version, licence identifier, file, which artifacts contain it). File names never start with `_` or
   `.`. The project maintains the contents; the build only copies.
2. **Per artifact:**

   | Artifact | Where the files go |
   |---|---|
   | AAR | `classes.jar` → `META-INF/io.github.shishark.faceblur/` (`LICENSE`, `NOTICE`, `licenses/*`) |
   | iOS klibs (Maven) | not inside the klib; in each target publication's `-sources.jar` and `-javadoc.jar` under the same `META-INF/io.github.shishark.faceblur/` path; the POM declares Apache-2.0 |
   | Kotlin metadata publication | its jar and `-sources.jar`, same path |
   | XCFramework zip | zip root: `LICENSE`, `NOTICE`, `licenses/` (the zip, not the `.xcframework` bundle) |
   | `faceblur-swift` | repository root: `LICENSE`, `NOTICE`, `licenses/`; the `FaceBlur` target also ships a privacy manifest resource whose content is set in review before release |
   | JVM jar | not published (ADR 0002); if built, same path as the AAR |

3. **Build wiring.** One Gradle task `packageNotices` stages the files; the Android JVM-resources, sources-jar,
   javadoc-jar and XCFramework-zip tasks depend on it. The XCFramework list of statically linked components is derived
   from the framework link inputs, so a newly linked library without an `INDEX.tsv` row fails the build.
4. **Integrator information.** The integration guide lists the third-party notices for what an app ships with
   FaceBlur (FaceBlur and ONNX Runtime with its third-party components), with links to the version-matched texts. It
   also states that the model the app supplies comes with its own licence and notices, which the app developer is
   responsible for; FaceBlur's notices do not cover it. That wording is approved before publication, like the wording
   about the example models (ADR 0012 decision 9).
5. **Fewer components.** kotlinx-serialization is not used in the library (ADR 0002), which removes it from every
   artifact.

## Alternatives considered

**A. Notices at `META-INF/LICENSE` / `META-INF/NOTICE` (top level).** Excluded by AGP's default packaging and merged
with other libraries' files. Rejected.

**B. Notices as Android assets.** Requires Android resources/assets packaging for the KMP library plugin and puts
text files into every app's `assets/`. Rejected; Java resources are enough.

**C. Post-processing klib zips to add files.** Unsupported format manipulation; may break tooling. Rejected.

## Consequences

Positive
- Notices survive into consumer APKs and the Swift distribution; klib consumers find them in the sources/javadoc jars
  and the POM (to be confirmed before the first release).
- A missing notice for a linked component fails the build instead of a review.

Negative
- KMP apps on iOS receive no notice files inside their binary (klibs cannot carry them), so the integration guide
  has to point those integrators to the texts.
- One more build task, and `INDEX.tsv` has to be maintained with every dependency change.

## Verification

- Verification build: unzip the minified release APK of the consumer app and assert
  `META-INF/io.github.shishark.faceblur/LICENSE`, `NOTICE` and every file listed in `INDEX.tsv` for the AAR.
- `unzip -l` of each published `-sources.jar`/`-javadoc.jar` and of the XCFramework zip against `INDEX.tsv`.
- Negative test: add a dummy static dependency to the framework without an `INDEX.tsv` row → the build fails.
- `faceblur-swift`: a test reads the privacy manifest from the built package's resource bundle.
- `licenses/INDEX.tsv` lists no model component, and the artifact leak check of ADR 0012 finds no model in any
  artifact (the sources jar included).
