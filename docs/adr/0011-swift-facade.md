# 0011. Swift facade: types, concurrency, errors, package layout

Status: Proposed (owner review), revision for release 0.2, 2026-11-10. This revision replaces the design that was
accepted on 2026-11-02 and amended on 2026-11-03 (that text stays in the git history). It brings the facade in line
with the shared Kotlin API as it is on the 0.1.0 line, gives every public Swift signature, and adds the package layout
and the list of Kotlin iOS entry points that the facade needs. The open choices at the end of the decision carry the
proposal's pick; the owner confirms or changes them. Nothing is published: no tag, no release, no package version.
Addition of 2026-11-11 (third-milestone ADR batch, still Proposed): decision 4a (how the facade maps `CGContext` and
`CVPixelBuffer` alpha to the pixel layouts, so that a face cannot stay unmasked), the C shim as a package library
product (decision 7), the iOS-only seam rule (decision 8 item 7), and open choice C recorded as built.
Second addition of 2026-11-11 (project owner's decisions on the in-memory API, still Proposed; all additive): decision
4a is revised to "opaque images only" (open choice F answered: an in-memory image with any pixel that is not opaque is
refused, never repaired, and the `CGImage` convenience no longer composites over black); decision 4b adds an
orientation parameter to the `CGContext`, `CVPixelBuffer` and `CGImage` forms; decision 4c adds an in-memory JPEG
helper; decisions 3, 8 and Verification list the new signatures, entry points and tests.
Date: 2026-11-10 (first version 2026-11-01; additions 2026-11-11)

## Status note (0.1.0)

The Swift facade (and the `faceblur-swift` package) is deferred to release 0.2: version 0.1.0 ships the Android
artifact and the shared Kotlin API only. This ADR describes the planned design; it is not implemented in 0.1.0. The
iOS targets of the library keep compiling (`iosArm64`, `iosSimulatorArm64`) so that work on 0.2 starts from a green
build.

## Context

Swift and SwiftUI apps use FaceBlur through the `faceblur-swift` package (ADR 0001): one public library product
`FaceBlur` (the hand-written facade), built on the Kotlin XCFramework `FaceBlurCore` and the ONNX Runtime glue
target `FaceBlurOnnxRuntime` (ADR 0010). The glue reaches the runtime through the C target `FaceBlurOrtShim`, which is
also published as a second library product for KMP apps (decision 7). Kotlin's Swift export is not used. The Objective-C header that Kotlin
generates is not a Swift API: it has Kotlin-specific types (`KotlinInt`, `KotlinByteArray`), `NSError`
out-parameters and `FaceBlurCore` prefixes.

Inputs:
- ADR 0002: one vocabulary on every platform; the configuration table, its defaults and ranges (decision 10); the
  mask opacity invariant; fail-closed errors (decision 9).
- ADR 0004: in-memory mode on a caller-owned buffer, pixels cross as address + size + row stride, no per-call
  Objective-C object in Kotlin, the convenience `mask(_ image: CGImage)`.
- ADR 0005 and ADR 0015: file mode, working size, smaller output, JPEG output only, metadata policy, plan binding,
  `PlanMasker`, `UprightBox`.
- ADR 0010: the ONNX Runtime bridge and its registration.
- ADR 0012: the app supplies the model and its profile; the expected SHA-256 is required.
- Product rules that bind this API: opaque masks only (no blur, pixelation, transparency or partial style, not even
  as an opt-in); settings that could weaken protection are not exposed or are limited to an approved range; JPEG
  output only; JPEG and HEIC input; a default limit of 25 MP on the decoded image; no network code; nothing is
  written to disk except the masked output (a temporary file of the masked output next to the destination, for a
  safe replace, is allowed).
- Platform: iOS 18+, Swift 6 language mode with strict concurrency.

What changed since the accepted design (the reasons for this revision):
- The shared Kotlin API now has 19 `FaceMaskException` subclasses. `DecodeFailed`, `EncodeFailed`, `MainThread` and
  an output-verification error do not exist: an undecodable source is `UnsupportedImage`, a failed output check is
  `OutputValidationFailed`. `UnsupportedImage` and `UnsupportedTarget` carry no public reason property. The Swift
  error list follows the Kotlin list one to one (decision 5).
- Kotlin configuration constructors validate and throw. The Swift configuration types therefore have throwing
  initialisers and a non-throwing `default` value (decision 2).
- Converting a large `Data` into a Kotlin `ByteArray` from Swift is one Objective-C call per byte unless Kotlin
  offers a copy from memory; the facade needs that entry point (decision 8).
- Swift 6.2 added `nonisolated(nonsending)` (SE-0461): an async function that runs on the caller's executor. It lets
  a caller pass a non-`Sendable` buffer (`CGContext`, `CVPixelBuffer`) and keep using it after the call, which the
  accepted design left to a compile test (decision 4).
- The ONNX Runtime Objective-C API of version 1.20.0 creates a session only from a model path
  (`ORTSession initWithEnv:modelPath:sessionOptions:error:`; SOURCE: `objectivec/include/ort_session.h` at tag
  v1.20.0, checked 2026-11-10). ADR 0010 decision 6 therefore writes a short-lived model file. That conflicts with
  "nothing is written to disk except the masked output"; see open choice C below.

SDK facts used (SOURCE, iOS SDK headers): `CGImage` is annotated `Sendable`; `CGContext` has no annotation;
`CVPixelBuffer` is marked non-`Sendable`; `vImage_Buffer` is a C struct with a raw pointer.

## Decision

### 1. No Kotlin type in the public Swift API

- **Facade (`FaceBlur`, the only product).** It imports the Kotlin module with an access-level import
  (`internal import FaceBlurCore`). The compiler then rejects any public declaration that mentions a Kotlin type.
  `FaceBlurCore` is not re-exported. The generated `FaceBlur.swiftinterface` contains no `Kotlin` symbol and no
  `FaceBlurCore` reference; a check on that file guards it.
- **Glue target (`FaceBlurOnnxRuntime`).** Its glue type conforms to the Kotlin `OnnxBridge` protocol, so it uses
  `public import FaceBlurCore`. It is not a library product, so apps cannot depend on it.
- **Swift types only.** Every public type is a Swift `struct`, `enum`, `actor` or `final class` built from `Int`,
  `Int64`, `Float`, `Double`, `UInt8`, `Bool`, `String`, `URL`, `Data`, arrays and dictionaries of these,
  `CGContext`, `CGImage` and `CVPixelBuffer`.
- **No public name contains "anonymi".** Checked on the interface file.

### 2. Public API: values and configuration

All value types are `Sendable` and `Hashable` unless stated. Types whose values have ranges have `public let`
properties and a throwing initialiser, so an out-of-range value cannot exist; each also has a non-throwing
`static let default`. Defaults and ranges are those of ADR 0002 decision 10, checked in Swift first and again in
Kotlin; a value outside its range throws `FaceMaskError.invalidConfiguration(parameter:)` with the Kotlin parameter
name.

```swift
// Masking (ADR 0002 decision 10). Opaque styles only; there is no blur, pixelation or alpha anywhere.
public enum MaskStyle: Sendable, Hashable, CaseIterable { case gradientOval, smoothFill, solid }
public enum MaskShape: Sendable, Hashable, CaseIterable { case ellipse, rectangle }

public struct MaskColor: Sendable, Hashable {          // no alpha: opaque by construction
    public var red: UInt8, green: UInt8, blue: UInt8
    public init(red: UInt8, green: UInt8, blue: UInt8)
    public static let neutralGray: MaskColor           // (127, 127, 127), the default
}

public struct MaskingConfiguration: Sendable, Hashable {
    public static let `default`: MaskingConfiguration
    public let style: MaskStyle                         // default .gradientOval
    public let shape: MaskShape                         // default .ellipse
    public let expandX: Float                           // default 1.6, range default ... 3.0 (only larger)
    public let expandY: Float                           // default 1.8, range default ... 3.0 (only larger)
    public let feather: Float                           // default 0.10, range 0 ... 0.5 (outward only)
    public let color: MaskColor                         // default .neutralGray
    public init(style: MaskStyle = .gradientOval, shape: MaskShape = .ellipse,
                expandX: Float = 1.6, expandY: Float = 1.8, feather: Float = 0.10,
                color: MaskColor = .neutralGray) throws(FaceMaskError)
}

public struct FaceMaskerConfiguration: Sendable, Hashable {
    public static let `default`: FaceMaskerConfiguration
    public let masking: MaskingConfiguration            // default .default
    public let onnxThreads: Int                         // default 0 (automatic), range 0 ... 4
    public init(masking: MaskingConfiguration = .default, onnxThreads: Int = 0) throws(FaceMaskError)
}

// File mode (ADR 0005). JPEG output only.
public enum OutputFormat: Sendable, Hashable { case jpeg(quality: Int) }      // quality 1 ... 100, default 90
public enum MetadataPolicy: Sendable, Hashable {
    case stripAll                                                             // default
    case keepAllowlist(gps: Bool = false, dateTime: Bool = false, camera: Bool = false)   // approved opt-in
}
public struct FileOptions: Sendable, Hashable {
    public static let `default`: FileOptions
    public let maxLongSide: Int           // default 3,500; 0 (source size) or >= 3,500 (detection floor)
    public let maxOutputLongSide: Int     // default 0 (output = working size); 0 or >= 64
    public let maxDecodedPixels: Int      // default 25,000,000 (limit on the decoded working image)
    public let format: OutputFormat       // default .jpeg(quality: 90)
    public let metadata: MetadataPolicy   // default .stripAll
    public init(maxLongSide: Int = 3_500, maxOutputLongSide: Int = 0, maxDecodedPixels: Int = 25_000_000,
                format: OutputFormat = .jpeg(quality: 90), metadata: MetadataPolicy = .stripAll) throws(FaceMaskError)
}
public enum OutputTarget: Sendable, Hashable {
    case newFile(URL, replaceExisting: Bool = false)   // file URLs only
    case overwriteSource                               // atomic replace of the source file
}

// Model (ADR 0012). The threshold and the expected SHA-256 have no default.
public enum ModelSource: Sendable { case data(Data); case file(URL) }      // file URLs only
public enum RowLayout: Sendable, Hashable { case rows, columns }
public enum BoxFormat: Sendable, Hashable { case xyxy, xywh, cxcywh }
public enum CoordinateSpace: Sendable, Hashable { case inputPixels, normalized }
public enum ColorOrder: Sendable, Hashable { case rgb, bgr }
public enum TensorLayout: Sendable, Hashable { case nchw, nhwc }
public struct PixelScaling: Sendable, Hashable {
    public let divisor: Float, mean: [Float], std: [Float]
    public init(divisor: Float, mean: [Float], std: [Float]) throws(FaceMaskError)
}
public struct GenericDetections: Sendable, Hashable {
    public let boxesOutput: String, boxColumn: Int, scoresOutput: String?, scoreColumn: Int
    public let rows: RowLayout, boxFormat: BoxFormat, coordinates: CoordinateSpace
    public let colorOrder: ColorOrder, tensorLayout: TensorLayout, scaling: PixelScaling, nmsInGraph: Bool
    public init(boxesOutput: String, boxColumn: Int, scoresOutput: String?, scoreColumn: Int,
                rows: RowLayout, boxFormat: BoxFormat, coordinates: CoordinateSpace, colorOrder: ColorOrder,
                tensorLayout: TensorLayout, scaling: PixelScaling, nmsInGraph: Bool = false) throws(FaceMaskError)
}
public enum DetectionFormat: Sendable, Hashable { case yuNetLayout; case genericDetections(GenericDetections) }
public struct DetectorProfile: Sendable, Hashable {
    public let format: DetectionFormat, inputWidth: Int, inputHeight: Int, scoreThreshold: Float
    public let expectedSHA256: String          // 64 hex digits, either case, stored in lower case
    public let minMaskExpandX: Float, minMaskExpandY: Float     // defaults 1.6 / 1.8, only larger, up to 3.0
    public init(format: DetectionFormat, inputWidth: Int, inputHeight: Int, scoreThreshold: Float,
                expectedSHA256: String, minMaskExpandX: Float = 1.6, minMaskExpandY: Float = 1.8) throws(FaceMaskError)
}

// Masks and plans (ADR 0002 decisions 5 and 6, ADR 0015 decision 6).
public enum MaskOrigin: Sendable, Hashable { case detected, manual }
public struct FaceBox: Sendable, Hashable {     // pixel coordinates of the image passed in; no landmarks
    public let left: Float, top: Float, right: Float, bottom: Float, score: Float
    public var width: Float { get }
    public var height: Float { get }
}
public struct Mask: Sendable, Hashable {
    public let shape: MaskShape, centerX: Float, centerY: Float, radiusX: Float, radiusY: Float
    public let origin: MaskOrigin
    public static func fromFace(_ face: FaceBox, masking: MaskingConfiguration = .default) throws(FaceMaskError) -> Mask
    public static func manual(left: Float, top: Float, right: Float, bottom: Float,
                              masking: MaskingConfiguration = .default) throws(FaceMaskError) -> Mask
    public static func exact(shape: MaskShape, centerX: Float, centerY: Float,
                             radiusX: Float, radiusY: Float) throws(FaceMaskError) -> Mask
}
public struct MaskPlan: Sendable, Hashable {     // == and hashing over width, height and masks only
    public let width: Int, height: Int, masks: [Mask]
    public init(width: Int, height: Int, masks: [Mask]) throws(FaceMaskError)   // no binding: in-memory plans
    public func withMasks(_ masks: [Mask]) throws(FaceMaskError) -> MaskPlan       // same size and binding
}
public struct UprightBox: Sendable, Hashable {
    public let left: Float, top: Float, right: Float, bottom: Float, imageWidth: Int, imageHeight: Int
    public static func fromRaw(left: Float, top: Float, right: Float, bottom: Float,
                               rawWidth: Int, rawHeight: Int, orientation: Int) throws(FaceMaskError) -> UprightBox
}
public struct ExpectedFace: Sendable, Hashable {
    public let left: Float, top: Float, right: Float, bottom: Float
    public init(left: Float, top: Float, right: Float, bottom: Float) throws(FaceMaskError)
}

// Results: created by the library only (no public initialisers).
public struct Timings: Sendable, Hashable {      // milliseconds; nil = step not performed
    public let decodeMs: Double?, orientScaleMs: Double?, detectMs: Double, passes: Int
    public let maskMs: Double, encodeMs: Double?, totalMs: Double, gcMs: Double?
}
public struct Detection: Sendable, Hashable {    // == over the public values only, never the binding
    public let faces: [FaceBox], width: Int, height: Int, outputWidth: Int, outputHeight: Int
    public let hasGainMap: Bool, masking: MaskingConfiguration, timings: Timings, warnings: [String]
    public func toMaskPlan() throws(FaceMaskError) -> MaskPlan
    public func toMaskPlan(masking: MaskingConfiguration) throws(FaceMaskError) -> MaskPlan
}
public struct MaskResult: Sendable, Hashable {
    public let faces: [FaceBox], masks: [Mask], width: Int, height: Int, gainMapRemoved: Bool
    public let timings: Timings, warnings: [String]
}
public struct FileResult: Sendable, Hashable {
    public let faces: [FaceBox], masks: [Mask], width: Int, height: Int
    public let sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int, outputBytes: Int64
    public let gainMapRemoved: Bool, timings: Timings, warnings: [String]
}
public struct ProfileCheckResult: Sendable, Hashable {
    public let allFound: Bool, bestIoU: [Float], detections: [FaceBox]
}
public struct TensorInfo: Sendable, Hashable { public let name: String, elementType: String, shape: [Int?] }  // nil = symbolic
public struct ModelInfo: Sendable, Hashable {
    public let sha256: String, sizeBytes: Int64, irVersion: Int64, opsetImports: [String: Int64]
    public let inputs: [TensorInfo], outputs: [TensorInfo], warnings: [String]
}
public struct MaskerModelInfo: Sendable, Hashable {
    public let model: ModelInfo, runtimeVersion: String, executionProvider: String, faceBlurVersion: String
}
```

Typed throws (`throws(FaceMaskError)`, Swift 6) is used on the synchronous value functions, where the only error is
`FaceMaskError`. The async methods (decision 3) use untyped `throws`, because they also throw Swift's
`CancellationError`.

### 3. Public API: model, masker and plan masker

```swift
public final class DetectorModel: @unchecked Sendable {    // immutable; one per model, shared by all maskers
    public static let defaultMaxModelBytes: Int = 52_428_800         // range 1 ... 2,147,483,639
    public init(source: ModelSource, profile: DetectorProfile,
                maxModelBytes: Int = DetectorModel.defaultMaxModelBytes) async throws
    public let info: ModelInfo
    public let profile: DetectorProfile
}

public actor FaceMasker {
    public init(model: DetectorModel, configuration: FaceMaskerConfiguration = .default) async throws
    public nonisolated let model: DetectorModel
    public nonisolated let configuration: FaceMaskerConfiguration
    public nonisolated let modelInfo: MaskerModelInfo

    // In-memory mode (ADR 0004): the caller keeps owning the buffer and may use it after the call.
    public nonisolated(nonsending) func detect(_ context: CGContext) async throws -> Detection
    public nonisolated(nonsending) func maskInPlace(_ context: CGContext) async throws -> MaskResult
    public nonisolated(nonsending) func applyMasks(_ plan: MaskPlan, to context: CGContext) async throws -> MaskResult
    public nonisolated(nonsending) func checkProfile(_ context: CGContext, expectedFaces: [ExpectedFace],
                                                     minIoU: Float = 0.5) async throws -> ProfileCheckResult
    public nonisolated(nonsending) func detect(_ pixelBuffer: CVPixelBuffer) async throws -> Detection
    public nonisolated(nonsending) func maskInPlace(_ pixelBuffer: CVPixelBuffer) async throws -> MaskResult
    public nonisolated(nonsending) func applyMasks(_ plan: MaskPlan, to pixelBuffer: CVPixelBuffer) async throws -> MaskResult
    public nonisolated(nonsending) func checkProfile(_ pixelBuffer: CVPixelBuffer, expectedFaces: [ExpectedFace],
                                                     minIoU: Float = 0.5) async throws -> ProfileCheckResult
    // Convenience: one copy into a library-owned buffer; returns a no-copy image over it.
    public func mask(_ image: CGImage) async throws -> (image: CGImage, result: MaskResult)

    // Orientation forms (decision 4b; additive): the forms above without `orientation` are these with `.up`.
    public nonisolated(nonsending) func detect(_ context: CGContext, orientation: CGImagePropertyOrientation) async throws -> Detection
    public nonisolated(nonsending) func maskInPlace(_ context: CGContext, orientation: CGImagePropertyOrientation) async throws -> MaskResult
    public nonisolated(nonsending) func applyMasks(_ plan: MaskPlan, to context: CGContext,
                                                   orientation: CGImagePropertyOrientation) async throws -> MaskResult
    public nonisolated(nonsending) func checkProfile(_ context: CGContext, orientation: CGImagePropertyOrientation,
                                                     expectedFaces: [ExpectedFace], minIoU: Float = 0.5) async throws -> ProfileCheckResult
    // ... the same four for CVPixelBuffer ...
    public func mask(_ image: CGImage, orientation: CGImagePropertyOrientation) async throws -> (image: CGImage, result: MaskResult)

    // In-memory JPEG output (decision 4c; additive): pixels only, file mode's rebuild and output check, no disk.
    public static func encodeJPEG(_ image: CGImage, quality: Int = 90) async throws -> Data

    // File mode (ADR 0005): file URLs; JPEG or HEIC (PNG where the platform decodes it) in, JPEG out.
    public func detectFile(at source: URL, options: FileOptions = .default) async throws -> Detection
    public func maskFile(at source: URL, to target: OutputTarget, options: FileOptions = .default,
                         plan: MaskPlan? = nil) async throws -> FileResult

    public nonisolated func close()       // idempotent, never throws; runs after a call in progress
}

public struct PlanMasker: Sendable {      // masks from the app's own detector; no model, no ONNX Runtime
    public init(masking: MaskingConfiguration = .default)
    public let masking: MaskingConfiguration
    public func planFile(at source: URL, options: FileOptions = .default) async throws -> MaskPlan
    public func maskFile(at source: URL, to target: OutputTarget, plan: MaskPlan,
                         options: FileOptions = .default) async throws -> FileResult
}
```

Masking one photo to a new file is one statement after construction:

```swift
let model = try await DetectorModel(source: .file(modelURL), profile: profile)
let masker = try await FaceMasker(model: model)
let result = try await masker.maskFile(at: photoURL, to: .newFile(outputURL))
```

### 4. Behaviour

- **Where the work runs.** Each `FaceMasker` owns a private serial `DispatchQueue`, which is also the actor's executor
  (`unownedExecutor` returns the queue as a `DispatchSerialQueue`, SE-0392). Every call that does work runs on that
  queue. Blocking work never runs on the cooperative thread pool or on the main thread, including the initialisers.
  `DetectorModel.init` reads, hashes and pre-parses on a private queue of the facade. `PlanMasker` calls run on a
  private concurrent queue, so parallel calls run in parallel.
- **Borrowed buffers.** The in-memory methods are `nonisolated(nonsending)`: they start on the caller's executor, so
  the caller does not give the buffer away and can draw, encode or display it after the call. Inside, the method
  extracts address, size, row stride and layout, then hops to the masker's queue with
  `withCheckedThrowingContinuation` + `queue.async`. The buffer reference crosses into that block in one internal
  `@unchecked Sendable` box. Its safety argument is the caller obligation of ADR 0004 (no other code touches the
  buffer during the call; the facade keeps no reference after returning). A `CVPixelBuffer` is locked with
  `CVPixelBufferLockBaseAddress` for the duration of the call and unlocked on every path.
- **Accepted layouts** are those of ADR 0004 decision 2 (8-bit, 4-channel RGBA/BGRA, premultiplied or skip alpha;
  `kCVPixelFormatType_32BGRA` and `_32RGBA`), mapped to the four pixel layouts by decision 4a. Anything else throws
  `unsupportedImage` before a pixel is written.
- **Cancellation.** Each async method wraps its queue hop in `withTaskCancellationHandler`. The handler sets a
  thread-safe flag that the facade's internal Kotlin `CancellationToken` implementation reads (an `Atomic<Bool>` from
  the Synchronization module). A Kotlin `Cancelled` is thrown to the caller as Swift's `CancellationError`, with the
  all-or-nothing rule of ADR 0002 decision 8: no output file and no temporary file remain, an overwrite target is
  unchanged, and a buffer is either untouched or fully masked.
- **Model sources.**
  - `maxModelBytes` is checked in Swift first (1 ... 2,147,483,639), then in Kotlin.
  - `.file(URL)`: file URLs only, otherwise `invalidConfiguration(parameter: "source")`. The facade calls
    `startAccessingSecurityScopedResource()` and, only if that returned `true`, `stopAccessingSecurityScopedResource()`
    on every path. Inside that window it passes the URL's file-system path (`withUnsafeFileSystemRepresentation`) to
    Kotlin's `ModelSource.fromFile`; the read, size and regular-file rules of ADR 0012 then apply as for KMP apps.
    A File Provider item that is not downloaded fails as `ioFailed`.
  - `.data(Data)`: the size is checked against `maxModelBytes` first. The bytes reach Kotlin through one memory copy
    (`withUnsafeBytes` and the Kotlin entry point of decision 8), never byte by byte.
  - The profile is validated in Swift when it is built and again in Kotlin.
- **File URLs in file mode.** Source and target must be file URLs (`invalidConfiguration(parameter: "source")` or
  `"target"` otherwise). The facade starts security-scoped access on the source URL and on the target URL (or its
  directory for `.newFile`) for the duration of the call and stops it on every path. For a target inside a folder
  the user picked, the app keeps access to that folder open during the call; DocC says so. The facade passes paths,
  never URLs, to Kotlin. FaceBlur writes the masked output only: `.newFile` and `.overwriteSource` write a temporary
  file of the masked output next to the destination and replace the destination atomically; on any error the
  temporary file is removed and the destination is unchanged (ADR 0005).
- **Plan binding (ADR 0015 decision 6).** A Swift `Detection` from `detectFile` and a Swift `MaskPlan` from
  `toMaskPlan`, `planFile` or `withMasks` keep an internal reference to the Kotlin value they came from, in an internal
  final class with one `let` property, marked `@unchecked Sendable` (the Kotlin `Detection` and `MaskPlan` are
  immutable). It is not part of `==`, hashing or `description`, and nothing public reads it. When a plan crosses into
  Kotlin for file mode, the facade calls Kotlin `withMasks` on the held plan with the Swift `masks`; a plan from the
  public initialiser has no held value and file mode refuses it with `maskPlanMismatch` before the source is opened.
- **Registration.** A lazily run static initialiser of the facade calls `FaceBlurIos.registerOnnxRuntime` with the
  glue's factory before the first `FaceMasker` is created. If it returns `false` (another factory was registered
  first, for example by code that imports `FaceBlurCore` directly), every `FaceMasker` creation throws
  `runtimeUnavailable`: the facade never runs a bridge it does not know. `PlanMasker` and `DetectorModel` do not
  need the bridge.
- **Version check.** The facade compiles in its version as a constant; at first use it compares it with
  `FaceBlurIos.libraryVersion`. A mismatch (for example a local XCFramework from another build) throws
  `runtimeUnavailable`.
- **Lifetime.** `close()` is idempotent and queued on the masker's queue, so it runs after a call in progress. Calls
  after `close()` throw `closed`. `deinit` may run on any thread, including the main thread; it hands the release of
  the Kotlin masker to the queue.
- **Discard rules (DocC on every in-place method and on `FaceMaskError`).** After any thrown error the buffer must
  not be displayed, encoded or shared as masked. After `CancellationError` the buffer must be treated as not masked:
  it is either untouched or fully masked, and the caller cannot tell which.
- **No logging.** The facade never logs and never prints. Error messages are fixed text plus numbers, never paths,
  file names, pixel values or coordinates.
- **Not in 0.2 (can be added later without a break).** A `Data` input form for file mode; a `vImage_Buffer` carrier
  and a synchronous `FaceMaskerSync` twin (open choices A and B).

#### 4a. Alpha and layout mapping (fail closed; proposed 2026-11-11)

**The risk.** The common pixel contract is non-premultiplied ARGB (ADR 0002). A premultiplied layout is therefore
un-premultiplied on read, and a pixel with alpha 0 reads as transparent black. Suppose a buffer is declared
premultiplied but its alpha bytes mean nothing: a producer that leaves alpha at 0, or straight-alpha data written into
a premultiplied context. Detection then sees black and finds no face, while the colour bytes, and so the face, stay in
the buffer and are shown by any viewer that ignores alpha. That is a way to leave a face unmasked, and it is closed
here.

**The principle.** Detection must see at least as much as any viewer of the buffer can see. A viewer that honours
alpha shows a blend of the stored colour and the background, never more detail than the stored colour; a viewer that
ignores alpha shows the stored colour; a viewer that divides alpha out shows the intrinsic colour, which for valid
premultiplied data is what the un-premultiplied read gives. Four rules follow.

1. **Layout from the declaration (the facade).**

   | Carrier and declared form | Layout used | Condition |
   |---|---|---|
   | `CGContext`, `premultipliedLast` | `RGBA_PREMULTIPLIED` | rule 2 holds |
   | `CGContext`, `premultipliedFirst`, 32-bit little-endian | `BGRA_PREMULTIPLIED` | rule 2 holds |
   | `CGContext`, `noneSkipLast` / `noneSkipFirst` | `RGBA_NONE_SKIP` / `BGRA_NONE_SKIP` | none: the skip byte is ignored and the colour is used as stored |
   | `CGContext`, any other alpha form | none | `unsupportedImage` |
   | `CVPixelBuffer`, `32BGRA` / `32RGBA` | `BGRA_NONE_SKIP` / `RGBA_NONE_SKIP`, never a premultiplied layout | the opacity guard of rule 1a |

   A `CVPixelBuffer` pixel-format type names the channel order only; whether its alpha is premultiplied is not part of
   it (ASSUMPTION: checked against the CoreVideo documentation when ST-031 is built; the facade does not rely on any
   optional buffer attachment either, because producers omit it). The facade therefore never declares a
   `CVPixelBuffer` premultiplied.

   **1a. Opacity guard for `CVPixelBuffer` (revised 2026-11-11).** After the buffer is locked, the facade reads the
   alpha byte of every pixel (row by row, padding never read). If every alpha byte is 255, the buffer is used as
   `*_NONE_SKIP`. If any alpha byte is below 255 (0 included), the facade throws `unsupportedImage` and the buffer is
   untouched. DocC says: flatten such an image onto an opaque background first. (The first version accepted alpha
   bytes that were all 0 or 255; the owner's decision of 2026-11-11 refuses every image that is not opaque.)
2. **Opaque premultiplied data only (the Kotlin pixel adapter, for every caller including KMP apps; revised
   2026-11-11).** In a premultiplied layout every pixel must be opaque (alpha 255). The adapter checks this over all
   pixel rows once, at the start of each call that takes the buffer (`wrapRgba`), before detection and before any
   write, and again on every read (a reused wrapper). The first pixel below 255 gives `UnsupportedImage` (Swift
   `unsupportedImage`), and the buffer is unchanged. Layouts with a skip byte are checked only for a skip byte of 0 or
   255, because they ignore it by definition. A format with an alpha channel whose pixels are all opaque (for example
   the default format of `UIGraphicsImageRenderer` drawing a photo) is accepted. The check is a linear read pass over
   the buffer; its cost is recorded at the second-pass milestone (ADR 0004 decision 7), not claimed here. KDoc of
   `IosPixelLayout` and `wrapRgba` states the rule. Because every accepted pixel is opaque, the adapter reads colour as
   stored and writes alpha 255; no un-premultiplying is left in the path.
3. **Output unchanged.** Mask interiors are written with alpha 255 and the soft edge never lowers alpha (ADR 0004
   decision 2). Nothing in this decision writes a pixel that is not part of a mask or its soft edge.
4. **The `CGImage` convenience (revised 2026-11-11).** `mask(_ image: CGImage)` (ADR 0004 decision 3) draws the image
   into a library-owned `premultipliedFirst`, 32-bit little-endian buffer that is cleared first, with the copy blend
   mode, so every canvas pixel carries exactly the source pixel's alpha (an image without an alpha channel gives 255).
   The canvas is then handed to the library as `BGRA_PREMULTIPLIED`, so rule 2 refuses it unless every pixel is
   opaque; the buffer is freed and nothing is returned. An accepted canvas is returned as a no-copy image declared
   without alpha (`noneSkipFirst`; identical bytes, every alpha 255). There is no black fill and no repair: colour
   hidden under transparent pixels can never reach detection or the output. Alpha is compared after the conversion
   to 8 bits (a 16-bit or float source whose alpha rounds to 255 counts as opaque).

**Why reject and not repair.** A rule that "repairs" transparent or invalid premultiplied pixels (for example by reading the stored
colour where colour exceeds alpha) would read one buffer in two ways and would need its own treatment in the soft edge
and in the read-back check of ADR 0002 decision 9. A producer that declares premultiplied alpha and fills it with
other data is a defect the caller must fix; a clear refusal tells them, and the escape is one line (declare a skip
layout).

**Residual, stated honestly.** Opaque images only: there is no translucent pixel left whose stored and intrinsic
colour could differ, so detection sees exactly what any viewer shows.

#### 4b. Orientation of in-memory images (proposed 2026-11-11)

**Decision.** The `CGContext`, `CVPixelBuffer` and `CGImage` forms each get a form with
`orientation: CGImagePropertyOrientation` (the EXIF numbering 1 to 8, as `UprightBox.fromRaw`); the forms without it
stay unchanged and mean `.up`. Detection always runs on the upright view (the stored pixels turned as a viewer shows
them), so a sideways face is detected like an upright one, and every coordinate of the call (faces, masks, a plan,
`Detection.width`/`height`) is in the upright view.

- **In-place forms (`CGContext`, `CVPixelBuffer`).** The buffer is not turned and not copied. The Kotlin adapter
  (`FaceBlurIos.wrapRgba` with an orientation) presents the upright view of the caller's memory: upright pixel (u, v)
  is read from and written to the stored pixel a viewer shows there. Because the eight orientations permute whole
  pixels (turns by 90 degrees and mirrors), detection input, mask writes and the read-back check of ADR 0002
  decision 9 all go through the same mapping, and a mask lands exactly on the stored pixels under it. A plan for
  `applyMasks` has the upright size; a plan of the stored size of a turned buffer is `maskPlanMismatch`.
- **`CGImage` form.** The image is drawn upright into the library-owned canvas (the orientation's transform applied
  while drawing, no interpolation), masked there, and returned upright. The copy count stays one.
- **Swift validation.** `CGImagePropertyOrientation` is a closed enum of exactly the eight values; the library checks
  1 to 8 again (`InvalidConfiguration("orientation")` for KMP callers).

**Alternatives considered.** (1) Callers turn the image first (the earlier DocC advice): one more full-size copy and a
silent miss when they forget. (2) The facade turns a copy for detection and maps the boxes back: one full-size copy
per call (against ADR 0004's memory rules) and a second implementation of the box mapping. (3) Detect on the stored
pixels and only report coordinates upright: a sideways face is often not detected. All rejected in favour of the
upright view.

#### 4c. In-memory JPEG helper (proposed 2026-11-11)

**Decision.** `FaceMasker.encodeJPEG(_ image: CGImage, quality: Int = 90) async throws -> Data` (static; needs no
model) gives apps that mask in memory the same output path as file mode: the image is drawn once into a library-owned
canvas (pixels only, as stored: no orientation applied, so the caller passes an upright image such as the one `mask`
returns; rule 4 of decision 4a refuses any pixel that is not opaque), and the Kotlin entry point
`FaceBlurIos.encodeJpeg(address, width, height, bytesPerRow, layout, quality): EncodedJpeg` encodes it with the
platform JPEG encoder from a no-copy image (quality is the only property: no metadata, no colour profile, no auxiliary
image), rebuilds the file with the library's fixed JFIF and EXIF headers (orientation 1, nothing else), and runs file
mode's fail-closed output check with `StripAll` (ADR 0005 decision 6) on the result. A failed check throws
`OutputValidationFailed` (Swift `outputValidationFailed(findings:)`) and returns no bytes. Nothing is written to disk.
The bytes cross to Swift through `EncodedJpeg.exportTo(address, capacity)` (one memory copy into the `Data`), never
byte by byte; `EncodedJpeg` holds them in the Kotlin heap, so no Objective-C object is created in Kotlin. The call runs
on a private concurrent queue of the facade, never on the main thread or the cooperative pool.

**Alternatives considered.** (1) Leave encoding to the app (`CGImageDestination` with the app's own properties): the
app can carry the source's metadata, profile or auxiliary images by mistake, and nothing checks the output. (2) Return
`NSData` made in Kotlin: an Objective-C object per call whose release waits for the Kotlin collector (ADR 0004 memory
rule). (3) A Kotlin `ByteArray` read from Swift: one Objective-C call per byte. Rejected.

### 5. Errors

```swift
public enum FaceMaskError: Error, Sendable, Hashable, CustomStringConvertible {
    case invalidConfiguration(parameter: String)
    case imageTooLarge(width: Int, height: Int, limit: Int)
    case sourceTooLarge(limitBytes: Int64)
    case notEnoughMemory
    case unsupportedImage
    case unsupportedTarget
    case ioFailed
    case outputValidationFailed(findings: [String])
    case maskPlanMismatch
    case sourceChanged
    case maskWriteFailed
    case modelIntegrity
    case modelRejected(findings: [String])
    case modelOutputInvalid(findings: [String])
    case runtimeUnavailable
    case concurrentUse
    case closed
    case cancelled
    case `internal`
    public var description: String { get }     // fixed English text, no path, no pixel data
}
```

- One case per Kotlin `FaceMaskException` subclass (19), with the Kotlin payloads. `findings` are the Kotlin codes;
  new codes may appear in any release, so DocC says to treat every code as a failure and not to match the list
  exhaustively.
- Cases that the async facade does not throw in practice are kept for the one-to-one rule and documented as such:
  `cancelled` (async methods throw `CancellationError` instead), `concurrentUse` (the actor serialises calls),
  `sourceTooLarge` (no stream form in Swift).
- **Mapping.** Every Kotlin function the facade calls is declared with `@Throws(FaceMaskException::class)`, so a
  Kotlin exception arrives as an `NSError` that carries the Kotlin exception object. The facade maps it by its Kotlin
  class, by a hand-written table. An `NSError` that the facade cannot map becomes `.internal`. Facade-side checks
  (ranges, URL kind, buffer layout) throw the same cases before Kotlin is called.
- **Adding a case** is a breaking change for callers that switch exhaustively. In 0.x it is made only in a minor
  version with a CHANGELOG "Breaking" entry; DocC recommends a `default` branch.

### 6. Mapping to the Kotlin API

| Kotlin (`io.github.shishark.faceblur`) | Swift | Notes |
|---|---|---|
| `FaceMasker(model, config)` | `actor FaceMasker`, `init(model:configuration:) async throws` | |
| `FaceMasker.detect/maskInPlace/applyMasks/checkProfile(PixelImage ...)` | overloads on `CGContext` and `CVPixelBuffer` | the facade picks the layout by decision 4a and wraps the buffer with `FaceBlurIos.wrapRgba` (decision 8) |
| — | `mask(_ image: CGImage)` | facade-only convenience (ADR 0004 decision 3; opaque canvas by decision 4a rule 4) |
| `FaceMasker.detectFile(path, options, token)` | `detectFile(at:options:)` | URL → path inside the security-scoped window |
| `FaceMasker.maskFile(path, target, options, plan, token)` | `maskFile(at:to:options:plan:)` | |
| `FaceMasker.close()` | `close()` | |
| `FaceMasker.modelInfo`, `.config` | `modelInfo`, `configuration`, `model` | |
| `PlanMasker(masking)`, `planFile`, `maskFile` | `struct PlanMasker` | |
| `DetectorModel.load(source, profile, maxModelBytes)` | `DetectorModel(source:profile:maxModelBytes:) async throws` | |
| `ModelSource.fromBytes`, `.fromFile` | `ModelSource.data`, `.file` | bytes through `FaceBlurIos.modelSourceFromMemory` (decision 8) |
| `DetectorProfile(format, inputWidth, inputHeight, scoreThreshold, expectedSha256, minMaskExpandX, minMaskExpandY)` | `DetectorProfile(format:inputWidth:inputHeight:scoreThreshold:expectedSHA256:minMaskExpandX:minMaskExpandY:) throws` | parameter name in errors stays `expectedSha256` |
| `DetectionFormat.YuNetLayout()`, `.GenericDetections(...)` | `DetectionFormat.yuNetLayout`, `.genericDetections(GenericDetections)` | |
| `PixelScaling(divisor, mean: FloatArray, std: FloatArray)` | `PixelScaling(divisor:mean:std:)` with `[Float]` | value semantics |
| `RowLayout`, `BoxFormat`, `CoordinateSpace`, `ColorOrder`, `TensorLayout`, `MaskOrigin` | enums, lower-camel-case cases | `INPUT_PIXELS` → `.inputPixels`, `CXCYWH` → `.cxcywh` |
| `FaceMaskerConfig(masking, onnxThreads)` | `FaceMaskerConfiguration(masking:onnxThreads:)` | |
| `MaskingConfig(style, shape, expandX, expandY, feather, color: Int)` | `MaskingConfiguration(style:shape:expandX:expandY:feather:color:)` | `MaskColor` → `0xFF000000 or (r shl 16) or (g shl 8) or b` |
| `MaskStyle.GRADIENT_OVAL`, `SMOOTH_FILL`, `SOLID` | `.gradientOval`, `.smoothFill`, `.solid` | no other case, ever, without the owner's approval |
| `MaskShape.ELLIPSE`, `RECTANGLE` | `.ellipse`, `.rectangle` | |
| `FileOptions(...)`, `OutputFormat.Jpeg(quality)`, `MetadataPolicy.StripAll`, `.KeepAllowlist(gps, dateTime, camera)` | `FileOptions`, `.jpeg(quality:)`, `.stripAll`, `.keepAllowlist(gps:dateTime:camera:)` | |
| `OutputTarget.NewFile(path, replaceExisting)`, `.OverwriteSource` | `.newFile(URL, replaceExisting:)`, `.overwriteSource` | `OutputTarget.PlatformTarget` has no Swift form (Android only) |
| `FaceBox`, `Mask` (`fromFace`, `manual`, `exact`), `MaskPlan` (`withMasks`), `UprightBox.fromRaw` | structs and static functions of the same names | Kotlin computes `Mask` geometry and `UprightBox`; one implementation of the rules |
| `Detection` (`toMaskPlan`), `MaskResult`, `FileResult`, `Timings`, `ExpectedFace`, `ProfileCheckResult`, `ModelInfo`, `TensorInfo`, `MaskerModelInfo` | structs of the same names | `Long` → `Int64` for sizes and versions, `Int` elsewhere |
| `FaceMaskException.*` (19) | `FaceMaskError` (19 cases) | decision 5 |
| `CancellationToken` | Swift `Task` cancellation | implemented inside the facade |
| `FaceBlurInfo` | — | `MaskerModelInfo.faceBlurVersion` |

### 7. Package layout (`faceblur-swift`)

```swift
// swift-tools-version: 6.2
// SPDX-License-Identifier: Apache-2.0
import PackageDescription

let package = Package(
    name: "FaceBlur",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "FaceBlur", targets: ["FaceBlur"]),      // the product for Swift apps
        .library(name: "FaceBlurOrtShim", targets: ["FaceBlurOrtShim"]),  // C shim for KMP apps (ADR 0010)
    ],
    dependencies: [
        .package(url: "https://github.com/microsoft/onnxruntime-swift-package-manager", exact: "1.20.0"),
    ],
    targets: [
        // Development: a locally built static XCFramework, never committed (git-ignored).
        // The released form (url + checksum) is added only when a release is approved.
        .binaryTarget(name: "FaceBlurCore", path: "Binaries/FaceBlurCore.xcframework"),
        .target(
            name: "FaceBlurOrtShim",                             // C: every ONNX Runtime C API call
            dependencies: [.product(name: "onnxruntime", package: "onnxruntime-swift-package-manager")],
            publicHeadersPath: "include"
        ),
        .target(
            name: "FaceBlurOnnxRuntime",                         // Swift glue; not a product
            dependencies: ["FaceBlurCore", "FaceBlurOrtShim"]
        ),
        .target(
            name: "FaceBlur",                                    // the facade; internal import FaceBlurCore
            dependencies: ["FaceBlurCore", "FaceBlurOnnxRuntime"],
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .testTarget(name: "FaceBlurTests", dependencies: ["FaceBlur"]),
    ],
    swiftLanguageModes: [.v6]
)
```

- `Sources/FaceBlur/` holds the facade, `PrivacyInfo.xcprivacy` (no tracking, no collected data, only the
  required-reason APIs actually used) and the DocC catalog. `Sources/FaceBlurOrtShim/` holds the C shim (the only
  code that calls the ONNX Runtime C API) and `Sources/FaceBlurOnnxRuntime/` the canonical Swift glue, a forwarder to
  the shim; the glue does not use the runtime's Objective-C API. `Templates/KMP/OnnxRuntimeGlue.swift` (not in a
  target) is the Swift copy for KMP apps (ADR 0010 decision 3). `Examples/SwiftUIDemo/` is an Xcode project that uses the package
  by local path. LICENSE, NOTICE and third-party notices sit at the root; no model file anywhere (ADR 0012).
- **The shim is a library product, not a copied file.** It has no Kotlin dependency, so a KMP app can link it next to
  the runtime without the facade, and a fix in the shim reaches every app through the package version instead of
  through copies. The product exposes a C header for the glue only: it is an integration seam like `OnnxBridge`, has no
  compatibility promise beyond "the same FaceBlur version", and is outside the Swift API baseline of decision 10.
  **Note for the KMP-consumer template (ST-033):** the template tells a KMP app to add the package and link only
  `FaceBlurOrtShim`. Whether that avoids resolving or linking the facade and the Kotlin XCFramework, and how a remote
  consumer resolves a package whose development form has a path-based binary target, are to be verified by a spike
  before the template is built; the fallback (copy the two shim files and extend the template comparison scripts) is
  in ADR 0010 decision 3 and needs no new repository.
- The package is built with `xcodebuild` and an iOS destination; host-only `swift build` cannot build it, because the
  XCFramework has no macOS slice.
- Tools version 6.2 is needed for `nonisolated(nonsending)`. ASSUMPTION: the Xcode release that ships Swift 6.2 is
  an acceptable minimum for apps in the 0.2 time frame.

### 8. Kotlin iOS entry points the facade needs

Everything else the facade calls is the shared Kotlin API (decision 6). The iOS-only public Kotlin surface is kept
to this list, in package `io.github.shishark.faceblur.ios`:

1. `OnnxBridge`, `OnnxBridgeFactory`, `FaceBlurIos.registerOnnxRuntime(factory): Boolean` (ADR 0010 decision 1).
2. `FaceBlurIos.libraryVersion: String` (the version check above).
3. `FaceBlurIos.wrapRgba(address: Long, width: Int, height: Int, bytesPerRow: Int, layout: IosPixelLayout):
   MutablePixelImage` with `enum class IosPixelLayout { RGBA_PREMULTIPLIED, RGBA_NONE_SKIP, BGRA_PREMULTIPLIED,
   BGRA_NONE_SKIP }` (ADR 0004 decision 1). It allocates no Objective-C object, copies no pixel and is valid only
   for the duration of the call it is passed to; KDoc says so. KDoc also states the validity rule of decision 4a
   rule 2: a premultiplied layout is refused (`UnsupportedImage`, buffer unchanged) when any pixel has a colour above
   its alpha, and a caller without meaningful alpha declares a skip layout.
   Additive (decision 4b): `FaceBlurIos.wrapRgba(address, width, height, bytesPerRow, layout, orientation: Int)`
   returns the upright view (size swapped for orientations 5 to 8); the form without `orientation` is orientation 1.
   Additive (decision 4c): `FaceBlurIos.encodeJpeg(address, width, height, bytesPerRow, layout, quality: Int):
   EncodedJpeg` and `class EncodedJpeg` (`byteCount`, `toByteArray()`, `exportTo(address, capacity)`).
4. `FaceBlurIos.modelSourceFromMemory(address: Long, byteCount: Long): ModelSource`: copies the bytes once into a
   library-owned array (a single memory copy), so that a model from `Data` never crosses byte by byte.
5. No new public symbol for file mode: the iOS `actual` of the file-mode seam (ImageIO decode with orientation and
   size reduction, JPEG encode from pixels, no auxiliary image) sits behind the shared `detectFile`, `maskFile` and
   `PlanMasker` path forms.
6. A rule, not a symbol: the garbage collection after each call (ADR 0004 decision 4) is an internal, iOS-only seam in
   the common `FaceMasker`; no public symbol exists for it, it is empty on Android and the JVM, and the facade does
   not call anything for it.
7. A rule, not a symbol: on iOS every Throwable that leaves a public Kotlin function is a `FaceMaskException`
   (anything else is wrapped as `Internal`), because a Kotlin exception not listed in `@Throws` ends the process
   when it reaches Objective-C.

### 9. Concurrency annotations

- All value types are `Sendable`; `FaceMasker` (an actor) and `PlanMasker` are `Sendable`.
- The facade has exactly three unchecked opt-outs: the borrowed-buffer box, `DetectorModel` (immutable `let`
  properties; the Kotlin `DetectorModel` is immutable and documented as thread-safe, ADR 0012) and the plan-binding
  holder. A grep checks the count.
- One `FaceMasker` per parallel worker; one `DetectorModel` shared by all of them.

### 10. Versioning and tooling

- `faceblur-swift` has the same SemVer version as `faceblur`; version `X.Y.Z` of the package uses the XCFramework
  from `faceblur` `X.Y.Z` (ADR 0001 decision 7).
- The public Swift API is recorded as a baseline with `xcrun swift-api-digester -dump-sdk` and an iOS target triple
  against the `xcodebuild` products, and compared at every milestone and release. The baseline file is registered
  under the repository's data-admission rules before its first commit.
- A Swift API break in 0.x bumps the minor version and gets a CHANGELOG "Breaking" entry, as on Kotlin.
- DocC is built with `xcodebuild docbuild` and an iOS destination. It covers getting started (one `maskFile` call),
  the editing flow with working-size coordinates, in-memory size guidance, the opt-ins with their risks (metadata
  allowlist, edited masks, `PlanMasker`), the discard and cancellation rules, and the caller obligations for buffers
  and encoding (ADR 0004 decision 5). The first paragraph of every async member states where it runs, the
  coordinate space and the errors.

### Open choices (owner review; the proposal's pick is marked)

- **A. In-memory carriers.** (1) `CGContext` + `CVPixelBuffer` + the `CGImage` convenience, `vImage_Buffer` later if
  asked for (**pick**: smaller surface, an `inout` C struct is awkward to borrow safely, and it can be added without a
  break); (2) all three carriers of ADR 0004 now.
- **B. Synchronous twin.** (1) async only in 0.2, `FaceMaskerSync` later if asked for (**pick**: one way to call, no
  main-thread error case, additive later); (2) `FaceMaskerSync` now, which needs a Swift-only `mainThread` error case.
- **C. Model file on disk (ADR 0010 decision 6).** ONNX Runtime 1.20.0's Objective-C API loads only from a path, so
  the earlier accepted bridge wrote a short-lived model file in the app's caches. (1) The glue creates the session from
  memory through the ONNX Runtime C API (`CreateSessionFromArray`) in a C target, with `OnnxBridge.open` taking an
  address and a byte count (**pick, and built and tested in the third milestone on the simulator**: nothing but the
  masked output on disk; ADR 0010 is amended accordingly); (2) keep the short-lived model file as a recorded
  exception. Neither changes the Swift public API. The owner's confirmation is still part of this ADR's review.
- **D. Error type.** (1) `enum FaceMaskError` as above (**pick**: matches the earlier decisions, pattern matching in
  `catch`); (2) a `struct FaceMaskError` with a `Code` type (like `URLError`), which allows new cases without a
  break but is less natural to match.
- **E. Validated configuration.** (1) Throwing initialisers plus `static let default` (**pick**: an invalid value
  cannot exist, the same as Kotlin); (2) plain memberwise initialisers checked at use, which defers the error to the
  first call.
- **F. Alpha in the `CGImage` convenience and in `CVPixelBuffer` (decision 4a).** Answered by the owner on 2026-11-11:
  option (2), refuse every in-memory image that is not opaque (no black fill, no repair). The other options, for the
  record: (1) composite a source with transparency over opaque black and accept a `CVPixelBuffer` whose alpha bytes
  are all 0 or 255 (the earlier pick); (3) keep alpha in the returned image (not recommended).

## Alternatives considered

**A. Use the Kotlin-generated Objective-C API directly.** Pro: no facade to maintain. Con: Kotlin types and `NSError`
out-parameters in app code; no `async`; Kotlin naming. Rejected.

**B. Kotlin Swift export.** Pro: generated Swift. Con: not stable; the project excludes it. Rejected.

**C. A class with completion handlers instead of an actor.** Pro: works without Swift concurrency. Con: not idiomatic
for Swift 6. Rejected.

**D. Isolated actor methods or plain `nonisolated` methods for in-memory buffers.** Pro: no `nonsending` and no
tools-version 6.2. Con: `CGContext` and `CVPixelBuffer` are not `Sendable`, so the caller would have to give the
buffer away (`sending`) and could not use it after the call, or the call would depend on the caller's
upcoming-feature settings. Rejected in favour of `nonisolated(nonsending)` plus an explicit queue hop.

**E. An ARGB `UInt32` colour with an alpha check, as in Kotlin.** Pro: identical to Kotlin. Con: a value that is
invalid by construction is possible. Rejected; `MaskColor` has no alpha.

**F. Accept another registered factory.** Pro: an app could plug in its own glue. Con: the facade would run a bridge
it does not know (ADR 0010 decision 5). Rejected.

**G. `Data` handed to Kotlin through a Kotlin `ByteArray` built from Swift.** Pro: no iOS-only entry point. Con: one
Objective-C call per byte for a model of tens of megabytes. Rejected; `modelSourceFromMemory` copies once.

**H. Declare every `CVPixelBuffer` `BGRA_PREMULTIPLIED`** (the usual Core Image convention). Pro: valid premultiplied
transparency is read correctly. Con: a producer that leaves alpha at 0 gives a buffer that reads as black while the
faces stay in the bytes, so detection finds nothing (decision 4a, the risk). Rejected.

**I. Declare every `CVPixelBuffer` `BGRA_NONE_SKIP` without a guard.** Pro: simple, and every stored colour is seen.
Con: at partial alpha the stored colour is the dimmed premultiplied colour, so a viewer that divides alpha out could see
a face that detection saw only faintly. Rejected in favour of the alpha guard (decision 4a rule 1a).

**J. Repair invalid premultiplied pixels instead of refusing the buffer** (read the stored colour where colour exceeds
alpha). Pro: no error for sloppy producers. Con: one buffer is read in two ways; the soft edge and the read-back check
need special cases; the defect stays hidden. Rejected (decision 4a, why reject).

**K. Copy the C shim into every consumer.** Pro: no package-resolution question for KMP apps. Con: a second native
source copy per app that has to be kept equal to the package's. Not the default; it is the fallback in ADR 0010
decision 3.

## Consequences

Positive
- Swift apps see only Swift types; the access-level import enforces it and the interface check guards it.
- Blocking work never runs on the cooperative pool or the main thread, including creation.
- A colour with transparency, a non-opaque style, or a weakening value outside the approved ranges cannot be
  expressed in Swift.
- The iOS-only Kotlin surface is four entry points plus the bridge, so the bridge work and the facade work line up.

Negative / risks
- The facade is hand-written; every Kotlin API change needs a facade change, a mapping-table update and a test.
- One audited `@unchecked Sendable` box carries the borrowed buffers; its correctness rests on the documented caller
  obligation.
- Apps need a Swift 6.2 compiler (tools version 6.2).
- Checking the Swift API and building DocC need macOS and iOS destinations, so they run in the macOS verification
  runs only.
- `OnnxBridge.open` takes a model address and byte count (open choice C, ADR 0010); the klib API dump on the 0.2 line
  records it, and the owner's review confirms it before 0.2.
- Decision 4a adds a read pass over the buffer for every in-memory call (the adapter's validity check) and, for a
  `CVPixelBuffer`, one more (the alpha guard). Callers with a buffer they know to be opaque and declared with a skip
  layout (a `CGContext` with `noneSkip*`) pay only the first-write cost they already had. The cost is measured at the
  second-pass milestone.
- Every in-memory image must be opaque (decision 4a as revised): an image with transparency is refused and the
  caller flattens it first. Callers with opaque images (photos) see no change.
- The orientation forms (decision 4b) and `encodeJPEG` (decision 4c) are additive: existing calls compile and behave
  as before (orientation `.up`).
- A second library product exists in the package (the shim); it has no compatibility promise beyond the same version.

## Verification

- **Kotlin isolation.** The `FaceBlur` target builds with `internal import FaceBlurCore`; `grep -c Kotlin` and
  `grep -c FaceBlurCore` on the generated `FaceBlur.swiftinterface` both return 0; no public name matches
  "anonymi[sz]"; the glue target compiles with `public import FaceBlurCore`.
- **Errors.** One test per `FaceMaskError` case (19), each mapped from the matching Kotlin exception; an unknown
  `NSError` becomes `.internal`; no message contains a path or pixel data.
- **Model.** A `DetectorModel` loads from `.data` and from a file URL obtained through the document picker
  (simulator); a non-file URL throws `invalidConfiguration(parameter: "source")`; security-scoped access is stopped
  on success and on every error (test hook counts the calls); a profile cannot be created without
  `expectedSHA256` (no initialiser omits it); a 50 MiB `Data` model crosses to Kotlin in one copy (the entry point is
  called once).
- **Configuration.** The Swift range checks match ADR 0002 decision 10 bound by bound, using the same table as the
  Kotlin defaults-and-ranges test; the internal test hook skips the Swift check and gets the same case from Kotlin;
  `MaskColor(127, 127, 127)` reaches Kotlin as `0xFF7F7F7F`.
- **Concurrency.** Strict-concurrency build with no warnings; exactly three `@unchecked Sendable` (grep); a caller on
  the main actor passes a `CGContext` to `maskInPlace` and draws into it after the call without a diagnostic
  (compile test); a cancelled `Task` gets `CancellationError`, no output or temporary file remains, an overwrite
  target is unchanged and a buffer is untouched or fully masked; eight tasks on one `FaceMasker` complete without
  `concurrentUse`; work runs on the masker's queue, never on the main thread or the cooperative pool (thread
  assertion in a test build); initialisation from the main actor does not block it.
- **Lifetime.** Dropping the last reference on the main thread does not crash and the session is released; `close()`
  twice is harmless; `close()` during a call completes after it.
- **Alpha and layout mapping (decision 4a as revised; simulator tests, fixtures from the public set only).**
  - `CGImage` form: an image with one pixel at alpha 254, a fully transparent image and a straight-alpha image with a
    face-coloured stripe under alpha 0 are refused with `unsupportedImage` and nothing is returned; an image whose
    format has an alpha channel but whose pixels are all opaque (premultiplied; `UIGraphicsImageRenderer`'s default
    format) is masked like the same image without alpha and comes back without alpha; the canvas carries the source's
    alpha 0 over poisoned memory (0xAB and 0xFF) and is refused.
  - `CVPixelBuffer`: alpha 0 everywhere, one byte of 128 and one byte of 254 give `unsupportedImage` with the buffer
    checksum unchanged; an opaque buffer is masked.
  - `CGContext` declared premultiplied with a pixel that is not opaque: `unsupportedImage` from the adapter, checksum
    unchanged; Kotlin `iosTest` covers the rule through `wrapRgba` (one pixel at 254, the last pixel, fully transparent,
    translucent, valid premultiplied data below 255) with the buffer unchanged, and on every read of a reused wrapper.
  - A source check fails if any facade code maps a `CVPixelBuffer` to a premultiplied layout.
- **Orientation (decision 4b).** Kotlin `iosTest`: for all eight orientations and all four layouts the upright view
  equals the mapping of `UprightBox.fromRaw` pixel by pixel; writes through the view land on the mapped stored pixels
  (padding untouched); masking a stored-turned buffer gives the faces and masks of the upright image, and every stored
  byte equals the masked upright pixel a viewer shows there; orientations outside 1 to 8 are refused. Swift
  (simulator, real runtime, a registered public fixture): the test helper's mapping agrees with ImageIO's own
  orientation transform (lossless TIFF); the canvas draws every orientation upright pixel for pixel; for all eight
  orientations the face is detected and masked in a `CGContext` (detect, maskInPlace, applyMasks, checkProfile), a
  `CVPixelBuffer` and through `mask(_:orientation:)`, each with the faces, masks and masked pixels of the upright
  fixture.
- **JPEG helper (decision 4c).** Kotlin `iosTest`: every layout encodes a JPEG that passes the output check, has only
  the fixed APP0, the library's APP1 and coding segments (no ICC profile, XMP, MPF or comment), reads back clean in
  ImageIO and leaves the buffer unchanged; segments planted by an encoder (EXIF text, ICC profile, XMP, comment) are
  dropped by the rebuild; trailing bytes and a truncated file fail the check, a frame of another size fails closed;
  refusals (not opaque, quality, row size, side above 65,535) happen before the encoder runs; no file appears in the
  app folders. Swift: `encodeJPEG` of a masked image from a Display P3 source has no GPS, IPTC, colour profile, TIFF
  or EXIF text, auxiliary image or orientation other than 1, the image's size, a smaller file at a lower quality, and
  no file created (snapshot); quality and opacity refusals write nothing.
- **Package products.** `swift package describe` (or the Xcode equivalent) lists exactly the products `FaceBlur` and
  `FaceBlurOrtShim`; the shim target has no dependency other than the runtime package; the KMP-consumer spike of
  ADR 0010 decision 3 is recorded before ST-033 starts.
- **Registration and version.** A fake factory registered through `FaceBlurCore` before the first facade use makes
  creation throw `runtimeUnavailable`; a mismatched library version throws `runtimeUnavailable`.
- **Files.** After every file-mode test (success, error, cancellation) the only new file on disk is the masked
  output; no model file and no copy of a source remain (static check plus a directory listing in the test).
- **API baseline and DocC.** The digester comparison against the committed baseline passes, or the change is marked
  breaking; the test hook is absent from the baseline; DocC inspection finds the discard and cancellation rules on
  every in-place method and on `FaceMaskError`.
- **Agreement.** The agreement and edit-flow tests of ADR 0002 also run through the facade on the simulator and the
  device; `UprightBox` and plan-binding tests as in ADR 0015.
