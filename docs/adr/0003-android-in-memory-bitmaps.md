# 0003. Android in-memory mode: which bitmaps, and what happens to gain maps

Status: Accepted
Date: 2026-11-01 (revised 2026-11-01 after review: gain-map removal order, memory threshold, tests; decision 5
revised 2026-11-02 for the mask stage's working area, ADR 0013)

## Context

In-memory mode on Android takes a `Bitmap` the app has already decoded, masks the faces in place without an extra
full-size copy, and returns faces and timings; the app's pipeline continues with the same bitmap (ADR 0002). The
project requires an ADR on two points: what happens to immutable or `HARDWARE` bitmaps (clear error, or one
documented copy), and what happens to an Ultra HDR gain map on Android 14+ (remove it, or mask it identically,
because the app's later encode would otherwise write the unmasked faces). The library supports Android 11 (API 30)
and later.

Platform facts (SOURCE: `android.graphics.Bitmap` in the Android SDK sources, platform 37.2, read 2026-11-01):

- `getPixels(...)`: "The returned colors are non-premultiplied ARGB values in the sRGB color space"; throws
  `IllegalStateException` "if the bitmap's config is HARDWARE".
- `setPixels(...)`: throws `IllegalStateException` "if the bitmap is not mutable"; colours are given as
  non-premultiplied sRGB ARGB.
- `copy(config, isMutable)`: copies the pixels into a new bitmap; throws for `HARDWARE` + mutable; returns `null`
  if the copy cannot be made.
- `hasGainmap()`, `getGainmap()`, `setGainmap(gainmap)` ("Sets a gainmap on this bitmap, or removes the gainmap if
  null"): API 34+; `setGainmap` has no mutability check in the source.
- `ImageDecoder` by default may return hardware-backed, immutable bitmaps; `ALLOCATOR_SOFTWARE` and
  `isMutableRequired = true` give a mutable software bitmap (the bench decodes this way).

Bench behaviour being extracted: the decoder clears the gain map right after decoding and again before
`compress`; pixel access goes through `getPixels`/`setPixels`; tile crops are drawn with `Canvas` from the source
bitmap.

## Decision

1. **Accepted bitmaps.** `maskInPlace(bitmap)` and `applyMasks(bitmap, plan)` require a bitmap that is not
   recycled, is mutable, and has config `ARGB_8888`. `detect(bitmap)` requires not recycled, not `HARDWARE`, and
   `ARGB_8888` (it may be immutable). Any colour space is accepted, because pixel access converts to and from sRGB
   per call; the agreement guarantee of ADR 0002 is stated for sRGB bitmaps (file mode always produces sRGB).
2. **Everything else is a clear error, checked before any work.** Immutable, `HARDWARE`, `RGB_565`, `ALPHA_8`,
   `RGBA_F16`, `RGBA_1010102` or recycled bitmaps throw `FaceMaskException.UnsupportedImage(reason)` with a reason
   enum (`IMMUTABLE`, `HARDWARE`, `CONFIG`, `RECYCLED`). The library never copies silently. The KDoc and the
   integration guide show the one documented copy the app can make itself, with its cost:
   `bitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ true)` (one full-size allocation, about 75 MB for
   5,000 × 3,750 px; the app must then continue with the copy and drop the original), and the decode settings that
   avoid the copy altogether (`ALLOCATOR_SOFTWARE`, `isMutableRequired = true`).
3. **Gain map: removed, not masked.** On API 34+, `applyMasks` and `maskInPlace` call `setGainmap(null)` when
   `hasGainmap()` is true, **after the last cancellation check and immediately before the first face pixel is
   written** (ADR 0002 decision 8), and always (also when no face is found or the plan is empty), so a bitmap that
   went through a completed masking call never carries a gain map. A call cancelled before that point leaves the
   bitmap untouched, gain map included. `MaskResult.gainMapRemoved` reports the removal.
   `detect` never modifies the bitmap and reports `Detection.hasGainMap` so an editing UI can warn early.
   Consequence: HDR rendering of that photo is lost; the SDR pixels are unchanged outside the masks.
4. **Android 11-13.** The gain-map API does not exist below API 34, and the platform decoder there does not attach
   gain maps to bitmaps (ASSUMPTION based on Ultra HDR being introduced in Android 14; covered by the API 30
   question below), so there is nothing to remove. The integration guide states for all versions: never copy APPn
   segments (MPF, XMP, EXIF thumbnail) from the source file into the output; encode from the bitmap.
5. **No copy of the bitmap; memory bounded by the masks.** Detection reads the bitmap through `Canvas`-drawn crops
   (at most one 640 px global pass and one tile at a time) and `getPixels` of tiles; masking reads and writes only
   the mask regions. Memory added by a call is the tile buffers (one ≤ 640 px global-pass bitmap or one 640 px tile
   at a time, and the 4.9 MB input tensor) plus the mask stage's working area (ADR 0013 decision 6). The working
   area grows with the masked area, not with the photo: a few megabytes for a photo with a few faces, and in the
   worst case (masks over almost the whole bitmap) up to 4.3 bytes per pixel plus 32 MiB, about one copy of the
   pixels (about 141 MB at 25 MP). It is allocated completely before the first pixel of the bitmap is written; if
   that fails, the call throws `NotEnoughMemory` and the bitmap is untouched. The KDoc of the bitmap overloads
   states the worst case.
6. **File mode uses the same path.** File mode decodes into a mutable software `ARGB_8888` sRGB bitmap, clears the
   gain map, then calls the same internal masking function (ADR 0002, decision 7), and clears the gain map again
   before `compress` (defence in depth, as in the bench).

## Alternatives considered

**A. One implicit copy for immutable/`HARDWARE` bitmaps, returned to the caller.**
- Pro: works with any bitmap; fewer errors for beginners.
- Con: the caller's original stays unmasked; an app that ignores the return value and encodes the original leaks
  every face. Doubles peak memory at 5,000 px without the caller asking. Breaks "the pipeline continues with the
  same image". Rejected.

**B. A separate library function `maskCopy(bitmap): Bitmap`.**
- Pro: explicit.
- Con: a thin wrapper over one platform line; one more public symbol. Not in 0.x; can be added later without
  breaking anything.

**C. Mask the gain map identically.**
- Pro: keeps HDR.
- Con: the gain map has its own, usually lower, resolution and format; masks must be scaled conservatively
  (rounded outward, feather included) so that no face detail survives at the coarser grid, and the fill value
  must be defined in gain-map units. That is a second masking path that needs its own privacy proof and tests.
- Deferred: a possible later opt-in (`GainMapPolicy.MASK`) if integrators need HDR; removal stays the default.

**D. Remove the gain map only when at least one face is masked.**
- Pro: keeps HDR for photos without faces.
- Con: detection can miss faces, and a missed face is equally visible in the gain map; behaviour depends on the
  result. Rejected for simplicity and predictability.

## Consequences

Positive
- Fail-closed: an unsupported bitmap is never half-processed; a processed bitmap never carries a gain map.
- Peak memory of in-memory mode is the app's bitmap plus tile-sized buffers.

Negative
- Apps decoding with default `ImageDecoder` settings get an error until they set the allocator; the error message
  names the fix.
- HDR is lost for Ultra HDR photos.

Follow-ups
- `GainMapPolicy.MASK` (alternative C) only if integrators ask for HDR, with its own privacy proof.
- Confirm decision 4 (no gain maps below API 34) on an API 30 emulator if the owner approves that run.

## Verification

- Host tests (Robolectric, native graphics): each unsupported kind (immutable, `HARDWARE` where the host supports
  it, each wrong config, recycled) throws `UnsupportedImage` with the right reason and leaves the bitmap's pixel
  checksum unchanged.
- Gain-map test on the Android 37 emulator with a public-safe synthetic Ultra HDR JPEG: precondition (the test
  FAILS, not skips, if it does not hold): the decoded bitmap reports `hasGainmap() == true` and the input bytes
  contain an MPF secondary image. Then `maskInPlace` → `hasGainmap() == false`, `gainMapRemoved == true`; the bitmap
  compressed by the test like an app would → no MPF/`hdrgm` data, exactly one image. Negative control: the same
  input compressed without the library DOES contain the gain map (proves the check can fail).
- Zero-face case: a synthetic Ultra HDR image without faces also loses its gain map; so does `applyMasks` with an
  empty plan.
- `detect` on a gain-map bitmap reports `hasGainMap == true` and leaves `hasGainmap()` true (no modification).
- Cancellation before mask writing (test hook) leaves the gain map and the pixel checksum unchanged.
- Memory: an instrumented test on the 5,000 px fixture samples Java and native heap during `maskInPlace` (a polling
  thread at ≤ 5 ms intervals, or an allocation-tracking seam); the peak increase must stay within decision 5's bound:
  tile buffers + the working area the mask stage computes for that call (ADR 0013 decision 6) + a margin measured on
  a zero-face image. Until that baseline exists, the threshold is 25 % of the bitmap's byte size (ESTIMATE; the
  fixture has a few faces, so its working area is far below that); a half-size copy fails either form. The worst
  case of a bitmap masked almost everywhere is tested in ADR 0013.
- Agreement test of ADR 0002 includes an Android device run.
- Open: whether to add an API 30 emulator run for decision 4 is a question for the owner (disk space).
