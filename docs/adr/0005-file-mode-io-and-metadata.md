# 0005. File mode: decoding, atomic output, content URIs, metadata, orientation and ICC

Status: Accepted (the owner decided the content-URI overwrite behaviour, the formats and the pixel limit; see
decisions 1, 4 and 7)
Date: 2026-11-01 (revised 2026-11-01 after review: `"wt"` restored, encoder-output rebuild, pixel limit, allowlist
defaults, temporary file in both target modes; later the same day: content-URI probe before truncation and one
read-back rule, exclusive create for new files, EXIF size limit, temporary-file permissions; accepted the same day:
content-URI overwrite only as an explicit opt-in, full read-back comparison, write descriptor closed before the
read-back, empty target after a failed write where possible, JPEG output with JPEG/HEIC/PNG input, 25 MP limit on the
decoded image with a reduced-size decode of larger sources; revised the same day after the owner approved the
settings table: detection on a working image of at least 3,500 px with the scale-down to a smaller output after
masking, and a pixel limit of 13-100 MP that fails instead of lowering the detection size; revised 2026-11-02 after
the file-mode core reviews: the library writes a fixed JFIF APP0 instead of copying the encoder's, the output carries
no ICC profile in 0.1.0, and the 65,535 px per-side check is listed in decision 1; amended 2026-11-03: the size and
memory checks of decision 1 are stated as generous technical limits that fail safely; amended 2026-11-03 (project
owner): a fixed 64 MiB cap on the stream source, error `SourceTooLarge`, decision 1; amended 2026-11-03 after the
reviews of the common file-mode orchestration: the order of the target refusals and the plan fingerprint in
decision 1, and in decision 2 no replacing of symbolic links or of files with other names, `OverwriteSource` bound
to the decoded file, and no-replace publication by hard link or exclusive create only; amended the same day
(project owner): the changed-source error `SourceChanged`, the refusal of plans without a binding and `planFile`
in decision 1, and the differential pair through the public API)

## Context

File mode (ADR 0002) decodes once, bakes the orientation, optionally scales down, detects and masks, and encodes
once to a new file or over the input. Project rules for this mode:

- Overwriting is atomic: write a temporary file next to the target and replace the target only after a successful
  write; on failure the original is unchanged.
- On Android content URIs, open for writing with truncation (`"wt"`) and test that no bytes remain after the JPEG
  end marker.
- Metadata: the default strips everything. An opt-in keeps an allowlist of non-image metadata (for example GPS,
  date and time, camera make and model) but never anything that can carry pixels: no EXIF thumbnail, MakerNote,
  XMP, gain map, depth map, matte or other auxiliary image. The orientation is always baked in and written as 1.
- Privacy rules from the extraction plan: re-encode from pixels only; on Android 14+ remove the gain map before
  `compress`; on iOS never write auxiliary images; no temp files of originals; no image bytes in logs; an
  automated check of every output file (no GPS unless allowed, no EXIF thumbnail, no XMP, no gain map, no extra
  images, no bytes after the end marker).

Platform facts used (SOURCE, Android SDK sources platform 37.2, `ContentResolver`, read 2026-11-01): modes
`"r"`, `"w"`, `"wt"`, `"wa"`, `"rw"`, `"rwt"`; "the implementation of "w" is up to each Provider implementation and it
may or may not truncate"; `openFileDescriptor`: ""w" is write only and "wt" is write and truncate". The
`DocumentsContract` and `MediaStore` APIs offer no replace-by-rename of an existing document (ASSUMPTION from the
API surface; to be confirmed by the android-developer).

Points settled by project decision and implemented here:
- the orientation tag is written as 1 (not omitted), and the source ICC profile is never copied; in 0.1.0 the output
  carries no ICC profile at all (decision 5);
- the JFIF APP0 segment is a fixed 18-byte segment (length field 16) written by the library, never a copy (no JFIF thumbnail,
  decision 1 step 7);
- overwriting an existing Android content URI is offered only as an explicit opt-in, with the residual risk
  documented (decision 4);
- output is JPEG only; input is JPEG and HEIC, and PNG because both platform decoders already read it (decision 7);
- the default pixel limit is 25 MP, applies to the decoded image (not to the source file), and is configurable
  between 13 and 100 MP; with a maximum long side set, larger sources (camera photos up to 200 MP) are decoded at the
  reduced size instead of being rejected (decision 1);
- detection never runs on fewer than 3,500 px on the long side (or the source size if smaller): `maxLongSide`
  (the working size) cannot be set below 3,500 px, and a smaller output is a separate setting,
  `maxOutputLongSide`, applied by scaling the masked image down after masking; the pixel limit may never push
  detection below this floor (decision 1).

## Decision

### 1. Pipeline (both platforms)

Before step 1, the target refusals that need no I/O run (ADR 0015 decision 4 step 2: for example
`OverwriteSource` for a source that cannot be replaced in one step, or a `NewFile` path equal to the source path
without `replaceExisting`); they read nothing, so their `UnsupportedTarget` is reported before any error about the
source. Then a plan without a binding is refused (`MaskPlanMismatch`, ADR 0015 decision 6), also without I/O. A
path source is opened only if it is a regular file (ADR 0015 decision 4, codec rules).

1. Open the source and identify its format from its first bytes (decision 7), never from the file name or MIME
   type; anything other than JPEG, HEIC or PNG throws `UnsupportedImage`. If `MetadataPolicy.KeepAllowlist` is set,
   read the allowlisted fields through the platform reader (Android `android.media.ExifInterface` on the source; iOS
   `CGImageSourceCopyPropertiesAtIndex` read through CoreFoundation in Kotlin) into a typed, validated
   `PhotoMetadata` value. Raw metadata bytes are never kept. A field the platform reader cannot provide for the
   format is simply absent from the output.
2. **Size checks, before any pixel is allocated.** Read the source width and height from the header and compute,
   with the orientation applied (never enlarging):
   - the **working size** (the decoded image that detection and masking run on, ADR 0002 decision 4): the source
     size, reduced to a long side of `maxLongSide` when the source is larger (`maxLongSide = 0`: source size).
     `maxLongSide` is 0 or at least 3,500, so the working image is never below 3,500 px on the long side unless the
     source is smaller;
   - the **output size**: the working size, reduced to a long side of `maxOutputLongSide` when that is set and
     smaller (`0`: the working size). It is never larger than the working size. Allowed values of
     `maxOutputLongSide`: 0, or **64 px and more**; 1 to 63 throw `InvalidConfiguration` at construction. The minimum
     has no protection role (detection never depends on it). It keeps outputs useful: below about 64 px a photo is
     only a few JPEG blocks wide, so smaller values are almost certainly a mistake in the caller's units. Thumbnails
     of 64 px and more stay possible. The short side follows the aspect ratio and is at least 1 px.
   - The **pixel limit** `FileOptions.maxDecodedPixels` (default 25,000,000; allowed 13,000,000 to 100,000,000,
     ADR 0002 decision 10) applies to the working size, not to the source file. If the working size is above the
     limit, the call throws `ImageTooLarge(width, height, limit)` and writes nothing; the library never decodes or
     detects at a smaller size to fit the limit. With `maxLongSide = 0` the working size is the source size, so a
     source above the limit is rejected. With `maxLongSide = 3500` the working image has at most 3,500 × 3,500
     pixels (12.25 MP), which every allowed limit accepts.
   - A **per-side limit**: each side of the working size must be at most 65,535 px, JPEG's own maximum per side
     (ADR 0002 decision 9); otherwise the call throws `ImageTooLarge` and nothing is decoded. It can be reached with
     `maxLongSide = 0` or with a value above 65,535 (`maxLongSide` has no upper bound; any other value reduces the
     long side to at most `maxLongSide`), so the check runs for every value; the pixel limit above still applies and
     is usually the tighter one.
   - A **source ceiling** of 200,000,000 pixels (camera photos reach about 200 MP) is checked on the header in every
     mode and is not configurable; a larger header throws `ImageTooLarge`. It bounds the work of the reduced-size
     decode and rejects headers that declare absurd sizes.
   - A **stream-source cap** of 64 MiB (67,108,864 bytes), fixed and not configurable in 0.1.0, for the stream
     form (`maskJpeg`), the only form that FaceBlur reads into a memory buffer. The format is checked on the first
     bytes before the rest is buffered (step 1); a longer stream throws `SourceTooLarge(limitBytes)` before
     decoding, and nothing is written. The buffer's growth is reserved through the memory check of ADR 0013
     decision 6 (`NotEnoughMemory` if it does not fit) and released once the image is decoded (ADR 0015 decision
     4). Path and `Uri` sources are read by the platform decoder from the file, file descriptor or content resolver,
     never from a FaceBlur buffer of the whole input, and are not capped by this limit; the error's
     message points to them for larger inputs.
   - On iOS a free-memory check sized for the decode peak (below) throws `NotEnoughMemory`.
   These checks exist so that the process is never killed for lack of memory. Each is a generous technical limit;
   exceeding it fails safely (the typed error above, nothing decoded or written). Tests that must process sources
   above the default pixel limit at full size raise `maxDecodedPixels` within its allowed range in their own test
   configuration; the library default does not change for them.
   After these checks and still before decoding, the writer's pre-check runs (decision 2; it creates and opens
   nothing for writing), and a plan passed to `maskFile` must have the working size (`MaskPlanMismatch`).
3. **Decode once** with the orientation applied and, when the working size is smaller than the source, at the
   working size in the same step, so a large source is never held at full size: Android `ImageDecoder` with `setTargetSize`, software
   allocator, mutable, sRGB target colour space, gain map cleared; iOS ImageIO thumbnail API with "create from image
   always", "with transform" and the max pixel size, drawn into a library-owned sRGB RGBA buffer, with the
   stored-image fallback the benchmark app uses; the decoded image is released right after drawing, so two
   working-size buffers exist only during this step. How much memory each platform decoder uses internally for a
   reduced-size decode of a very large JPEG or HEIC (subsampled decoding versus a full-size intermediate) is not
   documented; it is MEASURED with a large-source test (Verification) before the iOS headroom formula is final.
   PNG sources with transparency are composited onto opaque white right after decoding, before detection, so that
   detection, masking and the JPEG encoder all see the same opaque pixels.
   Right after decoding, and after this compositing, the fingerprint of the working image is computed over the
   opaque pixels that are masked (ADR 0015 decision 6): `detectFile`
   keeps it in the `Detection` and in the plans built from it, and `planFile` in the empty plan it returns;
   `maskFile` refuses a working image with another fingerprint before masking (`SourceChanged`), so a source that
   changed between the two calls is never written with the masks of the old pixels. A plan without a fingerprint is
   refused with `MaskPlanMismatch` before the source is opened.
4. Mask through the in-memory core on the working image (ADR 0002 decision 7). Cancellation follows ADR 0002
   decision 8.
5. **Scale down after masking**, only when the output size is smaller than the working size: the masked working
   image is resampled into a new output-size buffer with a filtering scaler (Android: a new software `ARGB_8888`
   bitmap drawn with filtering; iOS: vImage Lanczos, as in the benchmark's tile scaling), and the working buffer is
   released. Only masked pixels are ever scaled, so the masks stay opaque at the output size; output pixels at a
   mask's edge mix the mask with pixels outside it, never with face pixels. Peak memory of this step: the working
   image plus the smaller output buffer. Coordinates in the result stay in the working image's space (ADR 0002
   decision 4).
6. Encode JPEG from pixels into memory (Android `Bitmap.compress`, with the gain map cleared on the bitmap that is
   encoded; iOS `CGImageDestination` with only the quality property, from a no-copy view).
7. Build the output bytes by parsing the encoder output and rebuilding it: SOI, the library's fixed JFIF APP0, the
   library's EXIF APP1 (decision 3), then the remaining non-APPn segments unchanged. Every APPn and COM segment the
   encoder produced is dropped, including its own APP0 and any APP2 `ICC_PROFILE` (decision 5). The fixed APP0 is
   written from constants, never copied: marker `FF E0`, length 16, identifier `JFIF\0`, version 1.01, density
   units 0 (aspect ratio only), density 1 × 1, thumbnail 0 × 0 (18 bytes with the marker). A JFIF APP0 may carry an
   uncompressed thumbnail; writing it from constants means no thumbnail can reach the output. The frame size in
   the encoder output must equal the planned output size; another size fails closed (`Internal`) and nothing is
   written, and the result's sizes are taken from the validated output. Encoder output that
   the parser cannot read throws `OutputValidationFailed` with the parser's codes (ADR 0002 decision 9) and nothing
   is written. Then the runtime output validator runs (decision 6). A failure throws `OutputValidationFailed` and
   nothing is written.
8. Last cancellation check; then write the bytes to the target (decisions 2 and 4).

The input file is never copied. The only temporary file the library creates, in both target modes, holds the final
masked, re-encoded output and nothing of the original; it is created after encoding and removed on failure or
cancellation.

### 2. Atomic write for file-system paths

- `OutputTarget.NewFile(path, replaceExisting = true)` and `OutputTarget.OverwriteSource` write through a
  temporary file in the target's directory (`.<name>.faceblur-<random>.tmp`, created exclusively with owner-only
  permissions `0600` where the file system supports POSIX modes), `fsync` it, then atomically rename it over the
  target (`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` on Android/JVM, `rename(2)` on iOS), then `fsync` the
  directory so the rename survives a power loss.
- `OutputTarget.NewFile(path, replaceExisting = false)` must never replace a file, including one created by
  someone else between the check and the publication. The temporary file is therefore published with a hard link,
  which fails if the target exists, also as a symbolic link (`link(2)`: `android.system.Os.link` on Android,
  `Files.createLink` on the JVM, `link` on iOS), and the temporary name is then unlinked. An existing target makes
  the call throw `UnsupportedTarget` with the target untouched. This case never uses a rename (`rename(2)`,
  `File.renameTo`, `Files.move`), because on Unix a rename replaces an existing target. If the file system does not
  support hard links (ASSUMPTION: some FUSE or FAT-backed locations; the platform's "not supported" errors of
  `link` are mapped per platform and TESTED), the library instead creates the target itself exclusively
  (`O_CREAT | O_EXCL`, owner-only permissions where supported), writes the masked bytes into it, `fsync`s it, and
  deletes the file it created if anything fails. An existing target again fails with `UnsupportedTarget`. Until
  that write ends, the target can hold an incomplete file of masked output, never anything of the original. Any
  other error of `link` fails the call.
- **Never replace a symbolic link or a file with other names.** A rename replaces a directory entry. On a symbolic
  link it replaces the link and leaves the file it pointed to unchanged; on a file with several hard links the
  other names keep the old content. In both cases the call would report success while the old pixels (with
  `OverwriteSource`, the unmasked original) stay readable. Before every replace (`NewFile(path, replaceExisting =
  true)`, `OverwriteSource`, `overwriteNonAtomic` with a `file:` URI, and the iOS item-replacement path), the
  writer therefore reads the target's own entry without following a link (`lstat`; `LinkOption.NOFOLLOW_LINKS` on
  the JVM) and throws `UnsupportedTarget`, target untouched, if the entry is a symbolic link, is not a regular file,
  or has more than one hard link. Only the last path component counts: symbolic links in the directories of the
  path are followed as usual (for example Android's `/sdcard`). The message names the reason, never the path.
- **`OverwriteSource` replaces exactly the decoded file.** The codec records the identity of the file it opened
  (device and inode of the opened descriptor). Before the replace, the source path must still name that file;
  otherwise the source was replaced or moved during the call, the decoded original could survive under another
  name, and the call throws `UnsupportedTarget` with nothing written.
- **Check, then check again.** These checks run in the writer's pre-check before decoding (which creates and opens
  nothing for writing) and again immediately before the publication. A change made by another process between that
  last check and the rename is outside the library's control; the KDoc names it, as for concurrent writers to
  content URIs (decision 4).
- On any failure or cancellation the temporary file is deleted and the target is untouched. The temporary file's
  name and path never appear in an error.
- iOS security-scoped or provider-backed URLs may not allow creating a sibling file. The library then uses the
  system's item-replacement directory for the target's volume (`FileManager.url(for: .itemReplacementDirectory,
  in: .userDomainMask, appropriateFor: target, create: true)`) and `FileManager.replaceItemAt`, which keeps the
  same-volume atomic replace that "next to the target" is meant to give. The temporary file there follows the same
  rule: masked output only, owner-only permissions, removed on failure. If neither is permitted, it throws
  `UnsupportedTarget` without touching the target. Which locations allow which path is TESTED by the ios-developer
  (app container, Files "On My iPhone", iCloud Drive) before the iOS file-mode story is accepted.
- A process crash between write and rename can leave a temporary file that contains masked output only; the
  integration guide says so. Automatic clean-up of stale temporary files is not in 0.x.

### 3. Metadata policy and orientation

- **Default `StripAll`.** The output carries exactly one APP1 EXIF segment whose IFD0 holds only `Orientation = 1`
  (plus the mandatory TIFF header); no IFD1 (so no thumbnail), no EXIF sub-IFD, no GPS IFD. Writing the tag,
  rather than omitting it, follows the rule "written as 1" literally, and it gives the output test one exact
  expectation. Requirements and tests expect exactly `Orientation = 1`.
- **Opt-in `KeepAllowlist(gps = false, dateTime = false, camera = false)`.** Every flag defaults to `false`, so GPS
  is kept only when the app names it. A `KeepAllowlist` with no flag set is rejected when it is constructed
  (`FaceMaskException.InvalidConfiguration`, ADR 0002), so an opt-in always says what it keeps. Closed list of fields; each value is parsed,
  validated and re-serialised by the library's EXIF writer, never copied as bytes:

  | Group | Tags written |
  |---|---|
  | `gps` | GPSVersionID, GPSLatitudeRef/GPSLatitude, GPSLongitudeRef/GPSLongitude, GPSAltitudeRef/GPSAltitude, GPSTimeStamp, GPSDateStamp, GPSHPositioningError, GPSImgDirectionRef/GPSImgDirection |
  | `dateTime` | DateTimeOriginal, DateTimeDigitized, OffsetTimeOriginal, OffsetTimeDigitized, SubSecTimeOriginal (at most 9 digits) |
  | `camera` | Make, Model (printable ASCII, at most 64 characters each) |

  Never written in any mode: MakerNote, UserComment, ImageDescription, Artist, Copyright, any thumbnail, XMP, IPTC,
  COM, MPF, source ICC profile, gain map, depth, matte or any other auxiliary image. `Orientation` is always 1.
- The EXIF writer lives in `commonMain` (one implementation, byte-identical output on both platforms). The segment
  goes directly after the library's fixed JFIF APP0 segment (decision 1 step 7); the exact position
  is fixed by a reader test (Android `ExifInterface`, iOS ImageIO and one independent parser must all read
  `Orientation = 1` and the allowlisted values).
- In-memory mode never touches metadata; the app adds its own after FaceBlur (recommended order: decode, bake
  orientation, scale down, FaceBlur, watermark, app metadata, encode).

### 4. Android content URIs

There is no atomic replace for an existing content URI, so the rule "on failure the original is unchanged" cannot
be guaranteed there. Overwriting one is therefore **never implicit**: it is an explicit opt-in on the target, and the
library narrows the window and verifies the result.

**Targets (ADR 0002 decision 4).**
- `AndroidOutputTargets.newFile(uri)`: a new, empty document (for example from `ACTION_CREATE_DOCUMENT` or a fresh
  `MediaStore` insert). If the probe below finds the target non-empty, the call throws `UnsupportedTarget` before
  anything is opened for writing, and the target is untouched. A `file:` URI behaves exactly like
  `OutputTarget.NewFile(path, replaceExisting = false)` (decision 2).
- `AndroidOutputTargets.overwriteNonAtomic(uri)`: the opt-in. It replaces the content of an existing content URI,
  including the source itself, with the non-atomic write below; its name and KDoc state the residual risk. For a
  `file:` URI it uses the atomic path replace of decision 2, never anything weaker.
- `OutputTarget.OverwriteSource` with a `content:` source throws `UnsupportedTarget` before any decoding, and the
  message names `overwriteNonAtomic`. With a `file:` source it follows decision 2.

**Write sequence (both Android targets).**
1. Everything up to the validated output bytes happens in memory before the target is opened.
2. **Probe before any write.** Open the target with `openFileDescriptor(uri, "r")` and `fstat` it. The call throws
   `UnsupportedTarget`, with nothing opened for writing and the target untouched, if:
   - the provider does not grant read access (then the read-back in step 5 would be impossible; see the rule below);
   - or the descriptor is not a regular file (for example a pipe from a provider), because truncation could not be
     verified;
   - or the target is non-empty and the target is not `overwriteNonAtomic`.
   The probe is closed before step 3.
3. Open the target with `openFileDescriptor(uri, "wt")` (write and truncate; the platform maps it to
   `O_WRONLY | O_CREAT | O_TRUNC`). If the provider refuses `"wt"`, the call throws `UnsupportedTarget`; the library
   never falls back to `"w"`, whose truncation is provider-defined. If `fstat` on this write descriptor is not a
   regular file although the probe was, the library writes nothing and throws `UnsupportedTarget`. The provider may
   already have truncated the target at this open, which is part of the window in step 7.
4. Write all bytes, call `ftruncate(length)` explicitly, `fsync`, and check that `fstat` size == length on the
   write descriptor (all work on a write-only descriptor). Then **close the write descriptor**; an error from
   `close()` is a failure of the call, because some providers commit the data only on close.
5. **Read-back with full comparison.** Open a new `"r"` descriptor, check that `fstat` size == length, read the whole
   content and compare it byte for byte with the output bytes, and check that the stream ends right after `length`
   bytes. Any difference (other size, other bytes, bytes after the end marker) throws `OutputVerificationFailed`. A
   provider that serves a stale or cached copy, or stored something else, is therefore never reported as success.
6. **Failure inside the window.** If anything fails after bytes were written and while the write descriptor is
   still open, the library first tries `ftruncate(0)` on it and then closes it, so that the target is left empty
   rather than holding masked bytes followed by the rest of the original; the original error is reported either way
   (an error of the clean-up does not replace it).
7. **Residual window.** It starts when the target is opened with `"wt"` (a provider may truncate at open) and ends
   when step 5's read-back succeeds. An I/O error, a provider failure or a process kill inside the window can leave
   the target empty, damaged, or (if the provider did not truncate and the clean-up in step 6 was not possible)
   holding the start of the masked output followed by original bytes, which include original pixel data. The
   original cannot be restored. The KDoc of `overwriteNonAtomic`, of `maskFile` and the integration guide state:
   **"After any error from a call with a content-URI target, treat the target as unmasked and do not share it."**

**Read-back rule (one rule, no fallback).** A content-URI write counts as successful only after the full comparison in
step 5. A target the library cannot read is refused in step 2, before anything is written. The size check on the
write descriptor alone is never accepted as proof. Apps get read and write together from the usual sources
(`ACTION_CREATE_DOCUMENT`, `ACTION_OPEN_DOCUMENT`, MediaStore items the app owns or a write request was granted for;
ASSUMPTION per provider, MEASURED in the provider tests).

`maskFile(context, source = uri, target = AndroidOutputTargets.newFile(otherUri))` uses the same probe and verified
write. Concurrent writers to the same document from another app are outside the library's control (documented
limit).

### 5. Colour and ICC profile

- Decoding converts to sRGB on both platforms (as in the benchmark app). Wide-gamut photos therefore change colour
  slightly; documented.
- The source ICC profile is never copied, and in 0.1.0 the output carries **no ICC profile at all**: the rebuild
  step (decision 1 step 7) drops every APP2, and the validator (decision 6) rejects any APP2. The pixels are sRGB by
  construction (decoding converts to sRGB), and a JPEG without a profile is read as sRGB by convention, so the
  colours are unchanged for sRGB-aware viewers. A runtime check cannot tell an encoder's sRGB profile from a profile
  copied from the source by some platform path, and a device or vendor profile can carry text and private tags;
  writing none closes that path.
- Alternative kept for later: pin the profiles that the platform encoders are MEASURED to emit, by length and
  SHA-256, and accept only those. This needs a new project decision.
- Which segments each platform encoder actually emits (APPn list, ICC or not) is still MEASURED on both platforms
  and recorded; it informs that later decision but does not change the 0.1.0 output, because the rebuild step drops
  every encoder APPn.

### 6. Runtime output validator and the output-file test

A small JPEG segment parser in `commonMain` checks every output before it is written: one SOI at offset 0; exactly
one APP0, directly after SOI and byte-identical to the library's fixed 18-byte JFIF segment (length field 16) (decision 1 step 7; any
other APP0, including one with a thumbnail, is rejected); exactly one APP1 (EXIF, contents as in decision 3) of at
most 1,024 bytes including its marker and length, directly after the APP0; DQT/DHT/SOF/SOS/DRI and restart markers;
no other APPn (no APP2/ICC, XMP, MPF, IPTC/APP13) and no COM; a single EOI as the last two bytes. A failure throws
`OutputValidationFailed` whose `findings` list the codes of ADR 0002 decision 9.

The APP1 limit is computed from decision 3's closed tag list with every field at its maximum length:
- marker, length, `Exif\0\0` and the TIFF header: 18 bytes;
- IFD0 with 5 entries (Orientation, Make, Model and the two sub-IFD pointers): 66 bytes, plus 130 bytes for Make
  and Model (64 characters and a NUL each);
- the EXIF sub-IFD with 5 entries: 66 bytes, plus 64 bytes of values;
- the GPS IFD with 12 entries: 150 bytes, plus 107 bytes of values;
- word-alignment padding.
That is about 620 bytes (computed; the writer's test measures the exact all-fields-maximum size and must stay at or
below 1,024). An APP1 above the limit fails validation. The same
parser, plus an independent checker maintained by security, runs on every output file produced in the tests
(privacy rule 7).

### 7. Input and output formats

- **Output: JPEG only.** `OutputFormat.Jpeg(quality = 90)` (quality 1-100) is the only output format. The type
  stays sealed so that a later format can be added without breaking, but only by a new decision; every output format
  must pass the same output-file checks (decision 6).
- **Input: JPEG, HEIC and PNG**, identified from the first bytes of the source:
  - JPEG: `FF D8 FF`.
  - HEIC: an ISO-BMFF `ftyp` box whose major or compatible brands include `heic`, `heix`, `heim`, `heis` or `mif1`
    with an HEVC-coded primary image. Only the primary image is decoded (iOS `CGImageSourceGetPrimaryImageIndex`;
    Android `ImageDecoder` decodes the primary image, ASSUMPTION to be TESTED). Auxiliary images in the source (depth,
    mattes, gain map, thumbnails, burst or sequence frames) are never decoded into the output and never written.
    Android decodes HEIC through the platform decoder; on a device whose platform decoder cannot decode it the call
    throws `UnsupportedImage` (ASSUMPTION: the test phones and the emulator image decode HEIC; TESTED there).
  - PNG: signature `89 50 4E 47 0D 0A 1A 0A`. Included because both platform decoders already read PNG, so the cost is
    the alpha flattening in decision 1, fixtures and tests. Only the first frame of an animated PNG is decoded.
  - Anything else (for example WebP, AVIF, GIF, DNG) throws `UnsupportedImage` before decoding.
- Orientation is applied for every input format (EXIF orientation for JPEG; the HEIC rotation and mirror properties,
  which both platform decoders apply with the settings in decision 1, ASSUMPTION to be TESTED with HEIC fixtures in
  orientations other than 1).
- The output rules (decisions 3, 5 and 6) do not depend on the input format: the output is always a JPEG rebuilt
  from pixels, with the same metadata policy.

## Alternatives considered

**A. Write no EXIF segment by default (orientation absent).** Pro: smallest output; readers treat a missing tag as
"normal" orientation in practice. Con: does not match "written as 1"; a later tool that merges metadata from the
source could re-introduce a rotation tag without any visible conflict. Rejected.

**B. Copy the source EXIF and delete the unwanted tags.** Pro: keeps everything the app might want. Con: unknown
and vendor tags, offsets into MakerNotes and thumbnails make "delete what is unwanted" unverifiable. Rejected:
allowlist and re-serialisation only.

**C. Use platform writers for metadata (ExifInterface `saveAttributes`, ImageIO properties).** Pro: no own writer.
Con: two implementations with different output, `saveAttributes` rewrites the file (a second write and a temporary
file), ImageIO may add its own tags. Rejected; platform readers are used only for reading.

**D. Content URI overwrite through a new document plus delete of the old one.** Pro: the new bytes are complete
before the old document disappears. Con: not atomic either, needs user consent for deletion on Android 11+, changes
the document identity. Rejected.

**E. Decode at full resolution, then scale in a second step.** Pro: one resampler for every case. Con: a 48 MP
camera photo needs about 190 MB before scaling; the reduced-size decode bounds memory by the working size. Rejected;
the reduced-size decode is used, and the agreement guarantee is about identical pixels after this step, not about
the scaler.

**F. Open content URIs with `"rwt"` to read back on the same descriptor.** Pro: one descriptor for write and
verification. Con: some providers may grant write but not read-write access, so it would refuse targets that
`"wt"` serves; the project's rule names `"wt"`. Rejected; verification uses a separate `"r"` descriptor.

**G. Read-back of size and the last two bytes only.** Pro: cheap; detects a tail of the original. Con: a provider that
serves a stale copy of the same length that also ends with `FF D9`, or stored other data, would pass, and the app
would share a target that is not the masked output. Rejected; the full comparison costs one read of a few megabytes.

**H. Content-URI overwrite allowed by default (no opt-in).** Pro: one target type fewer. Con: every integrator would
take the residual window of decision 4 without noticing. Rejected by the owner's decision; the opt-in is explicit.

**I. One size setting for detection and output, or detection below the floor when the pixel limit is low.**
Pro: one setting, one buffer and less work for small outputs; never fails for a low limit. Con: shrinks faces below the measured
detection size, so small outputs would protect fewer faces without anyone noticing. Rejected by the owner's
decision: detection floor 3,500 px, scale after masking, and a clear error instead of a smaller detection.

**J. One fixed source-file size limit (reject every large camera photo).** Pro: simplest rule. Con: phones produce
photos of up to about 200 MP that a reduced-size decode handles with output-size buffers. Rejected; the limit applies
to the decoded image and a fixed 200 MP ceiling bounds the source.

**K. Replace through a symbolic link: resolve it once and replace the file it points to.** Pro: `OverwriteSource`
would also work for linked photos. Con: the resolved file can have other hard links, can lie in another directory
or on another volume (no atomic rename there), and a link that changes between resolution and rename brings the
original problem back; every case needs its own rule and test. Rejected for 0.1.0: such targets are refused, and
the app can pass the resolved path itself.

**L. Keep the existence check followed by a rename as the fallback where hard links are not supported.** Pro: the
target appears complete in one step. Con: a file created at the name between the check and the rename is replaced,
which breaks "never replace" for `replaceExisting = false`. Rejected; the exclusive create of decision 2 never
replaces, at the price of an incomplete masked file at the name while it is written.

## Consequences

Positive
- One metadata writer and one validator for both platforms; the output rules are checked at runtime and in tests.
- File-path targets are atomic; content-URI targets are compared in full after writing, and a content-URI overwrite
  happens only when the app asks for it by name.
- Large camera photos are processed at the working size without a full-size buffer when a maximum long side is set.
- Small outputs never mean weaker detection: detection always runs on at least 3,500 px (or the source size).

Negative / risks
- Content-URI overwrite is not atomic; after an error the target may be empty, damaged or hold original bytes
  (documented; the opt-in makes the integrator accept it knowingly).
- iOS replace paths depend on the file provider (TESTED before the iOS file-mode story is accepted).
- The accuracy comparison with the benchmark app must run with `maxLongSide = 0` (the benchmark detected on full
  resolution); the default of 3,500 px is measured separately.
- Content-URI targets without read access are refused (no unverified write), which may exclude some write-only
  providers.
- The internal memory use of a reduced-size decode of a very large source is platform behaviour, not under the
  library's control; it is measured, and the iOS headroom check is sized from that measurement.
- HEIC decoding on Android depends on the device's platform decoder.
- Replacing a file that is a symbolic link or has more than one hard link is refused (`UnsupportedTarget`), also
  when the app meant the link; the app passes the resolved path or writes a new file.
- On file systems without hard links, a new file appears at the target name while it is written (masked output
  only) instead of in one step.
- Small outputs cost the detection time of a 3,500 px working image plus one scale-down step; a low
  `maxDecodedPixels` combined with a large `maxLongSide` makes calls fail rather than detect on fewer pixels.

Follow-ups
- iOS replace paths per location, TESTED by the ios-developer before the iOS file-mode story is accepted (decision 2).
- MEASURED encoder segment lists on both platforms (decision 5).
- MEASURED peak memory of the reduced-size decode for a large JPEG and HEIC source on both test phones (decision 1).

## Verification

- Output-file test on every test output (Android host, Android emulator/device, iOS simulator/device): segment list
  exactly as in decision 6; EXIF IFD0 = {Orientation = 1} for `StripAll`; for `KeepAllowlist`, only the tags of the
  table; canary strings planted in every metadata field of the fixture (EXIF text fields, MakerNote, GPS when not
  allowed, XMP, IPTC, COM, thumbnail) never occur in the output; no bytes after EOI.
- Orientation: fixtures with EXIF orientations 1-8 come out upright (marker pixel position) with `Orientation = 1`.
- Atomicity: fault injection (write failure, cancellation during encoding, cancellation after encoding and before
  rename, encoder failure) leaves the target byte-identical to before and no temporary file behind; in the
  cancellation cases the call throws `Cancelled` and the target is never partly written.
- Encoder output: the raw segment list of each platform encoder is recorded (MEASURED) on a fixture; a test injects
  an extra APPn and a COM segment into the encoder output (test hook) and checks the rebuild step removes them.
- Pixel limit (working size): with `maxLongSide = 0`, a header whose size is above `maxDecodedPixels` throws
  `ImageTooLarge` before any pixel buffer is allocated (decoder spy / allocation counter); the same header with
  `maxLongSide = 3500` is decoded at the reduced size and succeeds; a header above the 200 MP source ceiling throws
  `ImageTooLarge` in both cases; the default of `maxDecodedPixels` is 25,000,000 (defaults test). Both cases of the
  floor rule: a 5,000 × 3,750 source with `maxLongSide = 5000` and `maxDecodedPixels = 13_000_000` (working image
  18.75 MP) throws `ImageTooLarge` and is not detected at a smaller size (test hook: no decode), also when
  `maxOutputLongSide = 1000` is set; with `maxLongSide = 3500` it succeeds with a working image of 3,500 × 2,625.
- Detection floor and scale after masking:
  - `maxLongSide` 3,499 and `maxOutputLongSide` 63 are rejected (`InvalidConfiguration`); `maxOutputLongSide` 64 is
    accepted.
  - A 5,000 px lossless fixture with `maxOutputLongSide = 1000` is decoded at 3,500 px, detected and masked there
    (test hook records the detection input size), then scaled; the output long side is 1,000 px.
  - Differential pair with one fixed plan (ADR 0002 decision 10, test (e)): `detectFile(A)` gives the plan. Then
    `maskFile(A, plan)` and `maskFile(B, plan)` run, where B differs from A only inside the plan's opaque cores. The
    two output files are byte-identical, and the independent pixel oracle in `verification/` finds the decoded
    outputs identical everywhere. B's pixels differ from A's, so the plan from `detectFile(A)` is refused for B with
    `SourceChanged` (ADR 0015 decision 6; the test asserts that refusal and that nothing was written). The run on B
    therefore uses the same masks bound to B's pixels through the public API,
    `PlanMasker().planFile(B).withMasks(plan.masks)`, on the same `FaceMasker`.
  - A 2,000 px source with `maxOutputLongSide = 1000` is detected at 2,000 px.
  - `FileResult` reports `width`/`height` (the working size), source size and output size.
- Large source: a generated 200 MP JPEG and a large HEIC (public-safe synthetic content, generated at test time) are
  processed with `maxLongSide = 3500` on both test phones; output long side 3,500 px; peak memory MEASURED and
  recorded; no out-of-memory.
- Input formats: JPEG, HEIC and PNG fixtures (orientation 1 and at least one rotated orientation each) come out
  upright with the same output-file checks; a PNG with transparent areas is flattened (the transparent area is
  white in the output, and detection ran on the flattened pixels, test hook); a multi-image HEIC yields only the
  primary image; a WebP and a GIF throw `UnsupportedImage`; a JPEG renamed `.png` is still decoded as JPEG.
- Content-URI opt-in: `AndroidOutputTargets.newFile(uri)` on a non-empty target and `OutputTarget.OverwriteSource`
  with a `content:` source throw `UnsupportedTarget` with the target bytes unchanged and no `"wt"` requested;
  `overwriteNonAtomic(uri)` on the same target succeeds.
- Content URIs: a test provider that ignores truncation (keeps old bytes) makes the call fail with
  `OutputVerificationFailed`; a provider whose `"r"` returns the old content with the same size (stale read) makes
  the call fail with `OutputVerificationFailed`; a provider that commits on close and fails the commit makes the call
  fail; a pipe-backed provider makes an overwrite fail with `UnsupportedTarget` before writing; a provider that
  refuses `"wt"` makes the call fail with `UnsupportedTarget` and is never opened with `"w"` (recorded modes); a
  normal provider overwrite of a larger original leaves exactly the output bytes; a provider that truncates at
  `"wt"` and then fails the write leaves an empty target and the call throws (the documented window); a provider
  that keeps the old bytes and fails part-way through the write makes the call throw and, through the clean-up of
  decision 4 step 6, leaves a target of size 0.
- Temporary files, both target modes: a file-system watch over the target directory and the app's cache, files and
  no-backup directories during the call sees only the library's `.faceblur-*.tmp` file, created after encoding and
  renamed to the target (or deleted on failure), plus the target itself; no other name appears. Negative control:
  an implementation hook that copies the input to a temporary file makes the test fail.
- Allowlist: `KeepAllowlist()` with no flag set throws `InvalidConfiguration`; with only `dateTime`, no GPS tag
  appears.
- EXIF size: the writer's output with every allowlisted field at maximum length is measured and is ≤ 1,024 bytes; a
  planted APP1 of 1,025 bytes fails the validator.
- New files: a target created by a test hook between encoding and publishing is not replaced when
  `replaceExisting = false` (`UnsupportedTarget`, the planted file unchanged); temporary files are created with mode
  `0600` where the file system supports POSIX modes (checked with `stat`). The same with a symbolic link (also a
  dangling one) planted at the target name. No-replace publication never renames (writer test seam records the
  calls: `link` only). With a test seam whose `link` reports "not supported", the target is created exclusively: a
  file planted at the name before the call is unchanged and the call fails with `UnsupportedTarget`; a write
  failure after the exclusive create leaves no file at the name.
- Links and other names (path writer, Android host tests and the iOS stories): `OverwriteSource` on a source path
  that is a symbolic link to an image, and `NewFile(path, replaceExisting = true)` on a target that is a symbolic
  link, throw `UnsupportedTarget`; the link and the file it points to are byte-identical to before. The same for a
  source and for a target with a second hard link: both names keep the old bytes. A source reached through a
  symbolic link in a parent directory is replaced normally. A source replaced by another file between decoding and
  publication (test hook) makes `OverwriteSource` throw `UnsupportedTarget`; the file now at the path and the moved
  original are unchanged. Every refusal happens in the pre-check before decoding where the state already exists
  then (decoder spy: no decode), and again in the write for a state created after the pre-check. No error message
  contains the path or the temporary file's name.
- Content-URI probe: a target without read access, and a pipe-backed target, are refused before any write
  descriptor is opened (provider log of requested modes shows no `"wt"`), and the target bytes are unchanged.
- Content-URI overwrite on a test phone (device test, generated public-safe images): `overwriteNonAtomic` of an
  original that is larger than the output, for a MediaStore item the app owns and for a document of the system's
  external-storage documents provider, leaves exactly the output's length and bytes (full read-back, decision 4
  step 5); the recorded modes show `"r"` and `"wt"` only, never `"w"`. The result is recorded per provider; a
  provider that keeps bytes after the output fails the call (`OutputVerificationFailed`), never passes.
- ICC: an input with a wide-gamut ICC profile produces an output without any APP2 segment; encoder output with an
  injected APP2 `ICC_PROFILE` (test hook) comes out without it; the validator rejects a planted APP2 `ICC_PROFILE`
  (including a well-formed RGB profile).
- JFIF APP0: every output's APP0 is byte-identical to the fixed 18-byte segment (length field 16); encoder output
  with an injected JFIF APP0 carrying an 8 × 8 thumbnail with a canary (test hook) comes out with the fixed APP0 and
  without the canary; the validator rejects that APP0, an APP0 with another identifier and a second APP0 with
  `FORBIDDEN_SEGMENT`, and a missing APP0 or an APP1 placed before the APP0 with `SEGMENT_ORDER`.
- Rebuild: encoder output that cannot be parsed (for example truncated) throws `OutputValidationFailed` with the
  parser's code (`TRUNCATED`) and nothing is written.
- Per-side limit: with `maxLongSide = 0`, a header of 65,535 × 1 passes the size checks, and 65,536 × 1, 1 × 65,536
  and 70,000 × 100 throw `ImageTooLarge` before any decode (test hook: no decode), also when the orientation tag
  swaps the sides; with `maxLongSide = 100000` the 70,000 × 100 header also throws `ImageTooLarge` before any
  decode; with the default `maxLongSide` it is reduced to a 3,500 × 5 working size.

## Update (0.1.0)

Short notes; the decisions above are unchanged.

- **Build checks enforce the write rule.** The library's only write sites are the masked output and the temporary file
  of that output next to the target (decisions 2 and 4), plus the removal of the call's own temporary or fallback
  file. Two things now check this at build time. Every reviewed file-I/O line of the SAST allow-list must have a row in
  the write-site register (`scripts/sast/write-sites.txt`) that classifies it (masked output, temporary file, clean-up,
  or a read kind), and `scripts/check-write-sites.py` fails when an allow-list line has no row or a row has no line,
  when a line that can write is classified as a reading kind, when a writing kind appears outside the file-mode writer
  files, when a read-open row does not name an existing test that proves its read-only flags, when a file mutation is
  made outside `PathWriteRules.kt`, or when library code names an app storage or temporary directory (cache, files,
  no-backup, code-cache, data, external, database or system temporary directory). The temporary file is created next
  to the target through the single create call of `PathWriteRules`, never in one of those directories.
- **Plain opens are read-only at run time.** `OsDescriptors.open` applies `PathWriteRules.requireReadOnly` before the
  platform call: a mode other than `O_RDONLY`, or any of `O_CREAT`, `O_TRUNC`, `O_APPEND`, is refused with an
  `IllegalArgumentException` that carries no path. Files are created only by the create call with its fixed exclusive,
  owner-only flags. `OsDescriptorsTest` covers both the refusal and the flags of today's three callers.
- **HEIC input has a device test.** `HeicInputDeviceTest` runs the HEIC route (platform decoder, masks, encoder, writer)
  on a device with a synthetic fixture that contains no face. The fixture is not committed: the build task
  `generateHeicFixture` generates it at test time (macOS) into the build directory and verifies its SHA-256, and the
  test fails, never skips, when the fixture is missing. It checks the output segment list, that the canary metadata of
  the input does not occur in the output, the baked-in orientation, a flat mask area against an unmasked control, and
  that no other file is left behind.
