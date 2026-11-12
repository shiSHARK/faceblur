# 0007. Android: remove ONNX Runtime's telemetry provider; keep permissions to the app

Status: Accepted. Decisions 1, 3 and 4 revised 2026-11-03 by the project owner after the scoping test failed (see
Context); the earlier text (removal of the permissions, scoped to ONNX Runtime's package) is superseded. Decision 2
revised the same day: the variable is read back and creation fails closed with `RuntimeUnavailable`, the timing is
tied to the initialisation of ONNX Runtime's classes, and the binding is found without a setup call (ADR 0015).
Date: 2026-11-01 (revised 2026-11-01 after review: limit of the environment variable; revised 2026-11-03: the
provider is removed by its exact name without a selector, the library no longer removes `INTERNET` or
`ACCESS_NETWORK_STATE`, app-side recipe in the integration guide, test matrix C0-C4; after review the same day: the
on-phone no-traffic check runs in an app that has `INTERNET`, with a positive control, and pass criteria come from
the merged manifest and the APK only; 0.1.0: that on-phone check is dropped, see "Update (0.1.0)" at the end, and the
static build checks replace it)

## Context

The library's quality bar: no network code, no network call and no telemetry anywhere in the library.
`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, a transitive dependency of FaceBlur on Android, ships this
manifest (MEASURED, `AndroidManifest.xml` inside the AAR from Maven Central, 2026-11-01; re-read 2026-11-03: the
provider is a standalone `<provider>` element, not a `<meta-data>` entry of the shared androidx.startup
`InitializationProvider`):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.onnxruntime" >
    <uses-sdk android:minSdkVersion="24" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <application>
        <provider android:name="ai.onnxruntime.TelemetryInitializer"
            android:authorities="${applicationId}.onnxruntime_telemetry_initializer"
            android:exported="false" android:initOrder="100" />
    </application>
</manifest>
```

ONNX Runtime documents telemetry as on by default on Android; the environment variable `ORT_DISABLE_TELEMETRY=1`
set before initialisation is the full opt-out, and `OrtEnvironment.setTelemetry(false)` switches event collection
off after creation (ONNX Runtime `docs/Privacy.md` at v1.31.0). The provider runs at application launch (the
platform creates every registered content provider then; `android.content.ContentProvider.onCreate` reference), so
as long as it is in the merged manifest, ONNX Runtime is initialised before any FaceBlur code can set the variable.

Manifest-merger facts (SOURCE: developer.android.com/build/manage-manifests, read 2026-11-01): `tools:selector`
takes "the library package name"; the `remove` rule with a selector "is applied only when the lower-priority
manifest file is from" that library. The page's example uses `<permission>`, not `<uses-permission>`;
`<uses-permission>` elements and `<provider>` elements are merged by `android:name`. Library priority follows the
order in the app's `dependencies` block.

App Startup facts (SOURCE: developer.android.com/topic/libraries/app-startup, read 2026-11-03): initializers of
several libraries share the one `androidx.startup.InitializationProvider`; a single initializer is disabled by a
`<meta-data android:name="..." tools:node="remove" />` entry inside that provider (declared with
`tools:node="merge"`), while removing the provider element disables every initializer. Content providers "are
expensive to instantiate and can slow down the startup sequence", and Android initialises them "in an undetermined
order".

Experiments (TESTED in throwaway consumer projects):

- 2026-11-01: a library manifest with `tools:node="remove"` and no selector removes ONNX Runtime's permissions and
  provider from consumer apps, both as a project dependency and as a published AAR, but in one dependency order it
  also removed `INTERNET` declared by an unrelated library; a permission declared in the app's own manifest was
  always kept.
- 2026-11-03, Android Gradle plugin 9.4.1, ONNX Runtime 1.31.0: the scoped form (`tools:node="remove"` with
  `tools:selector="ai.onnxruntime"`, the text of the earlier decision 1) did not remove anything, in both dependency
  orders: the merged manifest contained `INTERNET`, `ACCESS_NETWORK_STATE` and `ai.onnxruntime.TelemetryInitializer`.
  The merger report listed ONNX Runtime's three elements as rejected and the library's own three removal elements
  as added declarations. The same result with the scoped form in the app's own manifest. Control: the same library
  manifest without the selector removed all three. The scoping test of the earlier decision 4 therefore failed, and
  the question went back to the project owner with this evidence.

## Decision

1. **Provider removed by its exact name, without a selector.** `faceblur/src/androidMain/AndroidManifest.xml`
   removes ONNX Runtime's telemetry provider and nothing else:

   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       xmlns:tools="http://schemas.android.com/tools">
       <application>
           <provider android:name="ai.onnxruntime.TelemetryInitializer"
               android:authorities="${applicationId}.onnxruntime_telemetry_initializer"
               tools:node="remove" />
       </application>
   </manifest>
   ```

   The merger matches `<provider>` elements by `android:name`, so the element removes exactly ONNX Runtime's
   provider, whatever the dependency order; no other provider has that class name. The library manifest declares no
   `tools:selector`, no `<uses-permission>` element and no other component.
   If a future ONNX Runtime version moves its initializer into the shared androidx.startup `InitializationProvider`,
   only its `<meta-data>` entry is removed (the App Startup pattern above); the `InitializationProvider` element is
   never removed or replaced, so other libraries' initializers (WorkManager, Lifecycle and others) keep running.
   This is re-checked at every ONNX Runtime version change (Follow-ups).
2. **Runtime.** Before any ONNX Runtime class is initialised (its static initialiser loads the native library)
   and before its environment is created, the Android runtime binding calls
   `android.system.Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)` and reads the value back; if it is not `"1"`,
   creation fails (`RuntimeUnavailable`) and ONNX Runtime is not started. After creating the environment it calls
   `setTelemetry(false)`. The classes that run before this point reference no ONNX Runtime type. The environment
   variable is process-wide; the integration guide says so (an app that also uses ONNX Runtime directly gets
   telemetry disabled too). How the binding is found without a setup call: ADR 0015.
3. **Permissions are not removed by the library.** FaceBlur does not remove `INTERNET` or `ACCESS_NETWORK_STATE`.
   An unscoped removal in a library manifest depends on the dependency order and can strip another library's
   permission, and the scoped form does not work (Context). An app that adds FaceBlur therefore gets the two
   permissions that ONNX Runtime declares, unless the app removes them. FaceBlur itself contains no network code;
   ONNX Runtime's telemetry provider is removed (decision 1) and telemetry is off (decision 2).
4. **App-side recipe in the integration guide.** For apps that must have no network permission at all, the Android
   guide gives the two lines for the app's own manifest (the app's manifest has the highest priority, so the
   removal applies to every library in the app):

   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       xmlns:tools="http://schemas.android.com/tools">
       <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
       <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
   </manifest>
   ```

   The guide says plainly that these lines remove the permissions for every library in the app, so an app whose
   own code or another SDK needs the network must not add them, and that they are the app's choice, not a FaceBlur
   requirement. The guide also states what FaceBlur does on its own (decisions 1 and 2) and that the merged
   manifest otherwise lists the two permissions because of ONNX Runtime.

## Alternatives considered

**A. Unscoped removal of the permissions in the library manifest.** Pro: proven to remove them. Con: depends on the
dependency order and can silently strip `INTERNET` or `ACCESS_NETWORK_STATE` that another library needs; an app
then breaks in a way that is hard to trace to FaceBlur. Rejected by the project owner.

**B. Keep the scoped form and wait for a merger change.** Con: ineffective with the build tools in use (Context);
the published library would carry removal elements that add declarations instead. Rejected.

**C. Repackage the ONNX Runtime AAR without its manifest entries.** Pro: nothing to merge away. Con: a modified
third-party binary to build, host, sign and update; consumers could no longer share the official artifact.
Rejected.

**D. Leave the provider in place and rely on the environment variable.** Con: the provider runs at app start,
before any FaceBlur code, so the variable would come too late for ONNX Runtime's own start-up. Rejected.

**E. Remove the whole androidx.startup `InitializationProvider`.** Not applicable to ONNX Runtime 1.31.0 (its
provider is standalone) and never acceptable: it would disable every other library's initializer.

## Consequences

Positive
- A consumer app that adds only FaceBlur has no ONNX Runtime telemetry provider, and FaceBlur switches telemetry
  off before ONNX Runtime starts, with no integrator action.
- No other library's permission or component is touched; the result does not depend on dependency order.

Negative / risks
- The merged manifest of an app that adds only FaceBlur lists `INTERNET` and `ACCESS_NETWORK_STATE` (from ONNX
  Runtime). Store privacy forms and permission reviews see them; apps that want none add the recipe of decision 4.
  The README and the integration guide describe this plainly and do not claim that the library removes them.
- `Os.setenv` changes the process environment.
- The environment variable has no effect if ONNX Runtime was already initialised in the process (by the app or
  another library) before the first `FaceMasker`; then only `setTelemetry(false)` applies. The integration guide
  says so.
- Depends on the merger matching providers by name; covered by the matrix below and re-run on every Android Gradle
  plugin and ONNX Runtime upgrade.

Follow-ups
- At every ONNX Runtime or Android Gradle plugin version change: re-read ONNX Runtime's manifest (provider name,
  standalone provider or androidx.startup `<meta-data>`, permissions) and re-run the matrix.
- The integration guide (Android section) carries decision 4 and the paragraph on what the merged manifest shows.

## Verification

Verification build (published AAR from `build/local-maven/`, not a project dependency). Pass criteria come only
from the merged result: the merged manifest XML of each consumer build and `aapt2 dump permissions` /
`aapt2 dump xmltree` of its release APK, never the library's manifest text. The manifest merger report is
informational only: in the 2026-11-03 tests it listed elements as added or rejected while the merged XML and the APK
showed a different result, so it is not a pass signal. Every consumer also depends on two test libraries: `startlib`,
which registers an initializer through androidx.startup's `InitializationProvider` (`<meta-data>` entry), and
`provlib`, which declares a standalone provider of its own. In every configuration the check asserts: ONNX
Runtime's `TelemetryInitializer` absent (except C0), `InitializationProvider` present with `startlib`'s `<meta-data>`
entry, `provlib`'s provider present, and the permissions in the last column.

| Consumer | Setup | Expected permissions (provider as stated above) |
|---|---|---|
| C0 (control) | test library identical to FaceBlur's Android artifact but without the manifest above | `INTERNET`, `ACCESS_NETWORK_STATE`; `TelemetryInitializer` present (proves the check can fail) |
| C1 | app + FaceBlur only | `INTERNET`, `ACCESS_NETWORK_STATE` (from ONNX Runtime) |
| C2a-d | app + FaceBlur + test library `netlib` declaring `INTERNET` and `ACCESS_NETWORK_STATE`: FaceBlur before/after `netlib`, FaceBlur as direct Android artifact and through the KMP root coordinate | both present |
| C3 | app declaring `INTERNET` itself + FaceBlur | both present |
| C4a | app with the recipe of decision 4 in its own manifest + FaceBlur | neither present |
| C4b | as C4a, plus `netlib` | neither present (the recipe removes them for every library, as the guide states) |

Plus: a device test proving that ONNX Runtime creates a session and runs inference without the provider; an
instrumented check that `ORT_DISABLE_TELEMETRY` is `1` in the process after the first `FaceMasker` creation, with no
setup call by the test (ADR 0015); a second instrumented case in which the test initialises ONNX Runtime before the
first `FaceMasker` (expected: the variable is set but cannot take effect, and `setTelemetry(false)` is still
applied).

On-phone no-traffic check (0.1.0: not run, see "Update (0.1.0)" below; the static build checks replace it). An app without `INTERNET` cannot open a socket, so a capture with such an app passes
whatever ONNX Runtime does. The check therefore runs in configuration C1 (an app with FaceBlur only, release build,
the two permissions from ONNX Runtime present):

1. Before the run, `aapt2 dump permissions` on the exact APK under test must list `INTERNET`; otherwise the run does
   not count.
2. Positive control: the same build, in a control run, opens one deliberate connection to a known host; the capture
   must show it. A capture that does not show the control makes the run invalid.
3. Measured run: the app loads a model, creates a `FaceMasker` and masks public test images in file mode and
   in-memory mode; the capture must show no connection and no DNS query from the app.

A run in C4 (an app with the recipe of decision 4) is optional and proves only the recipe. The library's own
device-test APK removes both permissions in its test manifest, so it is a C4-shaped app; its runs do not count as
the no-traffic proof on their own.

## Update (0.1.0)

The decisions above are unchanged. Two changes to the verification plan:

- **Runtime-dependency allow-list.** `scripts/check-runtime-deps.py` compares the modules of the library's resolved
  Android runtime classpath, and every dependency declared by the published `faceblur-android` and root `faceblur` POM
  and Gradle module metadata, with the reviewed list `config/runtime-deps/android.txt` (module and exact version). A
  module or version that is not listed fails the build, so a new runtime dependency is reviewed before it ships. A
  module whose coordinates name a networking, telemetry, analytics or crash-reporting library (HTTP clients, gRPC,
  Firebase and Play services, crash and analytics reporters and similar) is refused even when a list row exists, and a list
  row for such a module is itself a failure. The check also fails when the list holds a row the classpath does not use.
  The gate runs in the local merge gate as the `runtime-deps` step.
- **No further on-phone network check.** The "On-phone no-traffic check" described under Verification is no longer part
  of the 0.1.0 verification plan. The permission matrix C0-C4, the telemetry-provider and run-time telemetry checks, and
  the dependency check above remain. The ONNX Runtime permissions still reach an app that does not apply the recipe of
  decision 4, as decided above.
