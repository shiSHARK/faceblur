# 0010. iOS ONNX Runtime bridge: interface, registration, memory and errors

Status: Accepted (2026-11-02, after code and security review). The revision of the same day for the
developer-supplied model (ADR 0012: `open` with the input shape, output rows with type and shape, contract run
instead of the known-answer check, new status codes, the format lock and the locked option list, the error mapping,
the iOS timeout limitation) was accepted on 2026-11-02 after code review and security review. The model-from-memory
amendment of 2026-11-11 (decisions 1, 2, 3, 4, 5 and 6: the session is created from the verified bytes through the
ONNX Runtime C API, the carrier file is withdrawn, telemetry is disabled through the C API) records the design that
was built in the third milestone and replaces the carrier-file design (kept as history in decision 6). The first
build passed code review; the security review of the bridge, which this area requires, is still open, and this
amendment is not final until it is done.
Date: 2026-11-01 (revised the same day after code, security and feasibility review: exact output sizes and sentinels,
known-answer check at session open, `close()` during a call (never throws; release deferred), bounds contract,
runtime logging and options, template home and comparison, trust boundary, measured telemetry facts; 2026-11-02:
developer-supplied model, see Status; amended later: the required SHA-256 in the carrier-file check; 2026-11-11:
model from memory through the C API, see Status)

## Status note (0.1.0)

The iOS ONNX Runtime bridge is deferred to release 0.2: version 0.1.0 ships the Android artifact and the shared Kotlin
API only. This ADR stays Accepted and describes the planned design; it is not implemented in 0.1.0. The iOS targets of
the library keep compiling (`iosArm64`, `iosSimulatorArm64`) so that work on 0.2 starts from a green build.

## Context

On iOS, FaceBlur runs the detection model that the app supplies (ADR 0012) with ONNX Runtime 1.20.0 from the Swift package
`microsoft/onnxruntime-swift-package-manager` (exact version). The reason for the pin: later versions we tested
(1.24.x) showed memory growth with every inference on devices with Apple A18 and M4 chips. The runtime's iOS API is
Objective-C/Swift, so the Kotlin library cannot call it directly. A Swift "glue" object implements a Kotlin interface,
and the library calls that interface. The benchmark app's measured setup used the runtime's Objective-C API for this.
The glue of this ADR keeps the same Kotlin interface and the same runtime and execution-provider setup, but reaches the
runtime through its C API, in a small C target (`FaceBlurOrtShim`, decision 3), because only the C API can create a
session from memory.

Two kinds of consumers must get the glue:
- **Swift apps** use the `faceblur-swift` package (ADR 0001). Its target `FaceBlurOnnxRuntime` imports the C target
  `FaceBlurOrtShim` and the Kotlin XCFramework module `FaceBlurCore`, and the facade `FaceBlur` (ADR 0011) depends on
  both. The shim depends on the runtime package.
- **KMP apps** link the library into their own Kotlin framework, whose module name is their own. Swift code compiled
  against `FaceBlurCore` cannot conform to a protocol of another module.

Constraints from the earlier ADRs:
- ADR 0002: `FaceMasker(model, config)` is created from `commonMain`. A missing bridge throws
  `FaceMaskException.RuntimeUnavailable`, whose message names the missing setup step. One call at a time per
  instance. Fail closed: no face is ever left silently unmasked.
- ADR 0004 and the benchmark's iOS memory rules: no per-call Objective-C objects created in Kotlin or returned to
  Kotlin, because the Kotlin GC frees their wrappers late and does not see native memory. Buffers cross as address +
  size. `GC.collect()` runs after each public call.
- ADR 0012: the app supplies the model as bytes or a file path. Kotlin reads it into a library-owned buffer, checks
  its size, its required SHA-256 (checked before any parsing) and, in a bounded pre-parse, refuses external-data
  references, all before any byte reaches the bridge. The runtime never sees the app's path and no file is written:
  Kotlin hands the address and byte count of the verified, library-owned bytes to the glue, which creates the session
  from memory (decision 6).
- Privacy by construction: the library writes nothing to disk except the masked output (a temporary file of the masked
  output next to the destination, for a safe overwrite, is allowed). No cache, no carrier file of the model and no
  optimised-model file written by the runtime.
- Library rules: no logging of image data, paths or coordinates; no network; typed errors only.

Facts about the pinned runtime, checked against the 1.20.0 Objective-C headers and sources (SOURCE):
- `ORTVersion()` returns the version string of the linked binary.
- `ORTValue(tensorData:)` wraps `NSMutableData.mutableBytes` without a copy and keeps a strong reference to it.
- `tensorData()` of an output value is a view into memory that the value owns.
- Every Objective-C API method that calls into the C++ API turns C++ exceptions into `NSError`.
- `ORTLoggingLevel` goes up to `fatal`.
- `ORTSession` is created only from a file path (`initWithEnv:modelPath:sessionOptions:error:`) and exposes the names
  of its inputs and outputs, not their types or shapes. An output `ORTValue` exposes `tensorTypeAndShapeInfo`
  (element type and shape). This is the reason the first version of this ADR needed a carrier file (history in
  decision 6).
- The C API of the same binary (API version 20) has `CreateSessionFromArray` (a session from a model held in memory)
  and `DisableTelemetryEvents`. Its headers ship in the pinned package archive and the shim includes them through the
  package, so no header is copied (SOURCE: the headers of the pinned package; built and run in the third milestone).
  The telemetry call, unreachable from Swift as stated below, is reachable from a C target (decision 2).
- A reading of the v1.20.0 source shows that the runtime resolves external-data locations written in a model file,
  including a special in-memory address tag and absolute or parent-directory paths, without the checks later
  versions added (whether a crafted model reaches this code at session creation was not tested). FaceBlur therefore
  refuses every model with external data before the runtime sees it (ADR 0012 decision 4).
- XNNPACK registration and `runWithInputs:outputs:runOptions:error:` with pre-allocated outputs exist.
- There is no telemetry switch in the Objective-C API. The C API's `DisableTelemetryEvents` is not reachable from
  Swift, because the framework ships no module map.
- The Apple build of 1.20.0 contains no telemetry sender and imports no networking API (MEASURED by a symbol scan of
  the static library), and the runtime's privacy documentation at 1.20.0 describes telemetry as Windows-only.

The benchmark's bridge (`initialize(modelPath, threads)`, `run(NSMutableData, w, h) -> [String: Data]`) returns a
dictionary of `NSData` per pass, which violates the memory rules above.

## Decision

### 1. The Kotlin interface (iosMain, public, exported in the framework header)

```kotlin
public interface OnnxBridge {
    /**
     * Creates a session from the verified model bytes at [modelAddress] ([modelByteCount] bytes) and checks that
     * the session's input and output names equal the given ones. The memory is read only during this call; the
     * bridge keeps no reference to it. Returns 0 or a status code (decision 4).
     */
    public fun open(modelAddress: Long, modelByteCount: Long, threads: Int, inputName: String,
                    inputShape: LongArray, outputNames: List<String>): Int
    /** Runs one pass with the input shape given at open. All memory is owned by the library; returns 0 or a code. */
    public fun run(inputAddress: Long, inputByteCount: Long, outputTableAddress: Long): Int
    /** The linked runtime's version: the value of ORTVersion(), never a literal. */
    public fun runtimeVersion(): String
    /** The execution provider of the open session: "XNNPACK" or "CPU". */
    public fun executionProvider(): String
    public fun close()
}
public fun interface OnnxBridgeFactory { public fun create(): OnnxBridge }
public object FaceBlurIos {
    /**
     * Registers the factory once per process. Returns true if this factory is the registered one afterwards
     * (also when it was already registered), false if a different factory was registered earlier.
     */
    public fun registerOnnxRuntime(factory: OnnxBridgeFactory): Boolean
    /** The library's SemVer version, checked by the Swift facade (ADR 0011). */
    public val libraryVersion: String
}
```

The KDoc of `OnnxBridge` says that it is an integration seam for the published glue only, states the contract below,
and states the trust boundary of decision 5.

**Shapes are fixed per session.** The input shape is given at `open`: rank 4, float32, from the profile
(`[1, 3, H, W]` for NCHW, `[1, H, W, 3]` for NHWC). `inputByteCount` is the product of the shape × 4 for every pass.
Output buffers are allocated by Kotlin once per `FaceMasker`: from the output shapes the pre-parse found where they
are static, otherwise from the row and element caps of ADR 0012 decision 6. The contract run at creation records the
exact size of every static output. For the reference model (input 640 × 640, 12 outputs) that is 4,915,200 input
bytes and about 0.54 MB of outputs per pass.

**Output table layout** (a shared constant on both sides, with a layout test). One row per name in `outputNames`, in
that order. Each row is nine little-endian `Int64` values, 8-byte aligned: buffer address, capacity in bytes, bytes
written, element type (the runtime's enum value), rank, and four dimensions (unused dimensions 0). The glue reads
type and shape from the output value's `tensorTypeAndShapeInfo`, and writes them to the row **also when it returns
status 3** (wrong type, rank above 4 with the first four dimensions, or too large for the buffer), so that Kotlin can
name the cause. The row count is fixed at `open` and never read beyond.

**Per pass, Kotlin:**
1. copies the tensor into the input buffer;
2. sets every row's "bytes written" to −1 (sentinel) and fills every output buffer with NaN;
3. calls `run`;
4. requires status 0, element type float32, dimensions consistent with the profile, and "bytes written" equal to the
   product of the dimensions × 4, not above the capacity; for a static output also equal to the size recorded by the
   contract run.

Any other value means `ModelOutputInvalid` before any pixel is written (ADR 0012 decision 5). A glue that skips an output, writes short, or leaves
old data behind therefore fails the call. An unwritten region would also trip the non-finite check of ADR 0002.

**Per pass, the glue:**
- **Validates first.** Before touching any memory it checks: non-zero addresses; `inputByteCount` equal to the
  product of the shape given at `open` × 4; row capacities ≥ the output sizes it is about to copy. All arithmetic is
  overflow-checked; there are no force unwraps or trapping conversions on these values. A failed check returns a
  status code, never a crash. The shim does the same for `open` (non-zero address, positive byte count, no overflow).
- **Wraps the input without a copy.** The shim wraps the input address as a tensor with the C API's
  `CreateTensorWithDataAsOrtValue` (CPU memory information, no copy, no ownership). No Objective-C wrapper object is
  involved, so the earlier identity check on `NSMutableData` is not needed.
- **Runs and copies out.** It runs the session and copies each output into its row's buffer, then releases the output
  value. It writes "bytes written".
- **Keeps nothing.** It keeps no reference to library memory after returning.

Only primitives cross per pass, and the pass creates no Objective-C object at all: the shim works on C values, and
the Swift glue is a forwarder that passes addresses and counts. `String`/`List<String>` at `open` and the version
string are one-time objects per instance.

**One session per `FaceMasker`.** The factory creates one bridge per `FaceMasker`. One `ORTEnv` per process is shared
by all sessions, as the runtime recommends.

**`close()` and buffer release.**
- `FaceMasker.close()` never throws (ADR 0002 decision 8). If a call is running, `close()` marks the instance closed
  and returns. The running call finishes, and the release below happens when it returns, so buffers are never
  freed under the glue.
- `close()` is thread-safe and idempotent.
- The release order is: the bridge's `close()` (it takes the glue's lock), then the input and output buffers are
  zeroed and freed.
- A cleaner for instances that were never closed follows the same order.

**Optional optimisation (not the default).** The runtime's pre-allocated-output variant could write directly into
the library's buffers and save one copy per pass. It may be adopted only if the speed target is at risk, with the
same exact-size checks.

### 2. Runtime configuration in the glue (the measured setup)

- **Execution providers.** XNNPACK (registered through the C API) is used with `threads` worker threads (0 = automatic: min(4, active cores)), the
  runtime's own intra-op pool at 1 thread, and spinning off. If XNNPACK cannot be registered, or the session with it
  cannot be created, the CPU execution provider is used with the performance-core count, as in the benchmark. The
  provider actually used is reported by `executionProvider()` and is public in `FaceMasker.modelInfo` (ADR 0012). The
  Core ML execution provider and every other provider are not used.
- **Model, graph and locked options.** The model is created only from the verified bytes passed by the library
  (`CreateSessionFromArray`). The shim applies exactly the locked option list of ADR 0012 decision 4 (one list for
  both platforms). In particular it **sets** `session.load_model_format` = `ONNX` and
  `session.allow_released_opsets_only` = `1` as session configuration entries through the C API; if setting any
  required option fails, `open` returns status 7 and creation fails with `RuntimeUnavailable`. Graph optimisation
  level "all". Because the format is locked to ONNX and the `session.use_ort_model_bytes_*` keys are on the "never"
  list, the runtime parses the bytes during creation and has no mode in which it keeps referring to the caller's
  memory afterwards (SOURCE: the 1.20.0 session-option documentation; confirmed by the verification test below).
- **Runtime options that are never set or called:** the "never" column of ADR 0012 decision 4 (optimised-model
  output path, profiling, custom operators including `registerCustomOpsUsingFunctionPointer`, ORT extensions,
  external initializers and their folder, `ep.context_*`, `session.use_ort_model_bytes_*`, other execution
  providers). Only the `onnxruntime` product is linked, never `onnxruntime_extensions`; the glue does not call the
  runtime's Objective-C API. The grep check in `faceblur-swift` (`config/check-glue-sources.sh`) is an allow-list of
  configuration keys over the shim and the glue: any other key, or any call of the "never" list, fails the check.
- **Logging.** The environment and session logging level is `fatal`, so runtime messages, which can contain the model
  path, do not reach the console. The runtime's status objects are released without reading their messages. The shim
  and the glue have no `NSLog`, `os_log`, `print`, `printf` or `Logger`, and no network API. The check
  `config/check-glue-sources.sh` fails on these names in `Sources/`.
- **Telemetry (amended 2026-11-11).** The shim calls the C API's `DisableTelemetryEvents` on the environment it
  creates, so telemetry is off by an explicit call and not only by the absence of a sender in the Apple build. A
  failing call is handled like a failing required option: `open` returns status 7. The exact run-time version check
  (decision 4) stays as a second guard, because runtime versions that send telemetry from iOS are later than the pin.
  The earlier statement that the call is unreachable from Swift remains true for Swift and is why the C target
  exists. The proof that no traffic leaves the device is a symbol scan of the linked app binary for networking APIs
  plus an on-device network capture, both scheduled with the iOS stories.

### 3. Registration and the glue's home

- **Where the C API is called.** The C target `FaceBlurOrtShim` in `faceblur-swift` (a `.c` file and a public header)
  holds every call into the ONNX Runtime C API: environment, session options, session creation from memory, runs and
  telemetry. It depends on the runtime package's `onnxruntime` product and has no dependency on the Kotlin
  framework, so it compiles the same for Swift apps and KMP apps. It is a SwiftPM library product of
  `faceblur-swift` (ADR 0011 decision 7), not a file that apps copy.
- **Swift apps (automatic).** `faceblur-swift`'s target `FaceBlurOnnxRuntime` contains the canonical glue
  (`Sources/FaceBlurOnnxRuntime/OnnxRuntimeGlue.swift`: `OnnxRuntimeGlue` and `OnnxRuntimeGlueFactory`), a thin
  forwarder from the Kotlin interface to the shim. The facade
  registers it once per process in a lazily run static initialiser before the first `FaceMasker` is created, and
  checks the result (ADR 0011). A Swift app adds the package and does nothing else.
- **KMP apps (one call at start-up).** The Swift glue for KMP apps is a template:
  `faceblur-swift/Templates/KMP/OnnxRuntimeGlue.swift`. It is not part of any target. It differs from the canonical
  file only in its `import` line (the app's framework module instead of `FaceBlurCore`). The shim is not part of the
  template: the app links the shim product. A KMP app:
  1. adds `onnxruntime-swift-package-manager` at **exactly 1.20.0** to its Xcode project, and the `faceblur-swift`
     package of the matching FaceBlur version with only the `FaceBlurOrtShim` product linked (the facade and the Kotlin
     XCFramework are not linked: one Kotlin runtime per app, see below);
  2. exports the FaceBlur API in its framework, with the same notation as its `api` dependency (for example
     `export("io.github.shishark:faceblur:<version>")`; the exact snippet is fixed by the KMP sample), so that
     `OnnxBridge`, `OnnxBridgeFactory` and `FaceBlurIos` appear in its header;
  3. copies the Swift template of the matching FaceBlur version and changes the `import` line;
  4. calls `FaceBlurIos.shared.registerOnnxRuntime(factory: OnnxRuntimeGlueFactory())` once at start-up, before any
     shared code creates a `FaceMasker`.
  The integration guide lists these steps, and the KMP sample in `faceblur` does exactly this.
  - **Open point for the KMP-consumer template (ST-033, to be settled before it is built).** Whether adding the
    `faceblur-swift` package for one product leaves the facade and the `FaceBlurCore` binary target unresolved and
    unlinked is an ASSUMPTION about SwiftPM and Xcode behaviour; it is verified by a spike on a clean checkout and
    recorded here. The development form of the package has a path-based binary target that exists only after a local
    build, so a remote consumer can resolve it only once the released form (URL and checksum) exists, and a release is
    outside this ADR. If the spike fails, or until the released form exists, the fallback needs no new repository: the
    shim's two files are copied into the KMP app like the Swift template, `config/check-glue-template.sh` and
    `scripts/check-kmp-glue.sh` below then also cover the `.c` and `.h` files (the same comparison, no `import`
    exception), and the copy header names the source version and SHA-256.
- **Keeping the copies identical, without cross-repository credentials** (both repositories are private for now):
  - **Same repository, every push.** A script in `faceblur-swift` (`config/check-glue-template.sh`) compares the
    canonical glue with the template, ignoring only the `import` line (and, in the fallback form, the shim files with
    no exception). It runs in `faceblur-swift` CI on Linux; it is
    a text comparison and needs no macOS.
  - **Across repositories, at each milestone and release.**
    - The KMP sample's copy in `faceblur` starts with a header comment naming the `faceblur-swift` version it was
      taken from and the template's SHA-256.
    - A script in `faceblur` (`scripts/check-kmp-glue.sh <faceblur-swift checkout>`) checks that the sample's copy
      equals the template at that pinned version (apart from the `import` line) and that the pinned version is the
      one being released.
    - It runs locally in the macOS verification run and in the release checklist, where both checkouts exist; it
      reads the local file system and needs no token.
    - Once the repositories are public, `faceblur` CI fetches the pinned template anonymously and runs the same
      script on every push.
  - Why this split: the copy that apps compile and the template sit in one repository, so the high-frequency check
    needs no second repository. The cross-repository drift can only enter through the KMP sample's pinned copy, which
    the release checklist checks. No credential spans the two private repositories.
- **One Kotlin runtime per app.** An app must use either `faceblur-swift` or a KMP framework that contains FaceBlur,
  not both: two Kotlin runtimes in one process are not supported. The integration guide says so.
- **Registration semantics.** `registerOnnxRuntime` is thread-safe (atomic reference); the first factory registered
  stays.

### 4. Checks, errors and threading

- **Creating a `FaceMasker` on iOS:**
  1. No factory registered → `RuntimeUnavailable` ("register the ONNX Runtime bridge at start-up").
  2. `runtimeVersion()` ≠ "1.20.0" → `RuntimeUnavailable` naming the required version. The pin is enforced at run
     time for KMP apps as well, not only by the package manifest.
  3. The `DetectorModel` has already passed the read, required-hash and pre-parse checks of ADR 0012; those checks ran
     on the library-owned bytes that are now handed to the bridge. Kotlin holds that array at a fixed address for the
     duration of `open` and passes its address and byte count; it makes no second copy and writes no file. (The
     carrier-file re-hash and its `IoFailed` cases of the earlier design no longer exist.)
  4. `open(...)`: status 1 → `ModelRejected[RUNTIME_REJECTED]`; status 6 → `ModelRejected[IO_MISMATCH]`; status 7
     → `RuntimeUnavailable`; status 5 → `Internal` (library or glue fault).
  5. **Contract run** (ADR 0012 decision 4 step 7): one pass on a uniform mid-grey input, on every platform.
     Its output types and shapes must be as declared and pass the per-pass checks; a `run` status 2 or 3 or a failed
     check → `ModelRejected[CONTRACT_OUTPUT]` or `[CONTRACT_VALUES]`. The 30 s limit cannot stop the run on iOS: it
     is checked when the run returns (`ModelRejected[TIMEOUT]`), and a run that never returns blocks creation.
     Session creation itself has no time limit (platform limitation, documented).

  The **known-answer check** below is no longer part of creating a `FaceMasker`, because it needs expected values
  for one named model. It stays, unchanged, as a test of the reference fixture (ADR 0009) on every platform, through
  an internal test seam:
  - **Inputs:** two fixed synthetic input tensors, deterministic patterns computed in code from a seed (no image
    file).
  - **Reference implementation:** OpenCV's DNN module running the same pinned model. It is the reference of
    ADR 0009, at the OpenCV version pinned in the tool.
    - The expected values are generated by a script in `tools/` and committed as reviewed Kotlin source.
    - The generated file's header records the model SHA-256 and the OpenCV version.
  - **What is compared, so that the check discriminates.** For each input and each stride: the 32 anchors with
    the highest reference score plus 32 fixed anchors, with all their score, box and landmark values.
    - The tool tries seeds in a documented order. It keeps the first two seeds for which at least 16 compared
      score values are ≥ 0.01 (ten times the tolerance) and the box and landmark values include magnitudes ≥ 0.1.
    - The chosen seeds are committed with the expected values.
  - **Tolerance**, fixed now and never derived from a run of the code under test: 1 × 10⁻³ absolute for score
    outputs; 1 × 10⁻³ relative (at least 1 × 10⁻³ absolute) for box and landmark outputs. If a real device and
    runtime combination fails it, the response is a recorded decision, never a quiet widening.
  - **Failure:** any mismatch fails the test.
  - **What it catches:** a wrong runtime or execution provider, swapped or stub outputs in the glue. Because the
    two inputs differ, it also catches a glue that replays earlier outputs.
- **Status codes from the glue:**
  - 0 = success;
  - 1 = session could not be created;
  - 2 = run failed;
  - 3 = output missing, of the wrong type or too large for its buffer;
  - 4 = not open;
  - 5 = invalid arguments (decision 1 validation: a null or oversized model address or byte count, a wrong input
    size, a null address);
  - 6 = the session's input or output names differ from those given at `open`;
  - 7 = a required session option could not be set.
  During a pass, `run` status 2 → `ModelOutputInvalid[RUN_FAILED]`; status 3 → `ModelOutputInvalid` with the code
  that the row's reported type and shape show (`OUTPUT_TYPE`, `OUTPUT_SHAPE`, `TOO_MANY_ROWS` or
  `TOO_MANY_ELEMENTS`); a type, shape or size mismatch found by Kotlin → the same codes. Codes 4 and 5 → `Internal`
  (library or glue fault). All of this happens before any pixel is written. There is no retry and no partial result.
- **No run timeout and no creation bound on iOS (platform limitation).** The 1.20.0 API has no call that stops a
  running pass. A pass that never returns blocks that call and that `FaceMasker`; cancellation acts only between
  passes; the integration guide says so. The Android/JVM run timeout of ADR 0012 decision 6 has no iOS counterpart.
- **Error text never crosses the bridge.** Runtime error messages can contain the model path; the glue returns codes
  only. Exception messages are fixed English text plus the code (ADR 0002 decision 9).
- **Exceptions at the boundary:**
  - Runtime errors arrive in the glue as `NSError` (the runtime converts its C++ exceptions) and become status codes.
  - The glue validates lengths and addresses before any Foundation call, so its own calls do not raise.
  - Swift cannot catch an Objective-C exception: an `NSException` or a Swift trap in the glue would terminate the
    process. That fails closed (no image is returned), but it is a crash, and the validation exists to prevent it.
  - On the Kotlin side, every bridge call sits inside the iOS entry-point wrapping of ADR 0002.
- **Threading.**
  - `run` is called only from the thread that runs the `FaceMasker` call: a worker thread, because ADR 0002 rejects
    method calls on the main thread.
  - `open` and the contract run run on the thread that constructs the `FaceMasker`. ADR 0002 documents that
    construction should be off the main thread but does not reject it. The Swift facade always constructs on its own
    queue.
  - Calls are one at a time per instance (ADR 0002 `ConcurrentUse` guard). `close()` during a call defers the
    release until that call returns. The glue also
    serialises with its own lock. Different instances can run in parallel; each has its own session and buffers.

### 5. Trust boundary

The bridge is in-process code with the app's privileges. FaceBlur's checks and masking rules on iOS assume the
shipped glue (Swift apps) or the unmodified template (KMP apps). The library cannot prove that a KMP app's registered
glue creates the session from the verified bytes or runs the pinned runtime: `runtimeVersion()` is reported by the
glue. The
contract run, the type, shape and size checks of every pass, and the known-answer test of the reference fixture catch
misconfigured or edited glue. They are not a defence
against code written to deceive them, which could skip FaceBlur entirely anyway. Swift apps cannot register a bridge
through the facade (ADR 0011). The integration guide and the security policy state this boundary for KMP apps.

### 6. Model from memory (amended 2026-11-11; replaces the carrier-file design)

ONNX Runtime 1.20.0's C API can create a session from model bytes held in memory, so the verified bytes reach the
runtime without any file. The app's own path is never handed to the runtime either.

- **Flow.** Kotlin has the verified, library-owned model bytes (ADR 0012). `open` receives their address and byte
  count. The shim calls `CreateSessionFromArray` with the session options of decision 2 and returns a status code. When
  `open` returns, Kotlin releases its hold on the bytes. The runtime has parsed what it needs; it does not read the
  caller's memory again (decision 2 explains why no mode exists in which it would).
- **No file of any kind.** No carrier file, no cache directory, no temporary file of the model, no optimised-model
  file written by the runtime (the "never" list of ADR 0012 decision 4), no profiling output. The library's only disk
  output is the masked photo (and the temporary file of the masked output next to the destination for a safe
  overwrite, ADR 0005). A static check and a test enforce this (Verification).
- **Integrity.** The SHA-256 required by ADR 0012 is checked on the bytes before the pre-parse; the bytes passed to
  `open` are those bytes. There is no second hash, because there is no second copy that could differ. This does not
  defend against code in the same process, which could change the memory between the check and `open` (decision 5).
- **Memory.** The model bytes exist twice for the time of `open` (the library-owned array and the runtime's own
  parsed graph); the array's lifetime is the same as before the amendment, since the carrier file was written from it.
- **Errors.** A runtime that rejects the bytes returns status 1 (`ModelRejected[RUNTIME_REJECTED]`); an invalid
  address or count returns status 5 (`Internal`: a library or glue fault). The runtime's message is never read or
  forwarded.

#### History: the carrier-file design (2026-11-02, withdrawn 2026-11-11)

The accepted design of 2026-11-02 used the runtime's Objective-C API, which creates a session only from a path. Kotlin
therefore wrote the verified bytes to a new, exclusively created file in the app's caches directory (per creation,
mode `0600`, random `.onnx` name, `fsync`, re-hash, path passed to `open`, unlinked as soon as `open` returned), with a
`0700` directory check, a stale-file cleanup after ten minutes and a documented fallback to a persistent hash-named
file if the runtime turned out to reopen the path. It was never part of a product build: the production bridge was
first built in the third milestone, when the privacy-by-construction rule (nothing on disk but the masked output) was
in force, and the C API route removed the need for the file. The design is kept here so that the reasoning is not lost: it stayed correct
within its constraints (a short-lived, model-bytes-only file), and it is the fallback if a future runtime version
loses the in-memory route. The same amendment replaced the Objective-C wrapper objects of the per-pass path by C
calls, which is what removed the last per-pass Objective-C allocation.

## Alternatives considered

**A. Keep the benchmark's bridge (`NSMutableData` in, `[String: Data]` out).** Pro: measured as it is. Con: 12
Objective-C objects returned to Kotlin per pass (over 40 passes per photo at 3,500 px), freed only when the Kotlin GC
collects their wrappers. This violates the memory rules. Rejected; only primitives cross, and buffers are
library-owned. The number of copies per pass stays the same as in the benchmark.

**B. ONNX Runtime's C API through cinterop inside the klib.** Pro: no Swift glue, no registration. Con: every
consumer must link the runtime's static library themselves; the build needs macOS for the interop; the same version
pin applies; not measured. Rejected for 0.x. (The accepted route also uses the C API, but from a C target of the Swift
package and behind the same Kotlin interface, so the registration and the version check stay as designed.)

**C. A separate glue package per consumer type.** Con: Swift code cannot conform to a protocol of a module whose name
it does not know at build time. Rejected; one canonical file and one template that differs only in its `import` line.

**D. Registration by the KMP app's Kotlin code.** Con: Kotlin cannot construct the Swift glue. Rejected.

**E. Fail over to another runtime or execution provider if the pinned runtime is missing.** Con: other runtimes and
the Core ML execution provider are out of scope and unmeasured. Rejected.

**F. Template kept in `faceblur`, compared with `faceblur-swift` in CI on every push.** Pro: one check on every push.
Con: while both repositories are private, the CI job would need a credential that can read the other repository.
Rejected for now; it becomes the CI step once the repositories are public (decision 3).

**G. Trust "bytes written ≤ capacity" without a sentinel and exact sizes.** Con: a glue that writes short or skips an
output would let Kotlin read the previous pass's tensors and mask the wrong places without an error. Rejected.

**H. An iOS run timeout by waiting with a deadline and then poisoning the instance.** The call would wait for the
pass with a deadline; on expiry it fails, the `FaceMasker` becomes unusable, and its buffers are released only when
the runtime returns. Pro: the caller gets an error instead of a blocked call. Con: the runtime keeps the CPU busy
until the pass ends anyway, the instance and its buffers stay alive for an unknown time, and a second thread per call
adds complexity to the memory-critical path. Not chosen for 0.1.0; the limitation is documented (decision 4).

**I. Keep the carrier file (the earlier design).** Pro: Objective-C API only, no C target. Con: a copy of the app's model
sits in the caches directory for a short time, which breaks the rule that only the masked output is written; file
permissions, cleanup and a runtime-reopen precondition to maintain. Rejected; kept as history in decision 6.

**J. Copy the C shim into KMP apps like the Swift template.** Pro: no package dependency for KMP apps. Con: a second
native source copy that has to be kept equal. Not chosen as the default (decision 3); it is the fallback if the
package route does not work for KMP apps.

## Consequences

Positive
- Swift apps need no setup. KMP apps have four documented steps and get a clear error naming the missing step.
- No per-pass Objective-C objects reach Kotlin. The 200-photo memory test measures the real fixed-buffer design.
- Misconfigured glue (wrong runtime, missing, stale or wrongly typed outputs) fails at creation or at the pass, before
  any pixel is written.
- The runtime version is checked at run time, so a KMP app that resolves another runtime version fails clearly.

Negative / risks
- Residual risk: a KMP app that registers its own, different bridge can bypass FaceBlur's checks. That is the app's own
  code in its own process (the app chooses the model anyway); it is documented (decision 5) and not prevented.
- The app's model reaches ONNX Runtime 1.20.0 and its parser. External data is refused before; any other parser issue
  of that version remains a residual risk. It is reduced by the required SHA-256, which is checked before the
  pre-parse, and by the guidance to bundle the model and hard-code its hash (ADR 0012 decisions 8 and 10). An app
  that computes the hash from an untrusted file at runtime gets no such reduction.
- No run timeout on iOS (decision 4).
- KMP apps must export the FaceBlur API in their framework header.
- Keeping the template in step needs the scripts of decision 3. Across repositories it is checked per milestone and
  release, not per push, until the repositories are public.
- Creation costs one extra pass for the contract run.
- The runtime pin can only move with a new decision. The version check makes every pin change visible in code.
- The bridge now has a C target next to the Swift glue. C code is easier to get wrong than Swift: every size is
  checked for overflow before use, nothing is kept after a call, and the shim and the glue are a security-review area
  (the bridge) on every change.
- The model is created from memory, so no file of the model ever exists on the device; that also removes the
  caches-directory handling and its failure modes.
- Telemetry is off by an explicit call, not only by the absence of a sender in the Apple build.
- Apps that use the KMP template link a package product for the shim (decision 3) or, in the fallback, copy two more
  files.

## Verification

- **Memory.** Device run of 200 consecutive photos on the reference iPhone with flat memory. Also an allocation
  trace of one pass showing no Objective-C object created in Kotlin (Instruments; recorded once and after every
  change of the glue).
- **Speed.** Time per detector pass ≤ 1.10 × the benchmark app on the same device.
- **Parity.** Tier 2 of ADR 0009 on the iPhone (boxes and scores against the reference).
- **Output contract.** Fake bridges (Kotlin iOS tests, no runtime needed) that return 0 but:
  - write nothing;
  - write fewer bytes than expected;
  - leave "bytes written" at its old value;
  - skip one output;
  - report float64 instead of float32, a rank above 4, or dimensions that do not match the profile;
  - report more bytes than the capacity.
  Each gives `ModelOutputInvalid` and no pixel written. A fake that replays the previous pass's outputs with correct
  sizes fails the known-answer test. A layout test checks the table constants (nine `Int64` per row) on both sides.
- **Contract.** A fake bridge returning status 6 at `open` gives `ModelRejected[IO_MISMATCH]`, status 7 gives
  `RuntimeUnavailable`, status 5 gives `Internal`; a fake reporting status 3 with a float64 type, too many rows or
  too many elements gives the matching finding code; a model with external data or the ORT format identifier never
  reaches `open` (the fake records no call).
- **Format lock (glue test, simulator).** The glue sets `session.load_model_format` = `ONNX` and
  `session.allow_released_opsets_only` = `1`, and a forced failure to set one returns status 7. No ORT-format file is
  ever given to the 1.20.0 runtime on a device.
- **Bounds.** Glue unit tests (simulator) with a null address, a wrong size, a capacity that is too small, a wrong
  width or height, and a null, zero-length or overflowing model address and byte count at `open`: each returns its
  status code and does not crash. The
  glue and simulator suites run once with Address Sanitizer in the macOS verification run.
- **Known-answer test (reference fixture only).**
  - Passes on JVM, Android and the iPhone with the shipped runtimes.
  - A fake bridge with swapped outputs or constant outputs fails it.
  - Negative control with a real different model, on the JVM: the pinned model with its first convolution's weights
    multiplied by 1.05 fails the check. The tool script builds that model at test time; it is never committed or
    shipped, and it is loaded through an internal test seam.
  - The expected values are regenerated by the tool and compared with the committed source.
- **Registration and version.**
  - Creating a `FaceMasker` without registration throws `RuntimeUnavailable` with the setup message.
  - A second registration with a different factory returns `false`; with the same factory, `true`.
  - A fake bridge reporting "1.24.0" makes creation throw `RuntimeUnavailable`.
  - A glue test asserts that `runtimeVersion()` equals `ORTVersion()` and "1.20.0".
  - A grep check fails if the glue or the template returns a string literal from `runtimeVersion()`.
  - The KMP sample registers the template glue and runs.
- **Close.** A `close()` from a second thread during a slowed fake `run` returns at once without throwing. The run
  completes, the session is closed exactly once afterwards, later calls throw `Closed`, and an Address Sanitizer
  build shows no early free. After `close()`, the buffers have been zeroed and freed (test hook).
- **Errors.** Fake bridges returning each status code give the documented exception type with no image written. A
  glue that throws inside `run` returns code 2 and does not crash. Exception messages contain no path.
- **Template.** `config/check-glue-template.sh` passes in `faceblur-swift` CI. `scripts/check-kmp-glue.sh` passes in
  the macOS verification run and the release checklist; a planted one-line difference makes each fail.
- **Runtime options and supply chain.**
  - A grep check: logging level `fatal` is set; no optimised-model path, profiling or custom-operator call; no
    logging or network names in `Sources/`.
  - The macOS verification run asserts that `ORT_POD_LOCAL_PATH` is unset and that `Package.resolved` pins the
    1.20.0 revision.
- **No network.** A symbol scan of the linked sample app for networking APIs and an on-device network capture during a
  run show no traffic from the library or the runtime.
- **No file on disk (replaces the carrier-file test).**
  - A glue test with the real runtime snapshots the app's temporary, caches, library and documents folders before the
    model is loaded and after creation plus an in-place call: identical. The snapshot ignores only system-owned
    folders of the simulator that are shared with the operating system's own services, and has a positive control (a
    file planted in the caches folder is seen).
  - A Kotlin test with a fake glue does the same.
  - The static check fails on any file-creation call, any cache or temporary directory name and any optimised-model
    path in the shim, the glue and `iosMain`.
- **Memory is not referenced after `open`.** A glue test overwrites the model bytes after `open` returns and runs a
  pass: the session still works and the result is unchanged.
- **Eight threads.** N = 8 threads create instances from one `DetectorModel` at the same time, 50 times: every
  creation succeeds and no file appears.
- **Telemetry.** A check fails if the shim does not call `DisableTelemetryEvents` before the first session is created,
  and a glue test with a forced failure of the call, through a test seam, gets status 7.
