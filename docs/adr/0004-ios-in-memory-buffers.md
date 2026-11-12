# 0004. iOS in-memory mode: mask a caller-owned buffer in place

Status: Accepted
Date: 2026-11-01 (revised 2026-11-01 after review: premultiplied layouts, convenience-path memory run; per-call
memory gate and flat-memory criterion added to Verification; revised the same day: alpha 255 inside every mask and
the soft edge blends alpha outward, following ADR 0002's mask opacity invariant; decision 4 revised 2026-11-02 for
the mask stage's working area, ADR 0013; revised 2026-11-11 after the first iOS build: the garbage-collection call
becomes an internal iOS-only seam (decision 4), vImage tile scaling is deferred to the second-pass milestone
(decision 7), and decision 2 refers to the alpha rules of ADR 0011)

## Context

On iOS the project requires an ADR choosing between returning a new `CGImage` and masking a caller-provided mutable
buffer in place (`CVPixelBuffer`, a vImage buffer or a `CGContext`), with a preference for in place at 5,000 px, and
documentation that the app's own encoder must not copy auxiliary images (gain map, depth) from the source file.

Constraints carried over from the benchmark app (its iOS memory rules, measured there):

- One full-resolution buffer per photo; detection images and the encoder input are no-copy views of it.
- Never `CGBitmapContextCreateImage` (its result may be a copy).
- No per-call Objective-C objects created in or returned to Kotlin: their wrappers are freed only by the Kotlin GC,
  which does not see native memory. Pixels cross the Swift/Kotlin boundary as address + size + row stride.
- `GC.collect()` between photos.
- vImage Lanczos for tile scaling (the benchmark found it faster than CoreGraphics high-quality interpolation). In
  this library it is deferred (decision 7): iOS uses the common resampler until the second-pass milestone.

A `CGImage` is immutable, so any API that takes a `CGImage` must copy its pixels at least once. A 5,000 × 3,750 RGBA
photo is about 75 MB.

The typical pipeline (decode, bake orientation, scale down, mask, watermark, add metadata, encode) on iOS draws the
decoded image with its orientation transform at the target size into a bitmap `CGContext`. That context is already
a mutable buffer at the final size.

## Decision

1. **Primary API: in place on a caller-owned buffer.** The Swift facade offers
   - `maskInPlace(_ context: CGContext)` and `applyMasks(_ plan: MaskPlan, to context: CGContext)`;
   - `maskInPlace(_ pixelBuffer: CVPixelBuffer)` (locks the base address for the duration of the call);
   - `maskInPlace(_ buffer: inout vImage_Buffer, format: vImage_CGImageFormat)`;
   - and `detect(...)` overloads for the same three carriers (read-only).
   The facade extracts base address, width, height, bytes per row and layout, and calls the Kotlin
   `MutablePixelImage.wrapRgba(address, width, height, bytesPerRow, layout)`; Kotlin never receives an Objective-C
   object per call.
2. **Supported layouts** (anything else throws `FaceMaskError.unsupportedImage` before any work):

   | Carrier | Accepted |
   |---|---|
   | `CGContext` | bitmap contexts, 8 bits per component, 32 bits per pixel, RGB colour model; alpha `premultipliedLast`/`noneSkipLast` with big-endian or default byte order (bytes R,G,B,A), or `premultipliedFirst`/`noneSkipFirst` with 32-bit little-endian byte order (bytes B,G,R,A) |
   | `CVPixelBuffer` | `kCVPixelFormatType_32BGRA`, `kCVPixelFormatType_32RGBA`; planar/YUV formats rejected |
   | `vImage_Buffer` | the same 8-bit, 4-channel layouts, described by the passed `vImage_CGImageFormat` |

   Float, 16-bit, extended-range (HDR) and non-RGB buffers are rejected. Colour values are used as given; the
   agreement guarantee with file mode (ADR 0002) is stated for sRGB buffers, which is what file mode produces.

   Premultiplied layouts: the common pixel contract is non-premultiplied ARGB (ADR 0002), so the adapter
   un-premultiplies on read (detection input and the pixels the renderer blends with) and re-premultiplies on write.
   Mask interiors are written with alpha 255 whatever the input alpha was, where both forms are identical. In the
   soft edge, colour and alpha are blended in non-premultiplied form with the same weight towards the opaque fill,
   so the alpha there is never lower than the input alpha (ADR 0002 decision 10, mask opacity invariant). Pixels
   outside the masks and their soft edges are never written. For the read-back after writing (ADR 0002 decision 9),
   premultiplied adapters declare their 8-bit premultiplied storage form, so a soft-edge pixel with alpha below 255
   is compared after the same round trip and never raises `MaskWriteFailed` falsely. `noneSkip*` layouts are treated as opaque. For opaque photos (alpha 255 everywhere) the conversion is
   the identity.
3. **Secondary convenience: a new `CGImage`.** `mask(_ image: CGImage) -> (CGImage, MaskResult)` draws the image once
   into a library-owned RGBA buffer, masks it in place, and returns a no-copy `CGImage` over that buffer whose data
   provider frees the buffer when the image is released (never `CGBitmapContextCreateImage`). Its documented cost is
   one full-size copy while the caller still holds the original; DocC recommends the `CGContext` path for photos
   above about 3,500 px and for batches, and states that the returned image owns a library-allocated buffer that is
   freed only when the app releases the image.
4. **Memory rules hold in every path.** A call adds tile-sized buffers (the 640 px global pass, one tile at a time,
   the reused 1 × 3 × 640 × 640 float input tensor) plus the mask stage's working area (ADR 0013 decision 6); the
   caller's buffer is never copied. The working area grows with the masked area: a few megabytes for a photo with a
   few faces, and in the worst case (masks over almost the whole buffer) up to 4.3 bytes per pixel plus 32 MiB, about
   one copy of the pixels. On iOS a failed allocation cannot be relied on to give a catchable error: the system
   usually ends an app whose memory footprint crosses its limit before any allocation fails. So before allocating, the
   library compares the working area plus a margin with the memory the system reports as available (on a device, a
   figure of 0 is refused) and throws `NotEnoughMemory`, with the buffer untouched, when it does not fit. The DocC of
   the in-place calls states the worst case and advises process-private buffers: a buffer shared with another process
   (an IOSurface-backed `CVPixelBuffer` used by another process, or a shared file mapping) can keep a partly written
   image if the system ends the app during the final copy (ADR 0013 decision 6). The Kotlin side runs `GC.collect()`
   after each public call that did work, whether it returned or threw (outside the measured stage timings), which is
   the library's form of "between photos". The call sits behind an **internal, iOS-only seam**: an optional hook that
   the common `FaceMasker` invokes after a public call, which is empty on JVM and Android and set to `GC.collect()` on
   iOS only. It is not public API, and it is not the platform memory probe's `collect()`: on Android that function
   requests a garbage collection, and calling it here would force a collection after every Android call and change
   the behaviour of the Android artifact. A JVM/Android test asserts that the seam is never invoked there.
5. **Caller obligations (DocC and the integration guide).**
   - The buffer must not be read or written by other code during the call (the actor serialises calls on one
     `FaceMasker`, not other code touching the same buffer).
   - Encode from pixels: `CGImageDestinationAddImage` with an explicit, minimal properties dictionary. Never
     `CGImageDestinationAddImageFromSource` or `CGImageDestinationCopyImageSource` with the original source, never
     `CGImageDestinationAddAuxiliaryDataInfo` with data taken from the source (gain map, depth, disparity, mattes),
     and never pass the source's full properties dictionary through; add only the metadata the app means to add
     (for example GPS). Do not decode with an HDR request (`kCGImageSourceDecodeToHDR`) for this pipeline: the
     resulting extended-range buffer is rejected.
6. **File mode uses the same path.** File mode decodes with ImageIO into a library-owned RGBA buffer (orientation
   applied and size reduced in the same decode, ADR 0005), wraps it exactly like a caller buffer, and calls the same
   internal function, then encodes from a no-copy view of that buffer. No auxiliary image is ever added.

7. **Tile scaling uses the common resampler for now (deferred, 2026-11-11).** The benchmark's vImage Lanczos tile
   scaling is not implemented in 0.2's functional build. iOS scales tiles with the same resampler as Android and the
   JVM. Reasons: the detection parity against the reference (ADR 0009) was established with the common resampler, so a
   switch needs a parity re-run anyway; and no speed or size optimisation is made before the second-pass milestone
   (MP), which measures first. At MP, vImage Lanczos is a candidate: adopted only if it measurably helps on the
   reference iPhone and the parity tests still pass, with the decision recorded here. Until then the in-memory pipeline
   makes no claim about vImage, and no document may say that iOS uses it.
8. **Alpha handling is decided by the carrier mapping and the pixel adapter.** How a `CGContext` or `CVPixelBuffer`
   maps to one of the four layouts of decision 2, and which pixels the adapter refuses, are in ADR 0011 decision 4a.
   In short (revised 2026-11-11): every in-memory image must be opaque. A premultiplied layout with any pixel below
   alpha 255, a `CVPixelBuffer` with any alpha byte below 255 and a `CGImage` with any pixel that is not opaque are
   refused, never repaired, so that detection sees exactly what a viewer of the buffer sees. The un-premultiplying
   and soft-edge alpha rules of decision 2 therefore no longer apply on iOS: every accepted pixel has alpha 255.
   The in-memory forms also take an orientation and detect on the upright view (ADR 0011 decision 4b).

## Alternatives considered

**A. New `CGImage` as the only API.**
- Pro: simplest Swift signature; matches `UIImage`-centric code.
- Con: at least one full-size copy per call, and two full-size buffers alive until the caller drops the original;
  at 5,000 px that is about 150 MB plus decoder buffers, on the device class where the benchmark app already needed
  a headroom check before decoding. Rejected as the primary path; kept as the convenience in decision 3.

**B. `CVPixelBuffer` only.**
- Pro: one carrier type; common in camera code.
- Con: still pictures are usually decoded with ImageIO/CoreGraphics into a `CGContext`; forcing a `CVPixelBuffer`
  means another copy. Rejected as the only carrier.

**C. Accept `UIImage`.**
- Con: UIKit dependency in the facade, orientation ambiguity (`imageOrientation`), and `UIImage` may be backed by
  a lazily decoded or HDR image. Rejected; apps pass `uiImage.cgImage` to decision 3 if they must.

**D. Let Kotlin take `CGContextRef` directly for Swift apps too.**
- Pro: no Swift-side extraction code.
- Con: the facade still needs `CVPixelBuffer` and vImage support; a single primitive-typed Kotlin entry keeps the
  boundary rule simple and testable. `CGContextRef.asMutablePixelImage()` is still provided for KMP apps.

**E. vImage Lanczos tile scaling in the first iOS build.** Pro: it was faster than CoreGraphics in the benchmark.
Con: a second resampler on one platform, a parity re-run against the reference, and an optimisation made before any
measurement of the library. Deferred to MP (decision 7).

**F. Call the platform memory probe's `collect()` from the common code after each call.** Pro: no new seam. Con:
on Android it requests a garbage collection, so every Android call would change. Rejected for an internal iOS-only
seam (decision 4).

## Consequences

Positive
- Zero full-size copies in the recommended pipeline at 5,000 px; the pipeline keeps working on its own context.
- One Kotlin entry point for all carriers; the boundary passes only primitives.

Negative
- Two pixel layouts (RGBA and BGRA) in the pixel adapter; both are tested.
- The convenience API makes a copy; documented.
- `GC.collect()` after each call adds time per call (reported separately, ADR 0002 `Timings.gcMs`).
- Until MP, iOS tile scaling can be slower than the benchmark's vImage path; the cost is not claimed away and is
  measured at MP.
- The common code gains one internal after-call hook; it must stay empty on Android.

Follow-ups
- iOS bridge registration (output tensors without per-pass Objective-C objects): ADR 0010.
- Swift facade (mapping table, DocC, alpha rules): ADR 0011.
- vImage Lanczos candidate at MP (decision 7).

## Verification

- iOS simulator tests (Kotlin `iosTest` + Swift package tests): each accepted layout masks correctly (pixels inside
  a known mask match the reference fill from the common engine, pixels outside are untouched); each rejected layout
  throws before modifying the buffer (checksum unchanged).
- RGBA vs BGRA: the same image in both layouts gives identical boxes and identical mask pixels (after channel swap).
- Alpha < 255 (revised 2026-11-11, decision 8): a premultiplied buffer, a pixel buffer or a `CGImage` with any pixel
  below alpha 255 is refused with the buffer unchanged; the tests are listed in ADR 0011 Verification.
- Agreement test (ADR 0002) on the simulator and the iPhone: `maskFile` vs `maskInPlace` on a `CGContext` holding
  the same decoded pixels.
- Memory per call: during one in-place call on a 5,000 px buffer, the sampled peak footprint increase stays within
  decision 4's bound (tile buffers + the working area the mask stage computes for that call + a margin measured on a
  zero-face image); until that baseline exists, below 25 % of the buffer's byte size (ESTIMATE; the fixture has a few
  faces). The worst case of a buffer masked almost everywhere is tested in ADR 0013.
- Memory over time: on the reference iPhone, 200 consecutive 5,000 px in-place calls on a reused context keep the
  process footprint flat (criterion: upper 95 % confidence bound of the footprint slope ≤ 0.25 MB per photo, total
  drift ≤ 25 MB, repeated once, with a sensitivity run against a deliberately leaking build); a separate 200-call run of the decision-3 convenience
  path (each returned image released before the next call) must also stay flat and records its peak.
- After-call seam: on iOS the seam runs once after each public call that did work, on success and on a thrown error,
  and its time is not in the stage timings; a JVM/Android test fails if it is ever invoked there.
- Tile scaling: the iOS tests use the common resampler (the existing parity tests apply unchanged); no test or
  document refers to vImage until MP.
- Static check in review: no `CGBitmapContextCreateImage` and no per-call Objective-C allocation in `iosMain`
  (grep plus code review).
- Output-file test for the SwiftUI demo pipeline: the encoded file has no auxiliary images and no MPF/gain map.
