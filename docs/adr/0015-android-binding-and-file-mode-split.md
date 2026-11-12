# 0015. Android binding without a setup call, and the file-mode API across common and Android code

Status: Accepted (2026-11-03)
Date: 2026-11-03 (revised 2026-11-03 after review: Java-visible internals of the platform target and the defaults,
per-call platform context in the seam, file mode on targets without file-mode parts, bounded reading of sources held
in memory, test evidence for phone-only code; the same day the project owner fixed the stream cap at 64 MiB with
the error `SourceTooLarge`; later the same day: path and `Uri` sources decoded from the file or provider, never
from a whole-input buffer; amended the same day after the reviews of the common file-mode orchestration: the seam
and the Java-hidden members as implemented, the order of the target refusals, a mask plan from `detectFile` bound
to the decoded image (decision 6), and the rules the platform codecs and writers must meet (decision 4); amended
the same day (project owner): decision 6 gets the error `SourceChanged`, `MaskPlan.withMasks`, the refusal of
plans without a binding in file mode and `PlanMasker`, the route for masks from the app's own detector without a
model; amended after the review of that batch: the fingerprint is held by a Java-hidden internal class, the frame
rule of `planFile` is warned about in its KDoc and the guide, and a small helper converts a box from the raw frame
into the plan's upright frame (project owner), `UprightBox`)

## Context

ADR 0001 promises Android and KMP consumers one Gradle line. ADR 0002 decision 2 says that `FaceMasker(model)` and
`DetectorModel.load` are usable from `commonMain` on every target, and that only iOS needs a registration step (the
Swift bridge of ADR 0010). ADR 0002 decision 4 fixes the file-mode names (`detectFile`, `maskFile`, `maskJpeg`,
`FileResult`, `OutputTarget`, `AndroidOutputTargets.newFile` / `overwriteNonAtomic`) and leaves one point open:
whether the Android targets are platform subclasses of the common `OutputTarget` or wrappers, "settled in the first
Android file-mode story against the Kotlin rules for sealed hierarchies across source sets".

The common code finds its platform parts through an internal registry: the model runtime, the model-file reader,
the optional platform digest and the memory probe of the mask stage (ADR 0012 decision 8, ADR 0013 decision 6).
Each slot is filled by an internal install call. The first Android runtime binding (ST-003) has such an install
call, but nothing calls it in a consumer app, so `FaceMasker(model)` on Android throws `RuntimeUnavailable` and
`DetectorModel.load(ModelSource.fromFile(...))` finds no file reader.

Forces:

- One Gradle line on Android; the KMP guide's code sample creates a `FaceMasker` from common code, so Android must
  need no platform-specific setup.
- Nothing new at app start. A library should not change an app's start-up or the other libraries' initializers
  (ADR 0007). Content providers are created on the main thread at application launch and "must not perform lengthy
  operations, or application startup will be delayed" (SOURCE: `android.content.ContentProvider.onCreate`
  reference, read 2026-11-03); Android creates them "in an undetermined order" (SOURCE: App Startup guide, read
  2026-11-03).
- Small, stable ABI in 0.x; every public symbol is reviewed through the ABI dump.
- Java callers: no default arguments, static entry points.
- `ORT_DISABLE_TELEMETRY=1` must be set before any ONNX Runtime class is initialised (its static initialiser loads
  the native library) and before its environment is created (ADR 0007 decision 2).
- The memory probe must be in place on Android before the first mask call; without it the mask stage has no
  pre-check (ADR 0013 decision 6).
- Kotlin rules (SOURCE: kotlinlang.org, read 2026-11-03): "direct subclasses of sealed classes must reside in the
  same source set" in multiplatform projects, unless the sealed class is an `expect` class; expected and actual
  classes are Beta and need `-Xexpect-actual-classes` to silence the compiler warning; expected functions and
  properties carry no such notice; "every expected declaration in the common source set has a matching actual
  declaration in every platform-specific source set".
- Members cannot be added to a common class from `androidMain` (that would make `FaceMasker` an expect class).

## Decision

### 1. Platform defaults through one expected function (no setup call, no app-start component)

- `commonMain` declares `internal expect fun platformDefaults(): PlatformDefaults`. `PlatformDefaults` is an
  internal common class with one nullable value per registry slot: model runtime, model-file reader, memory probe,
  platform digest, and the file-mode parts of decision 4.
- Each registry lookup returns the explicitly installed value if there is one, otherwise the platform default. An
  explicit installation therefore wins: tests install fakes, and the iOS bridge registration of ADR 0010 fills the
  runtime slot exactly as before. An internal, Java-hidden test switch turns the defaults off, so common tests can
  still run with nothing installed.
- Kotlin `internal` declarations are public in JVM bytecode. `platformDefaults()` and the test switch are therefore
  `@JvmSynthetic`, so Java code cannot call them, and the JVM boundary test checks it (Verification). As
  implemented, `PlatformDefaults` has a private constructor; its factory, its getters and its empty value `NONE` are
  Java-hidden as well, so Java code can neither build nor read one.
- Actuals:
  - **Android**: the ONNX Runtime binding, the model-file reader, the remaining-heap memory probe
    (`HEAP_REMAINING`) and the Android file-mode parts; no platform digest (the common SHA-256 stays until the phone
    measurement of ADR 0012 decides otherwise).
  - **JVM** (tests and tools, not published): none; tests and the evaluation tool install their bindings
    explicitly, as today.
  - **iOS** (`iosMain`): no runtime (the Swift bridge registers it, ADR 0010, unchanged); the memory probe, the
    model-file reader and the file-mode parts are filled in by the iOS stories, until then none.
- The defaults are stateless Kotlin `object`s, created by the class loader on first use. Nothing runs at app
  start; the first `DetectorModel.load` or `FaceMasker` creation reaches them. There is no once-flag to get wrong:
  every lookup returns the same immutable values.
- **Telemetry order.** The objects reachable from `platformDefaults()` reference no ONNX Runtime type. The Android
  runtime binding sets `ORT_DISABLE_TELEMETRY=1` and reads it back as the first step of opening a session, before
  any ONNX Runtime class is initialised (its static initialiser loads the native library) and before its
  environment is created; ONNX Runtime types are reached only after that, behind the session factory (ADR 0007
  decision 2). A failed read-back fails creation with `RuntimeUnavailable`.
- **Targets without file-mode parts.** Where the defaults hold no file-mode parts and none are installed (the JVM;
  iOS until the iOS stories), `detectFile` and `maskFile` fail closed with `RuntimeUnavailable` before the source is
  opened; the message names file mode on this platform, and nothing is written.
- **No public symbol.** Java and Kotlin callers write nothing; the ABI dump does not change for this decision.

### 2. `OutputTarget`: one common sealed class with an opaque platform subclass

```kotlin
public sealed class OutputTarget {
    public class NewFile @JvmOverloads constructor(public val path: String, public val replaceExisting: Boolean = false) : OutputTarget()
    public object OverwriteSource : OutputTarget()
    /** Made only by the platform factories (Android: AndroidOutputTargets); other values are refused. */
    public class PlatformTarget private constructor(@get:HiddenFromJava internal val value: PlatformTargetValue) :
        OutputTarget() {
        internal companion object {
            @HiddenFromJava internal fun create(value: PlatformTargetValue): PlatformTarget = PlatformTarget(value)
        }
    }
}
```

- All direct subclasses live in `commonMain`, as the multiplatform rule requires. `PlatformTargetValue` is an
  internal common interface; Android implements it with the URI and the overwrite opt-in.
- `internal` does not hide a declaration from Java (it is public in JVM bytecode). The constructor of
  `PlatformTarget` is therefore private, and the only factory (`create`) and the `value` getter are Java-hidden, so
  Java code cannot construct a `PlatformTarget` or read its value. The interface `PlatformTargetValue` stays visible
  to Java, so as a second line of defence the Android code accepts only its own value type: it checks the type
  first, and any other value makes the call throw `UnsupportedTarget` before any work (never a
  `ClassCastException`). The KDoc says that these targets are made only by the platform factories and that other
  values are refused.
- Equality: `NewFile` has written-out `equals`/`hashCode` on `path` and `replaceExisting` (no data class, as for
  the results and options of ADR 0002 decision 5); `OverwriteSource` is an `object`; `PlatformTarget` compares by
  its platform value, and the platform value defines `equals`/`hashCode` over the URI and the overwrite opt-in, so
  two targets made by the same factory for the same URI are equal.
- `AndroidOutputTargets` (an `object` in `androidMain`, `@JvmStatic` methods) keeps the names and behaviour of
  ADR 0002 decision 4 and ADR 0005 decision 4: `newFile(uri: Uri): OutputTarget` and
  `overwriteNonAtomic(uri: Uri): OutputTarget`, each returning a `PlatformTarget`.
- A target holds no `Context`; the `Context` comes with each call, so a stored target never keeps an `Activity`
  alive. A `PlatformTarget` passed to a call that has no `Context` (the common path form) throws
  `UnsupportedTarget` before any work, and the message names the `Context` form.
- `toString()` of every target names its kind only, never the path or URI (messages never contain paths).
- Not chosen: an expected sealed class (Beta, compiler flag, and an expect/actual pair for a type apps only pass
  along); Android subclasses (not allowed across source sets); a separate Android target type (every `maskFile`
  form would need a second target parameter type).

### 3. Placement of the public file-mode API

| Part | Source set | Shape |
|---|---|---|
| `OutputTarget` | commonMain | decision 2 |
| `FileResult` | commonMain | final class, internal constructor, written-out `equals`/`hashCode`/`toString`; fields of ADR 0002 decision 5 (`width`/`height` = working size, `sourceWidth`, `sourceHeight`, `outputWidth`, `outputHeight`, `outputBytes`, `faces`, `masks`, `timings`, `gainMapRemoved`, `warnings` as a read-only list) |
| `Detection.outputWidth`, `Detection.outputHeight` | commonMain | already public (equal to `width`/`height` in in-memory mode); `detectFile` sets them from the size plan; no ABI change |
| `FaceMaskException.UnsupportedTarget(reason)`, `FaceMaskException.SourceTooLarge(limitBytes: Long)` | commonMain | additive subclasses, ADR 0002 decision 9 |
| `FaceMasker.detectFile(source: String, options, cancellation)` and `FaceMasker.maskFile(source: String, target, options, plan, cancellation)` | commonMain | members, `@JvmOverloads` |
| File-mode orchestration | commonMain, internal | decision 4 |
| `PlanMasker(masking)` with `planFile(source: String, options, cancellation)` and `maskFile(source: String, target, plan, options, cancellation)`; `MaskPlan.withMasks(masks)`; `FaceMaskException.SourceChanged` | commonMain | decision 6; `@JvmOverloads` |
| `AndroidOutputTargets` | androidMain | decision 2 |
| `detectFile(context, source: Uri, options, cancellation)`, `maskFile(context, source: Uri, target, options, plan, cancellation)`, `maskJpeg(input: InputStream, output: OutputStream, options, cancellation)` | androidMain | extension functions on `FaceMasker` in package `io.github.shishark.faceblur`, file annotated `@file:JvmName("FaceMaskerAndroid")`, `@JvmOverloads`; Java calls `FaceMaskerAndroid.maskFile(masker, context, uri, target)` |
| `planFile(context, source: Uri, options, cancellation)` and `maskFile(context, source: Uri, target, plan, options, cancellation)` | androidMain | extension functions on `PlanMasker` in the same `FaceMaskerAndroid` file class (decision 6); Java calls `FaceMaskerAndroid.planFile(planMasker, context, uri)` |
| Codec, path writer, content-URI writer, stream source and sink | androidMain, internal | decision 4 |

- The `Context` forms accept every target: `NewFile`, `OverwriteSource` (refused for a `content:` source, ADR 0005
  decision 4) and the Android targets.
- Kotlin callers write `masker.maskFile(context, uri, target)`, as in ADR 0002 decision 4. The same file later
  holds the `Bitmap` overloads of ADR 0002 decision 3, so Android has one Java entry class.
- An Android file path is passed as a `String` (`file.path`); there is no separate `java.io.File` form.
- 0.1.0 has no coroutine or `CompletableFuture` wrappers (deferred to 0.2, ADR 0002 decision 8 as amended); the
  guide shows calling these functions from a coroutine with cancellation.

### 4. Internal seam between the common orchestration and the platform

The responsibilities below are fixed; names and signatures may be refined in the first change that implements
them, reviewed by both the common-code and the Android developer. The seam is internal and does not affect the ABI.

```kotlin
internal const val MAX_STREAM_SOURCE_BYTES: Long = 64L * 1024L * 1024L              // the single source of the stream cap
internal sealed class FileSource { /* Path(path: String), Bytes(bytes: ByteArray), Platform(value: PlatformSourceValue) */ }
internal interface PlatformSourceValue { val kind: String; val replaceableInOneStep: Boolean } // drives the OverwriteSource refusal
internal class CallContext(val platform: PlatformCallValue?)                         // per call only; Android: the ContentResolver
internal fun interface ImageCodec { fun open(source: FileSource, call: CallContext): OpenedImage } // format from first bytes; header only
internal interface OpenedImage : AutoCloseable {
    val format: ImageFormat                                                         // from the first bytes
    val size: Size                                                                  // oriented size from the header
    val hasGainMap: Boolean
    val metadata: PhotoMetadata?                                                    // typed values only; read for KeepAllowlist only
    fun decode(working: Size): WorkingImage                                         // once; orientation applied; sRGB; opaque; gain map dropped
}
internal interface WorkingImage : AutoCloseable {
    val pixels: MutablePixelImage                                                    // the same platform adapter as in-memory mode
    fun scaledTo(output: Size): WorkingImage                                        // filtering scaler, masked pixels only
    fun encodeJpeg(quality: Int): ByteArray                                         // gain map cleared on the encoded image
}
internal interface TargetWriter {
    fun check(target: OutputTarget, source: FileSource, call: CallContext)          // before decoding; creates and opens nothing for writing
    fun accepts(value: PlatformTargetValue): Boolean                                // only the platform's own target type
    fun absolutePath(path: String): String                                          // lexical, no file-system lookup
    fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) // checks again, then writes once
}
```

- **Metadata data flow.** `OpenedImage.metadata` is read only when `MetadataPolicy.KeepAllowlist` is set; the
  orchestration restricts it to the named groups before the EXIF writer of ADR 0005 decision 3 serialises it. The
  codec fills it only through the typed, validated `PhotoMetadata` values, never with raw metadata bytes.
- **Java boundary.** Every member that takes or returns a seam type is Java-hidden; the JVM boundary test scans
  every class of the JVM main output for such members (not a fixed class list). An Android host variant of the scan
  follows with the Android codec and writer.

- **Per-call platform context.** The Android `Context` forms build one `CallContext` per call that holds the
  `ContentResolver` taken from the caller's `Context`; the codec reads content URIs and the content-URI writer writes
  them through it. It lives only for the duration of the call and is never stored in a default object, a target, a
  source or the `FaceMasker`. The common path form passes a `CallContext` without a platform value; that is how the
  orchestration recognises "a `PlatformTarget` without a `Context`".

The common orchestration runs ADR 0005 decision 1 in this order:

1. Without the platform's file-mode parts the call fails with `RuntimeUnavailable` before the source is opened
   (decision 1).
2. **Target refusals that need no I/O, before the source is opened**: `OverwriteSource` for a source that cannot
   be replaced in one step (a content source, a stream source), a `PlatformTarget` without a `Context`, a
   `PlatformTarget` whose value is not the platform's own type, and a `NewFile` path equal to the source path
   without `replaceExisting`; then a plan without a binding (`MaskPlanMismatch`, decision 6). They depend only on
   the call's arguments and read nothing.
3. Cancellation check; open the source: the format from the first bytes, the header only.
4. Plan the sizes from the header (`FileSizing.plan`); nothing is decoded yet.
5. The writer's pre-check (`TargetWriter.check`: refusals that need the file system, such as an existing file
   without `replaceExisting`) and the plan-size check (`MaskPlanMismatch`).
6. Cancellation check; decode once at the working size; close the opened source and release a stream buffer;
   check the decoded size.
7. The image fingerprint of decision 6: `detectFile` stores it in the `Detection` and `planFile` in the empty plan
   it returns (`planFile` ends here: no detection, no target, nothing written); `maskFile` with a plan compares it
   and refuses other pixels with `SourceChanged` (without a plan, none is computed).
8. Detect (no plan) and mask through the same internal function as `maskInPlace` / `applyMasks`.
9. Scale down (only to a smaller output, size checked); encode; check the encoded frame size against the planned
   output size; rebuild and validate (`JpegOutput.finish`).
10. Last cancellation check; write once; build the result from the validated output.

So an error of step 2 (`UnsupportedTarget`) takes precedence over `UnsupportedImage` and `ImageTooLarge`, which
need the source. Platform code only decodes, scales, encodes, reads and writes. `maskJpeg` uses a `Bytes` source
and a stream sink that writes the finished bytes once.

- **Rules for the platform codecs** (each has a test in Verification):
  - A path source is opened only if it is a regular file: the codec checks the path's type before opening and the
    opened descriptor's type after opening, and opens without blocking where the platform allows it, so a FIFO,
    socket, device or directory can neither block nor stream into the call. Any other type fails with
    `UnsupportedImage`, nothing decoded. A symbolic link to a regular file is read as that file (reading through a
    link changes nothing).
  - `decode` returns exactly the requested working size, and `scaledTo` exactly the output size; the common code
    checks both and fails closed (`Internal`) before anything is written.
  - `WorkingImage.pixels` is the live buffer that `scaledTo` and `encodeJpeg` read. The codec never takes a copy of
    the pixels before the masks are written, so the encoder always encodes the masked pixels.
  - `close()` drops every reference the codec holds to the source buffer of the stream form and to its decoder
    objects, so the stream buffer is really released before the mask stage (bounded-read rule above).
  - Metadata only as typed `PhotoMetadata` values (metadata data flow above).
  - Errors are `FaceMaskException` subclasses with fixed text and no cause or suppressed exception from platform
    code, because platform exception text can contain a path; this also holds for errors of `close()`.
- **Rules for the platform writers**: ADR 0005 decision 2 (file-system paths: never replace a symbolic link or a
  file with other names, `OverwriteSource` replaces exactly the decoded file, no-replace publication by hard link
  only, temporary file rules) and decision 4 (content URIs: probe, `"wt"`, full read-back). `check` creates and
  opens nothing for writing; `write` repeats the checks immediately before it publishes. The temporary file's name
  and path never appear in an error. Errors follow the codec rule above.

- The "path equal to the source path" check compares the two paths lexically after making them absolute and
  normalising them (no file-system lookup). It is an early refusal only, to fail before decoding. The authoritative
  guard stays the no-replace hard link of ADR 0005 decision 2, which fails for any existing file, also for one
  reached through another spelling or a symbolic link.
- **The stream source is bounded (project owner).** The stream form (`maskJpeg`) is the only form that FaceBlur
  reads into a memory buffer. It follows one rule: read the first bytes and check the format (ADR 0005 decision 7)
  before buffering the rest, so a stream that is not JPEG, HEIC or PNG fails with `UnsupportedImage` after its first
  bytes; then read at most **64 MiB** (67,108,864 bytes, the whole stream including any trailing data). The cap is
  fixed and not configurable in 0.1.0. A source above it fails with
  `FaceMaskException.SourceTooLarge(limitBytes: Long)` before any decoding; the output stream is not touched and
  nothing is written. The path and `Uri` forms are not capped by this limit: the platform decoder reads them from the
  file or the provider, and FaceBlur buffers nothing of them.
- **Path and `Uri` sources stay bounded without a whole-input buffer.** The path and `Uri` forms hand the platform
  decoder a file, file-descriptor or content-resolver source (for example `ImageDecoder.createSource(File)` or
  `ImageDecoder.createSource(ContentResolver, Uri)`), never a FaceBlur byte array of the whole input. The format
  check reads only the first bytes, and the header checks (the 200 MP source ceiling and `maxDecodedPixels`, ADR 0005
  decision 1) run before any pixel is decoded; a large source is decoded downsampled to the working size. The platform
  decoder's own memory is as in ADR 0005 decision 1. The current internal file-mode code, which reads the whole input
  into a byte array before decoding, changes to this rule in ST-012.
- **Message of `SourceTooLarge`.** Fixed English text that names the limit in bytes and tells the developer to use
  the file (path) or `Uri` form for larger inputs; it names no path and no size beyond the limit (which is not
  known). Documentation rule: the integration guide's stream-form section says the same (limit, and the file or
  `Uri` form for larger inputs).
- **Memory of the stream buffer.** The buffer is read in chunks and then joined into one array for the decoder, so
  while reading it needs up to about twice the source size for a moment, then once the source size. Every growth is
  reserved through the same memory check as the mask stage (ADR 0013 decision 6), with the process-wide count of
  the other running calls, so a source that does not fit in the memory available fails with `NotEnoughMemory`,
  never with an out-of-memory error. The buffer is released as soon as the image is decoded, before the mask stage
  reserves its working area, so the two are never held by one call at the same time. The rule is a common internal
  helper over a platform read function, so it is host-tested.
- **Parallel calls with the stream form.** The recommendation of at most 2 parallel masking calls on phones (ADR
  0013, the integration guide) holds for the file, `Uri` and in-memory forms and for stream sources of ordinary
  photo size. Two stream-form calls whose sources are near the cap can together need more memory than an app has on
  some phones and are then refused with `NotEnoughMemory`. Documentation rule: the guide recommends one stream-form
  call at a time for sources near the 64 MiB cap, or the file or `Uri` form for them. The guide's wording on parallel stream-form calls
  is to be checked against phone measurements after 0.1.0.

### 5. Test evidence for code that only runs on a phone

Some Android code can only run on a device, for example the calls into ONNX Runtime's native library or platform
behaviour that host tests do not reproduce. For it:

- A short, fixed list of phone-only files holds only those platform calls. All logic that can run off the phone
  (size planning, step order, format checks, the bounded read, target checks, error mapping, defaults) lives in
  common code or in Android code that runs in host tests (Robolectric), and the changed-lines coverage rule applies
  to it like to all other code.
- For the listed files, the coverage evidence is the on-phone test run (device-test APK with no photo data) for the
  exact commit, recorded with the pre-merge checks, instead of a line-coverage figure.
- Adding a file to the list needs approval by the QA lead and the code reviewer, with the reason why the code
  cannot run off the phone. The list lives in the coverage configuration of the build, so every change to it is
  reviewed.

### 6. A mask plan from `detectFile` is bound to the decoded image

`detectFile` and `maskFile(..., plan)` are two calls, and the source can change between them: replaced by another
photo of the same size, edited, re-encoded or rotated by another app. The plan's size check alone would then place
the masks where the faces were, not where they are, and `maskFile` would write faces of the new pixels unmasked
while reporting success. That is not a privacy-reliable option, so the second call fails closed instead.

- **Fingerprint of the working image.** Right after the decoded-size check (step 6 of decision 4), `detectFile`
  computes a fingerprint of the decoded working image: SHA-256 over a fixed version tag, the width, the height and
  every pixel in the platform adapter's channel order. The pixels are read in bands of bounded size, never as a
  second full-size copy. It uses the library's SHA-256 of the model check, including the platform digest where ADR
  0012 selects one, so there is no second implementation.
- **Carried internally, in a class that Java cannot reach.** The fingerprint is held by an internal regular class
  (called `ImageFingerprint` here), not a value class, so that no JVM signature erases it to a `ByteArray`. The
  `Detection` carries it; `toMaskPlan()` and `toMaskPlan(masking)` copy it into the plan they build (a "bound"
  plan), and `planFile` (below) returns a bound plan. It is never written to a file, logged, shown in `toString()`
  or part of an error message, and it is not part of `equals`/`hashCode`, so the documented equality of `Detection`
  and `MaskPlan` does not change. Kotlin `internal` is public in JVM bytecode, so `internal` alone would let Java
  read the fingerprint of a plan or build a `Detection` or `MaskPlan` with any fingerprint, which is the route that
  Alternative L rejects. Therefore, as for `PlatformTarget` and `PlatformDefaults` (decision 1), every member that
  takes or returns an `ImageFingerprint` is `@JvmSynthetic`, and any constructor that takes one is private or
  synthetic with a synthetic factory: the accessors of `Detection` and `MaskPlan`, the constructors, the factories
  and copy functions behind `toMaskPlan()`, `withMasks` and `planFile`. The public `MaskPlan` constructor
  `(width, height, masks)` stays and builds a plan without a fingerprint. The result: there is no Kotlin-public and
  no Java-callable way to read, set, compare, store or restore a fingerprint. A `Detection` from in-memory `detect`
  and a plan built with the public `MaskPlan` constructor carry no fingerprint. On iOS, Kotlin `internal` members
  are not exported to the generated Objective-C header (ASSUMPTION; the iOS story confirms it with a grep of the
  generated Objective-C header).
- **Checked before masking.** `maskFile` with a bound plan computes the same fingerprint on its own decoded working
  image (step 7 of decision 4), before the mask stage reserves memory. If the fingerprints differ, the call throws
  `FaceMaskException.SourceChanged`: nothing is masked, nothing is written, the target is untouched. The size check
  of step 5 still runs first, so a plan for another size fails with `MaskPlanMismatch` as before, without decoding.
  Cancellation is checked before and after the fingerprint. `FaceMasker.maskFile` and `PlanMasker.maskFile` use
  the same fingerprint, so each accepts a bound plan made by the other for the same source and options.
- **What it covers.** Any change of the pixels the plan was made for, also outside the masks (the whole image is
  covered, so a face added anywhere is caught), and also a plan without masks (a photo in which no face was found
  that is then replaced by one with faces). It does not depend on file names, paths, dates or provider metadata, so
  it works the same for paths, content URIs and every file system. The same rule applies to the Android `Context`
  forms and to the iOS file-mode forms.
- **Decoder determinism.** The check needs two decodes of an unchanged file with the same options to give the same
  pixels. That is expected of the platform decoders on one device (ASSUMPTION) and is TESTED on both test phones
  for JPEG, HEIC and PNG, one call after the other and with two calls in parallel, because a platform may choose
  another decode path (for example a hardware decoder or a software one) while it is busy (ASSUMPTION;
  Verification). A non-deterministic decoder would make the call fail (closed), never
  write unmasked faces; such a result is reported and needs a new decision, never a weaker check.
- **In-memory mode.** `applyMasks` does not check a fingerprint: the app holds the pixels and decides what it
  applies a plan to. Its KDoc says to apply a plan only to the image it was made for.
- **Cost.** One more read of the working image in each of the two calls (at the default working size of at most
  3,500 × 3,500 px, at most 49 MB of pixels), with no extra full-size buffer. Its time is taken from the release
  phone runs of the file-mode stories together with the other file-mode timings, not from instrumented test runs.

**Project owner's decisions on the error, edited plans and masks from the app's own detector:**

- **`SourceChanged` (new public error).** A changed source throws `FaceMaskException.SourceChanged`, a new
  subclass without properties, and not `MaskPlanMismatch`: the app's reaction differs, and adding a subclass after
  0.1.0 would break an exhaustive `when` (or Swift `switch`) in apps. Swift mirrors it as
  `FaceMaskError.sourceChanged` (ADR 0011 decision 4). The message is fixed English text with no path, size or
  value: "The source image changed after the mask plan was made; nothing was written. Make the plan again for the
  current file and review it before masking." It has no cause and no suppressed exception. KDoc rule (on
  `SourceChanged`, on both `maskFile` forms and in the integration guide): the file changed after the plan was
  made and nothing was written; run `detectFile` again on the current file (with masks from the app's own
  detector: `planFile` and the detector again) and, if the app shows the masks to the user for review, show the new
  result again before masking.
- **`MaskPlanMismatch` stays for plans that do not fit the image**: a plan whose size is not the working size, and
  in file mode a plan without a binding (next point).
- **Edited plans keep the binding: `MaskPlan.withMasks(masks: List<Mask>): MaskPlan`.** It returns a new plan with
  the same width, height and fingerprint (none if the plan has none) and the given masks, validated as by the
  constructor (count limit, `InvalidConfiguration("masks")`); the receiver is unchanged. It is the documented way
  to edit a plan in both modes (ADR 0002 decision 6). Swift: `MaskPlan.withMasks(_:)` (ADR 0011).
- **File mode refuses a plan without a binding.** `maskFile` (every form, on `FaceMasker` and on `PlanMasker`)
  throws `MaskPlanMismatch` for a plan without a fingerprint, after the target refusals and before the source is
  opened (decision 4 step 2), with the fixed message "The mask plan is not bound to a file; in file mode use a
  plan from detectFile or planFile, edited with withMasks. Nothing was written." So every plan that `maskFile`
  accepts was made by `detectFile` or `planFile` for the same, unchanged pixels. `MaskPlanMismatch` is chosen over
  `SourceChanged` because the source did not change: the plan was made the wrong way, a fault in the app's code
  that fails on every run and is fixed in code, while `SourceChanged` tells the app to ask its user again. In-memory
  `applyMasks` accepts every plan, as before.
- **Masks from the app's own detector, without a model: `PlanMasker`.** A public class in `commonMain` that needs
  no `DetectorModel`, no `FaceMasker` and no ONNX Runtime:

  ```kotlin
  public class PlanMasker @JvmOverloads constructor(public val masking: MaskingConfig = MaskingConfig()) {
      @JvmOverloads @Throws(FaceMaskException::class)
      public fun planFile(source: String, options: FileOptions = FileOptions(),
                          cancellation: CancellationToken? = null): MaskPlan
      @JvmOverloads @Throws(FaceMaskException::class)
      public fun maskFile(source: String, target: OutputTarget, plan: MaskPlan, options: FileOptions = FileOptions(),
                          cancellation: CancellationToken? = null): FileResult
  }
  // androidMain, file class FaceMaskerAndroid (Java: FaceMaskerAndroid.planFile(planMasker, context, uri))
  @JvmOverloads @Throws(FaceMaskException::class)
  public fun PlanMasker.planFile(context: Context, source: Uri, options: FileOptions = FileOptions(),
                                 cancellation: CancellationToken? = null): MaskPlan
  @JvmOverloads @Throws(FaceMaskException::class)
  public fun PlanMasker.maskFile(context: Context, source: Uri, target: OutputTarget, plan: MaskPlan,
                                 options: FileOptions = FileOptions(), cancellation: CancellationToken? = null): FileResult
  ```

  - `planFile` runs steps 1, 3, 4, 6 and 7 of decision 4 with the same decoder settings as `detectFile` (format
    check, header checks, size plan, one decode at the working size with the orientation applied, fingerprint) and
    returns an empty, bound plan whose `width` and `height` are the working size. It detects nothing, takes no
    target and writes nothing. Its coordinate space is that of every file-mode plan (ADR 0002 decision 4): pixels
    of the working image, the source turned upright by its orientation tag (as a photo viewer shows it), top-left
    origin, scaled to the working size. The app converts its detector's boxes into that space (for example with
    `Mask.manual(left, top, right, bottom, masking)`, which adds the same expansion as for detected faces) and adds
    them with `withMasks`.
  - **Frame warning (KDoc of `planFile` and the guide).** A detector that decodes the file without applying its
    orientation tag (Android's `BitmapFactory` and a raw `CGImageSource` image without the transform do this) gives
    boxes in the stored, unrotated frame. Used as they are, the masks land in the wrong place while `maskFile`
    reports success, so the faces stay visible. The KDoc says so in this wording: the boxes must be in the plan's
    frame (upright, after the orientation tag, top-left origin), and a box from the stored frame is converted with
    `UprightBox.fromRaw` (next point) or the detector decodes upright (for example Android's `ImageDecoder`, which
    applies the tag).
  - **`UprightBox.fromRaw` (project owner's decision on the plan frame).** A small, pure, public helper converts a
    box from the raw (unrotated, stored) frame into the plan's upright frame, from the raw image size and the
    orientation value 1 to 8 (the EXIF and ImageIO numbering: 1 upright, 2 mirrored horizontally, 3 turned 180
    degrees, 4 mirrored vertically, 5 transposed, 6 turned 90 degrees clockwise, 7 transversed, 8 turned 270 degrees
    clockwise). It reads no file, needs no `PlanMasker` and no model, and has no state.

    ```kotlin
    public class UprightBox private constructor(
        public val left: Float, public val top: Float, public val right: Float, public val bottom: Float,
        public val imageWidth: Int, public val imageHeight: Int,          // size of the upright frame, in the units of the raw size
    ) {
        public companion object {
            @JvmStatic @Throws(FaceMaskException::class)
            public fun fromRaw(left: Float, top: Float, right: Float, bottom: Float,
                               rawWidth: Int, rawHeight: Int, orientation: Int): UprightBox
        }
        // equals, hashCode and toString over the six values
    }
    ```

    Java: `UprightBox.fromRaw(l, t, r, b, rawWidth, rawHeight, orientation)` (a static method through
    `@JvmStatic`), getters `getLeft()` ... `getImageHeight()`. Swift: `public struct UprightBox: Sendable, Equatable`
    with the same six `let` properties and `static func fromRaw(left:top:right:bottom:rawWidth:rawHeight:orientation:)
    throws -> UprightBox` (ADR 0011). Rules: the box is transformed by its two corners and the edges are put in order
    again, so the result has `left <= right` and `top <= bottom`; for orientations 5 to 8 the upright frame is
    `rawHeight` wide and `rawWidth` high. The box may lie partly outside the frame (the factories of `Mask` bound
    and check coordinates later, decision 9 of ADR 0002). An orientation outside 1 to 8 (also 0, "undefined" in
    EXIF), a raw size of 0 or less, or a NaN or infinite value throws `InvalidConfiguration` with the parameter
    name (`"orientation"`, `"rawWidth"`, `"rawHeight"`, `"box"`); nothing is guessed. A file whose tag is missing
    is orientation 1, which the app passes; the helper does not read the file. The result is in the units of the raw
    size; the app still scales it by `plan.width / imageWidth` and `plan.height / imageHeight`. The helper does not
    prove that the app read the tag of this file; a wrong value that keeps the frame size (180 degrees, a mirror)
    is not detectable from sizes (guide rule below). If the helper is not part of the first release, the same
    function is a code snippet in the guide, covered by the same eight-orientation test.
  - `maskFile` takes a required plan and runs decision 4 without detection. The masks are written with the style,
    shape rule, feather and colour of the instance's `MaskingConfig`, exactly as `FaceMasker.maskFile` writes the
    same plan with the same `MaskingConfig`; refusals, the fingerprint check, the output rules and `FileResult` are
    those of `FaceMasker.maskFile` (`FileResult.faces` is empty, `masks` are the plan's).
  - Parameter order: `plan` is required, so it comes before the defaulted parameters (`FaceMasker.maskFile` has it
    after `options` because it is optional there); Kotlin callers name it.
  - KDoc of `PlanMasker` and the guide: it detects nothing; it masks exactly the masks the app gives it, and every
    face outside the app's boxes stays visible in the output. `FileResult.faces` is empty.
  - `PlanMasker` holds only its immutable `MaskingConfig`: no session, no native resource, nothing to close (not
    `AutoCloseable`). It is thread-safe; parallel calls are allowed and reserve their memory through the same
    process-wide mask-stage count as every other call (ADR 0013 decision 6). Its calls block and follow the
    main-thread rule of `FaceMasker`'s methods (`MainThread`).
  - It reaches only the model-free parts of the platform defaults (decision 1): the file-mode parts and the memory
    probe, never the runtime slot, so no ONNX Runtime class is initialised and its native library is not loaded on
    Android, and on iOS no bridge registration is needed. The ONNX Runtime dependency itself stays in the published
    artifact (one dependency line, ADR 0001); removing it from an app is not supported in 0.1.0.
  - Where the platform has no file-mode parts, `planFile` and `maskFile` fail with `RuntimeUnavailable` before
    the source is opened, as for `FaceMasker` (decision 1).
- **Order of the two-call routes (documentation rule).** The binding covers the time between the decode of the
  first call and the decode of `maskFile`. With masks from the app's own detector, the guide therefore says: call
  `planFile` first, then let the detector read the current file, then call `maskFile`; a change after `planFile`
  is then refused. A detector run that read the file before `planFile` is not covered. Masks saved earlier (for
  example across the end of the app's process) must not be added to a new plan without detecting again or showing
  them to the user on the current image, because a new plan is bound to the current pixels, not to the ones the
  saved masks were made for.
- **Guide example (documentation rule).** The integration guide has an external-detector example on Android
  (Kotlin and Java) and in Swift: `PlanMasker()`, `planFile(source)`, the app's detector run on the file, each box
  converted into the plan's upright frame with `UprightBox.fromRaw` (or the detector decodes upright), a check that
  the detector image's aspect ratio matches the plan's, boxes scaled by `plan.width / imageWidth` (and the height
  factor), `Mask.manual(..., masking)` per box, `plan.withMasks(masks)`, `maskFile(source, target, plan)`, and the
  reactions to `SourceChanged` and `MaskPlanMismatch` above. The guide states the pitfall of the frame warning and
  that the aspect check catches a missing 90 or 270 degree turn but not 180 degrees or a mirror, which are right
  only when the tag value passed to the helper is the file's. The Kotlin form of the example:

  ```kotlin
  val planMasker = PlanMasker()
  val empty = planMasker.planFile(path)                       // working size; nothing detected or written
  val raw = myDetector.detect(path)                           // after planFile; stored frame, tag not applied
  val tag = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)   // 1..8; 0 is passed as 1 by the app
  val masks = raw.boxes.map { b ->
      val u = UprightBox.fromRaw(b.left, b.top, b.right, b.bottom, raw.width, raw.height, tag)
      val aspectError = abs(u.imageWidth.toDouble() / u.imageHeight - empty.width.toDouble() / empty.height)
      check(aspectError < 0.01) { "detector image does not match the photo's upright shape" }   // within 1 %
      val sx = empty.width.toFloat() / u.imageWidth
      val sy = empty.height.toFloat() / u.imageHeight
      Mask.manual(u.left * sx, u.top * sy, u.right * sx, u.bottom * sy, planMasker.masking)
  }
  val result = planMasker.maskFile(path, OutputTarget.NewFile(outPath), empty.withMasks(masks))
  ```

## Alternatives considered

**A. A library content provider (or an androidx.startup initializer) that installs the binding at app start.**
Pro: also sets the telemetry variable at launch. Con: a new component in every consumer app's manifest that runs
on the main thread at every launch, in every app process, also when FaceBlur is never used; providers start in an
undetermined order, so it would not reliably run before another library's use of ONNX Runtime either; apps that
remove providers or disable initializers would lose it, so the lazy path would be needed anyway; androidx.startup
adds a dependency. Rejected.

**B. A public `FaceBlurAndroid.install()` that apps call once.** Pro: explicit, Java-friendly. Con: no longer one
line; a forgotten call fails at the first creation; the KMP sample would need Android-specific code; a permanent
public symbol. Rejected for 0.1.0. If an explicit early hook is ever needed, it can be added in a minor version
without breaking anything, because the lazy defaults stay.

**C. Lazy install inside `FaceMasker` creation only.** Con: `DetectorModel.load(ModelSource.fromFile(...))` runs
before any creation and needs the file reader; a once-flag across several slots has to be thread-safe on every
target. Rejected in favour of decision 1, which needs no flag.

**D. Android targets as wrappers around a common target.** Con: two target types in every Android signature, and
`OutputTarget.OverwriteSource` would still be needed in common. Rejected (decision 2).

**E. Only document that the source must not change between `detectFile` and `maskFile`.** Pro: no cost, no
code. Con: a change made by another app or a sync client is not under the developer's control, and the failure is
silent (success reported, faces unmasked). Not privacy-reliable. Rejected (decision 6).

**F. Bind the plan to the file's size, modification time and inode.** Pro: one `stat`, no pixel read. Con: copy
and sync tools preserve modification times, some file systems store them with seconds of resolution, content URIs
have no inode and no reliable time, and a file rewritten in place keeps its inode. A changed photo can pass.
Rejected as the check; nothing of it is needed in addition to decision 6.

**G. Bind the plan to a SHA-256 of the source file's bytes.** Pro: independent of the decoder. Con: the platform
decoder reads path and `Uri` sources itself (decision 4), so the hash needs a second full read of the file, and the
file can still change between that read and the decoder's read, so it does not prove which pixels were masked. The
pixel fingerprint covers exactly the buffer that is masked and needs no second read of the file. Rejected.

**H. Detect again in `maskFile` and refuse a plan that misses a detected face.** Pro: also catches a changed
source. Con: the full detection cost a second time, and it would refuse the edit the plan exists for (the user
removing a false detection). Rejected.

**I. `MaskPlanMismatch` for a changed source (the recommendation before the owner's decision).** Pro: no new public
type. Con: the app cannot tell "the plan does not fit" (a fault in its code) from "the file changed" (ask the user
again), and adding the subclass after 0.1.0 would break exhaustive `when`/`switch` in apps. Rejected by the project
owner in favour of `SourceChanged`.

**J. `SourceChanged` for a plan without a binding.** Pro: one error for every refused plan in file mode. Con: it
would tell the app that the file changed when it did not, and lead it to ask its user again instead of fixing the
code that built the plan. Both errors fail closed; `MaskPlanMismatch` gives the right reaction. Rejected.

**K. `planFile` as a `FaceMasker` member, or a `FaceMasker` without a model.** Pro: no new class. Con: a member
needs a `FaceMasker`, which needs a model and an ONNX Runtime session, which apps with their own detector may not
have; a `FaceMasker` without a model would have detection methods that always fail. Rejected for `PlanMasker`.

**L. A public way to restore a binding (a serialisable plan with its fingerprint).** Pro: edited plans survive the
end of the app's process. Con: any caller could attach a fingerprint to masks made for other pixels, so the
refusal of unbound plans would no longer prove anything. Rejected; a plan is made again after a restart.

**M. One call with a callback that hands the decoded working image to the app's detector.** Pro: the detector
sees exactly the pixels that are masked, with no window between two calls. Con: a long callback inside a library
call, no review step for the user, and not the route the project owner chose for 0.1.0. A candidate for a later
version; it can be added without breaking anything.

**N. `planFile` returns the source's orientation value, and the app converts.** Pro: the app sees the tag the library
used. Con: more API without removing the main risk (the app must still convert correctly, for eight cases); the
fixed upright frame is the same frame as FaceBlur's own results. Rejected; the helper `UprightBox.fromRaw` does the
conversion once, with a test for all eight orientations.

**O. A `PlanMasker` method that takes raw-frame boxes and the orientation.** Pro: one call. Con: it mixes two
jobs (converting and planning), is a larger surface, and the conversion is useful without a `PlanMasker` (for
example for the in-memory `Mask.manual`). Rejected in favour of a pure helper.

## Consequences

Positive
- `implementation("io.github.shishark:faceblur:<version>")` is enough on Android: `DetectorModel.load`,
  `FaceMasker(model)` and file mode work from Kotlin, Java and common code without a setup call.
- No new manifest entry, no start-up work, no new public symbol for the binding.
- The file-mode orchestration is common and tested with fakes on every target; platform code is thin, so most of
  the file-mode logic is covered by host tests.
- Common-code and Android work can proceed in parallel once the public types and the seam exist.

Negative / risks
- `iosMain` needs an actual (returning nothing until the iOS stories), so the change touches the iOS source set and
  needs the iOS simulator smoke run.
- Common tests that assert "nothing installed" must switch the defaults off explicitly on Android hosts.
- Telemetry still depends on FaceBlur being the first code in the process to start ONNX Runtime (ADR 0007).
- `PlatformTarget` is a public name that apps see in a `when` over `OutputTarget`; KDoc explains it.
- `OutputTarget` is sealed, so a new subclass in a later version breaks the source of apps that use an exhaustive
  `when` over it; in 0.x that is a minor version bump with a CHANGELOG "Breaking" entry.
- A stream source above 64 MiB is refused even when its pixels are within the pixel limits; the path or `Uri`
  form, which the platform decoder reads without a FaceBlur buffer, is the way to process such a file. The same
  file can therefore succeed through the path form and fail through the stream form; the KDoc of `maskJpeg` says
  so.
- `maskFile` with a plan costs one more read of the working image in each of the two calls (decision 6), and a
  source that changed between the calls fails with `SourceChanged` instead of being masked; the app makes the plan
  again.
- More public surface: `SourceChanged`, `PlanMasker` (with its Android forms), `MaskPlan.withMasks` and
  `UprightBox`. Plans built with the public `MaskPlan` constructor work only in in-memory mode; a file-mode
  plan restored after the end of the app's process has to be made again (`detectFile` or `planFile`), and the masks
  shown again before they are added.
- The binding cannot cover an external detector run that read the file before `planFile`; the documented order
  (decision 6) closes that window for apps that follow it.
- An error that needs no I/O (`UnsupportedTarget` of decision 4 step 2) is reported before an error about the
  source (`UnsupportedImage`, `ImageTooLarge`), also when both apply.

Follow-ups
- ADR 0002 decisions 2 and 4 refer to this ADR; decisions 1 and 8 record the wrapper deferral (done with this ADR).
- The iOS stories fill the iOS actual (memory probe, file reader, file mode) under the same rule.
- ADR 0005 decision 1 and ADR 0002 decisions 4, 9 and 10 record the stream cap and `SourceTooLarge` (done with
  this ADR).
- The phone measurement of parallel stream-form calls (decision 4) is not covered in 0.1.0.
- Decision 6: ADR 0002 decisions 1, 4, 6 and 9 and its Verification, ADR 0005 Verification and ADR 0011 decisions
  2 to 4 record the project owner's decisions (done with this ADR); the KDoc of `MaskPlan`, `withMasks`,
  `detectFile`, both `maskFile` forms, `PlanMasker`, `UprightBox` and `SourceChanged`, and the integration guide's
  external-detector example, follow in the implementing change.

## Verification

- Android host test: with no install call, the registry returns the Android runtime binding, the model-file reader
  and a memory probe with rule `HEAP_REMAINING`; an explicitly installed fake wins; the test switch turns the
  defaults off.
- Android host test, fail closed without the install call: when the telemetry variable cannot be set (test seam),
  `FaceMasker(model)` throws `RuntimeUnavailable` and no session is opened.
- Android device test (a test app that only depends on the published AAR through the local Maven repository, one
  dependency line, no setup code): `DetectorModel.load(ModelSource.fromFile(...))`, `FaceMasker(model)` and
  `maskFile` succeed; `ORT_DISABLE_TELEMETRY` is `"1"` afterwards (ADR 0007 Verification).
- Unit test: a `PlatformTarget` on the common path form throws `UnsupportedTarget` before the codec is opened
  (fake codec counts zero opens); `toString()` of each target contains no path or URI; `NewFile` equality and hash
  follow `path` and `replaceExisting`.
- Android host test: a `PlatformTarget` whose value is not the Android type (a test implementation, as a Java app
  could write) throws `UnsupportedTarget` before the source is opened, not `ClassCastException`.
- JVM boundary test: it scans every class of the JVM main output; no public, non-synthetic member outside the seam
  types' own declarations takes or returns a seam type (among them `PlatformTargetValue`, `PlatformDefaults`, the
  codec and writer interfaces and `CallContext`), with no exception; `PlatformTarget` has a private constructor and
  its `create` and `value` getter are synthetic; `platformDefaults()`, the test switch, the `PlatformDefaults`
  factory, getters and `NONE` are synthetic. The members of the mask stage's reservation count and of its stage
  seams that could change the process-wide count or the memory probe are synthetic as well (ADR 0013 decision 6).
  A planted class with a Java-callable seam member makes the scan fail (negative control). An Android host variant
  of the scan covers the Android codec, writer and targets when they exist.
- JVM boundary test for the fingerprint (decision 6): the type `ImageFingerprint` is one of the scanned seam types:
  no public, non-synthetic member (method, constructor, field) of any class of the JVM main output takes, returns or
  stores it, and no public member exposes a `ByteArray` or `String` that the fingerprint is copied into. Reflection
  on the built classes confirms that the only non-synthetic public constructor of `MaskPlan` is
  `(int, int, List)` and that `Detection` has no non-synthetic public constructor or accessor with the type. Negative
  controls: a planted class with a public method that returns an `ImageFingerprint`, and a planted class with a
  public constructor that takes one, each make the scan fail. A Java source in the test sources that tries to call
  a `Detection` accessor or a `MaskPlan` constructor with the fingerprint does not compile (a compile-failure
  test, expected message checked).
- Refusal order (common tests with a fake codec and writer): a target refusal of decision 4 step 2 is reported
  before the source is opened (zero opens), also for a source that is not an image or is too large;
  `TargetWriter.check` runs after the header checks and before decoding and creates nothing (fake writer counts).
- Plan bound to the image (decision 6; common tests with a fake codec, Android host tests for the `Context` forms):
  - `detectFile` and then `maskFile` with `toMaskPlan()` on unchanged pixels succeed and write once.
  - The same with one pixel changed between the calls (same size), inside a mask and, separately, far outside every
    mask: the call throws `SourceChanged`, the mask stage is not entered, nothing is written (writer count 0), the
    target is unchanged; the same with a plan without masks. The message is the fixed text of decision 6, with no
    path, and the cause and suppressed list are empty.
  - A content-URI source whose provider returns other bytes of the same size at the second call fails the same way.
  - The fingerprint is absent from `toString()` and from every error message, and `equals`/`hashCode` of
    `Detection` and `MaskPlan` are unchanged (equal plans with and without a fingerprint compare equal).
  - Cancellation requested at the fingerprint step writes nothing.
  - On both test phones (device tests with generated, public-safe fixtures), `detectFile` then `maskFile` with the
    plan succeeds 20 times in a row for each of a JPEG, a HEIC (with and without a gain map where the platform
    produces one) and a PNG source, at the default working size and with `maxLongSide = 0`. A second run per
    format does the same with two calls in parallel (and with another decode in flight), because a platform may
    take another decode path while a hardware decoder is busy; a mismatch there is reported with the platform,
    format and options, as the decision requires. The fingerprint time is MEASURED in the release phone runs of
    the file-mode stories, not in these instrumented tests.
  - `withMasks` on a bound plan returns a plan with the same width, height and fingerprint (`maskFile` accepts it
    on the unchanged source) and the given masks; the receiver is unchanged; 65,537 masks throw
    `InvalidConfiguration("masks")`; on a plan without a fingerprint it returns a plan without one.
  - A plan without a fingerprint (public constructor, and `withMasks` of one) is refused by every `maskFile` form,
    on `FaceMasker` and on `PlanMasker`, with `MaskPlanMismatch` and the unbound-plan message before the source is
    opened (fake codec counts zero opens, writer count 0); a target refusal of decision 4 step 2 is reported before
    it; in-memory `applyMasks` accepts the same plan.
- Masks without a model (`PlanMasker`, decision 6; common tests with a fake codec and writer, Android host tests
  for the `Context` forms, the Android device test app of the first item, iOS simulator through the facade):
  - The tests run with no `DetectorModel` loaded and no `FaceMasker` created in the process. On the JVM they run in
    a test JVM whose class loader refuses and counts the requests for `ai.onnxruntime` classes, among them
    `ai.onnxruntime.OnnxRuntime` and `ai.onnxruntime.OrtEnvironment` (the native library is loaded in the static
    initialiser of the first): the count stays 0. In the Android device test the same two classes are not loaded
    afterwards (`findLoadedClass` per class name, which cannot enumerate classes) and the ONNX Runtime native
    library is not in the process's memory maps; the runtime slot of the registry is never read (test counter). On
    iOS no bridge is registered (registration count 0) and the route still succeeds.
  - Positive controls, so that a harness that cannot see ONNX Runtime does not pass by accident: in a separate test
    JVM (and a separate process on the phone) the same harness, after a `FaceMasker` is created or the runtime slot
    is read, sees the class requests (JVM: count above 0, or the refusal fires), loads the classes and finds the
    native library in the memory maps (Android), and the iOS registration count is above 0 after a `FaceMasker`.
  - `UprightBox.fromRaw` (common tests, no file, no model): a fixture raw frame of 4 × 2 px with a box around one
    asymmetric pixel is converted for each of the orientations 1 to 8; the expected upright box, the size
    (`imageWidth` and `imageHeight` swapped for 5 to 8) and the position of the marked pixel are given as a table
    written from the EXIF definition, and the result of each orientation is mapped back with the inverse to the
    input. The edges are in order for every orientation (a box given with its corners in either order). An
    orientation of 0, 9 and -1, a raw size of 0, a NaN and an infinite value throw `InvalidConfiguration` with the
    parameter name. Agreement with the decoder (Android host and device, iOS simulator and device, a generated
    public-safe fixture): the same asymmetric image is stored with each of the eight tags; the box of the marker in
    the stored frame, converted with the tag and scaled by `plan.width / imageWidth`, lands on the marker in the
    upright working image that `planFile` and `maskFile` use (the mask's opaque core covers the marker pixels in
    the written file). The guide's example is compiled and run in the same test, with a detector double that
    returns stored-frame boxes, and refuses an upright-shaped detector image of the wrong shape.
  - `planFile` returns a plan without masks whose size is the working size, at the default options and with
    `maxLongSide = 0`; for a source with an orientation tag that turns it by 90 degrees, width and height are those
    of the upright image; no detection runs (detector seam count 0) and nothing is written (writer count 0).
  - Edited plan round trip: `planFile` → `withMasks` (manual masks) → `maskFile` writes once; decoded with an
    independent decoder, the opaque cores of the masks have the mask colour. `PlanMasker.maskFile` and
    `FaceMasker.maskFile` (same `MaskingConfig`, same plan) write byte-identical files, and each accepts a bound
    plan made by the other's first call for the same source and options.
  - Changed source: one pixel changed between `planFile` and `maskFile` (same size) throws `SourceChanged`; the
    mask stage is not entered and nothing is written.
  - A plan without a fingerprint is refused with `MaskPlanMismatch` before the source is opened (as above).
  - `PlanMasker` holds no reference to a call's `CallContext` after the call (seam test below), and two parallel
    calls on one instance succeed.
- Codec rules (decision 4), Android host tests and the iOS stories: a path to a FIFO, a directory and (where the
  test can create one) a socket fails with `UnsupportedImage` without blocking (a test timeout fails the test); a
  symbolic link to a regular image file is decoded; a codec test hook that hands the mask stage a copy of the
  pixels makes the end-to-end check fail (negative control); after `close()` no reference to the stream buffer is
  reachable from the codec (weak-reference check where the platform allows it); a platform exception with a
  path-like text in a codec or writer, also in `close()`, reaches the caller as a fixed-text error whose cause and
  suppressed list are empty.
- End to end on written files (Android host, device, iOS simulator and device): the written JPEG is decoded with a
  decoder independent of the library; the opaque cores of the plan's masks have the mask colour, the segment list
  is that of ADR 0005 decision 6, and there is no gain map and no MPF segment.
- Common test on a target without file-mode parts: `detectFile` and `maskFile` throw `RuntimeUnavailable` before
  the source is opened and write nothing.
- Seam test: after a call, no default object, target or source holds the call's `CallContext`.
- Bounded read (host tests with a counting stream): a stream that is not JPEG, HEIC or PNG fails with
  `UnsupportedImage` after its first bytes; a stream of exactly 64 MiB is read in full; a stream of 64 MiB + 1 bytes
  (a valid header followed by padding) fails with `SourceTooLarge(limitBytes = 67108864)` and zero bytes are written
  to the output stream; its message names the limit and the file or `Uri` form and contains no path; a stream whose
  buffer does not fit the memory check (a small budget through the memory-probe seam) fails with `NotEnoughMemory`,
  not with an out-of-memory error; the buffer's reservation is released before the mask stage reserves its working
  area (test hook records the reservation count).
- Path and `Uri` sources are not buffered (Android host test): a path source and a content-URI source above 64 MiB
  (a valid header followed by trailing padding) are processed without `SourceTooLarge`, and the bounded-read helper
  is not called for them (call counter = 0).
- Phone-only list (decision 5): the coverage check reads the list; a changed line outside the listed files is held
  to the changed-lines threshold; a listed file without an on-phone run for the exact commit fails the pre-merge
  check.
- Common tests with a fake codec and writer: step order of decision 4, nothing written on any failure or
  cancellation, the plan-size check, result fields against the fake's sizes.
- ABI dump: the change adds `OutputTarget` (three subclasses), `FileResult`, `UnsupportedTarget`, `SourceTooLarge`
  (with `limitBytes: Long`), the two common members, `AndroidOutputTargets` and `FaceMaskerAndroid`; no binding symbol. `Detection.outputWidth`/`outputHeight`
  are already in the dump and do not change.
- ABI dumps for decision 6: the change adds `FaceMaskException.SourceChanged` (no public constructor, no
  property), `MaskPlan.withMasks(List<Mask>)`, the class `PlanMasker` (its two constructors, `getMasking`, three
  overloads each of `planFile` and `maskFile`) and the class `UprightBox` (no public constructor, six getters,
  `fromRaw`) to the JVM, Android and Kotlin library (klib) dumps; the Android dump alone adds the
  `FaceMaskerAndroid` overloads of `planFile` and `maskFile` for `PlanMasker` (three each). `MaskPlan` keeps its
  constructor, and no symbol in any dump reads, sets, compares or serialises a fingerprint or has the type of the
  fingerprint class (checked by the boundary test above, because the dumps do not list internal members).
- iOS simulator smoke run with the new `iosMain` actual.
