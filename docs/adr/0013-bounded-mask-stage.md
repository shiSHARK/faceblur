# 0013. Bounded mask stage: part-mask cap, mask limits, linear writing and an untouched image on failure

Status: Accepted (2026-11-02, after code review), with the project owner's addition on the working area
(decision 6)
Date: 2026-11-02 (revised the same day after code review: the cover rectangle is defined on the drawn geometry and
snapped to whole pixels, the part test gets the same small-box rule, stricter tests for linear cost and
cancellation, exact copy region, error mapping and memory statements completed; on acceptance: the working area is
allocated completely before the first write, a failed allocation gives `NotEnoughMemory` with the image untouched,
fills are computed tile by tile so that the worst case stays at about one copy of the image, and the related ADR
and architecture texts are updated; later the same day after review: exact-size run buffers, the reservation
touched up front, a figure of 0 on an iOS device refused, a process-wide count of reserved bytes, the catch limited
to `OutOfMemoryError`, and tighter tests). Amended 2026-11-02 by the project owner (decision 6): Android and the
JVM check the remaining heap before they allocate, with the same process-wide count of reserved bytes as iOS; the
caught `OutOfMemoryError` is only a last-resort backstop. The KDoc states that the fixed part is sized per call and
that the working area is needed per call, so parallel calls add up. Amended 2026-11-03: the limits are final and
stated as generous technical limits with a technical rationale; `TOO_MANY_MASKS` gets an actionable message; the
shadow is reserved for the exact union of the touched tiles; the coverage map covers the tiles of the opaque
rectangles; the premultiplied exception to bit-identity is stated exactly. Amended 2026-11-03 (project owner): mask area factor
lowered from 64 to 32.

## Context

The mask stage writes the masks of a plan into the caller's image (`applyMasks`, `maskInPlace`, later `maskFile`).
ADR 0002 decisions 8 and 9 and ADR 0012 decisions 3 and 6 bound the steps before it: rows, detections, passes, merge
candidates and merge work. This ADR bounds the stage itself and states its failure behaviour. Design rationale:

- **Part masks per face.** Part masks (ADR 0012 decision 3) keep an earlier state of a merged face's box whenever
  the state's mask cannot be shown to lie inside the final mask. The part test cannot show this when the two boxes
  share edges, because the ellipses touch there. So the number of part masks of one face grows with the number of
  times its box grew, and that number is bounded only by the merge-candidate bound. Each part mask is drawn at full
  size, but together they add only a thin sliver to the face's ellipse.
- **Pairwise steps.** Overlapping masks were processed in groups, found by comparing every pair of masks. Each
  mask's neighbours were found by scanning all masks. Both costs grow with the square of the mask count.
- **Group memory.** Every fill of a group was kept until the whole group was composited, so the memory was the sum
  of the group's write areas.
- **Writing and failure.** Masks were written group by group, and cancellation was read only before and after
  writing. An error after the first group could therefore leave a partly written image. The KDoc already says to
  discard the image after such an error.
- **Model pre-parse.** The table of graph-input names had no count limit, so its time and memory grew with the
  number of declared inputs. Loading cannot be cancelled.

Constraints that stay as they are:

- Masks are opaque. A setting may only add coverage (project principle: no option may weaken protection).
- **Union property** (ADR 0012 decision 3): every mask drawn at a score threshold lies, pixel for pixel, inside the
  union of the masks drawn at any lower threshold.
- Fail closed: a typed error, never a silent truncation.
- The output of the fills stays bit-identical wherever this ADR does not say otherwise.
- **Drawn geometry.** The stage draws every mask with both radii raised to at least 1 px
  (`PreparedMask.MIN_RADIUS`, around the same centre). Every containment statement below is about these drawn
  masks, evaluated with the stage's float pixel test.

## Decision

### 1. At most 32 part masks per face; beyond that the face gets one rectangle

- The merge counts, for every kept box, how often it **grew**: how often a lower-scored candidate changed its edges.
- A face that grew at most **32 times** (`MAX_PART_MASK_GROWTH`) keeps its own mask plus every part mask that the
  part test does not drop.
- A face that grew more often is drawn as **one RECTANGLE mask, the cover**, with no part masks. This applies
  whatever shape the masking config asks for.
- **Cover edges.** For a final box with edges L, T, R, B (W = R − L, H = B − T), expansions ex, ey and the fixed
  shift s = 0.08, each edge is computed in double precision from the box's float values:

  | Edge | Value |
  |---|---|
  | left | ⌊min(L − W·k_x − 0.05, L − 1.05)⌋ |
  | right | ⌈max(R + W·k_x + 0.05, R + 1.05)⌉ |
  | top | ⌊min(T + H·(½ − s − ey/2 − 10⁻⁴·ey/2) − 0.05, T − 1.05)⌋ |
  | bottom | ⌈max(B + H·(ey/2 − ½ − s + 10⁻⁴·ey/2) + 0.05, B + 1.05)⌉ |

  Here k_x = (ex − 1)/2 + 10⁻⁴·ex/2. The cover is the RECTANGLE mask with centre ((left + right)/2,
  (top + bottom)/2) and radii ((right − left)/2, (bottom − top)/2).
  - The first term of each edge is the expansion edge of the face's own mask plus the margin of the part test
    (0.05 px plus 10⁻⁴ relative).
  - The second term reaches 1.05 px beyond the box. It covers masks whose radius the stage raised to 1 px.
  - Snapping outwards to whole pixels puts the boundary 0.5 px from every pixel centre, so the stage's float pixel
    test selects exactly the pixels of the integer rectangle.
- **Part test, small boxes.** The part test of ADR 0012 decision 3 also answers "not covered" (the part is drawn)
  when any radius of the part or the face at the smallest allowed expansion is below 1.05 px. A raised radius can
  otherwise put a drawn part pixel outside the drawn face mask.
- `Detection.faces` is unchanged (one box per face). The `Detection` remembers internally which faces get a cover,
  so `toMaskPlan()`, `toMaskPlan(masking)`, `maskInPlace` and `maskFile` all draw the same plan.

Why the cover holds every drawn mask of the face:

- A box only grows, and candidates are clipped to the image before the merge, so every earlier state lies inside
  the final box.
- **Unraised radius.** Per axis, a mask spans its box widened by (expand − 1)/2 of the box side on each side, with
  the centre moved up by s times the height. This span is monotone in the box when expandX ≥ 1 and
  expandY ≥ 1 + 2·|s|. The public floor is 1.6 / 1.8 (1.35 / 1.55 in the design stage; the argument holds for both), and s is fixed at 0.08 publicly. A configurable shift would
  have to keep this condition. So the span of every earlier state is inside the first term.
- **Raised radius.** The radius becomes 1 px around the state's own centre. That centre lies inside the final box
  horizontally, and between T and B vertically (the shifted centre of a sub-box of height h lies at least
  (½ − s)·h below its top). So the drawn span lies within 1 px of the box: inside the second term.
- An ellipse lies inside its own bounding rectangle, so both shapes are covered.
- **Evidence (MEASURED on synthetic geometry, float32 replica of the drawing path including the raise):** 0 pixels
  outside the cover in about 140,000 random box/sub-box cases (boxes from 0.02 px, coordinates up to 65,000 px, shared
  or inward edges, both shapes, independent x and y expansions) and about 6,100 growth-chain cases (33-60 steps,
  clamped at image edges, both shapes). The pixel set equalled the integer rectangle in every case. The earlier check of 2.2 × 10⁹ pixels
  did not model the raise, and a cover without the second term fails with tiny boxes.

Why the union property still holds:

- The merge at a lower threshold repeats the merge at the higher threshold step by step before it adds
  lower-scored candidates. So a face's growth count at the lower threshold is at least its count at the higher one,
  and its final box contains the higher threshold's final box.
- **Below the cap at both thresholds:** unchanged. A part is drawn bit for bit again with the same geometry, or the
  part test (with the small-box rule) shows it inside a drawn mask.
- **Below the cap at the higher threshold, above it at the lower one:** every drawn mask of the face at the higher
  threshold belongs to a state that is now an earlier state, so it lies inside the cover.
- **Above the cap at both:** every cover edge is built only from operations that are monotone in the box edges
  (subtraction, multiplication by a positive or negative constant in the order written, min, max, floor, ceil), and
  IEEE rounding is monotone. So the computed edges, and the pixel sets, are nested exactly.
  - MEASURED: 0 pixels in about 75,000 cases.
  - A cover defined only by radii without snapping lost a few hundred pixels in total over the same cases, at
    large coordinates, through float rounding at shared edges.
- The count is of growth steps, not of part masks kept. The number of part masks kept can fall at a lower threshold,
  because a larger final mask covers more earlier states. A rule based on that number would not be monotone.

Cost (MEASURED on synthetic geometry unless stated):

- The cover is 4/π = 1.273 times the area of the face's ellipse (exact, for any expansion), plus up to 1 px per side
  from snapping. It is drawn only for faces above the cap.
- Against the ellipse plus part masks it replaces:
  - 1.248 for growth chains of 50-3,200 steps at the default expansion;
  - 1.247 for a single cut half;
  - median 1.265 (range 1.242-1.273) over 181 random tile-border chains at the default expansion, and 1.269-1.273
    over 184 chains at expansion 3.0;
  - 1.102 against the union of the masks of every possible sub-box, which is the most any set of part masks can
    cover.
- The masks per face are at most 33. A face drawn as a cover needs no part tests at all.
- With a RECTANGLE masking config, the extra area is only the margin and the snapping.

### 2. Per-image mask limits, checked before the mask stage reads or writes any pixel

There are two limits, and both fail closed:

| Limit | Value | Checked |
|---|---|---|
| Masks per image, `MAX_MASKS_PER_IMAGE` | **65,536, final** | Detection: after the merge, on its arrays, before any result object is created (faces plus part masks after decision 1). A caller's plan: in `MaskPlan`'s constructor. |
| Mask work per image, `MAX_MASK_AREA_FACTOR` | **32, final** (lowered from 64 by the project owner after the phone measurements of the stage): the sum over all masks of the area of each mask's working (read) rectangle, clipped to the image, must not exceed 32 × max(image pixels, 1,048,576), computed in 64-bit arithmetic | Step 1 of the mask stage (decision 3), with the settings of the call (the working rectangle depends on style, feather and expansion), before the stage reads any pixel. |

- Every working rectangle is clipped to the image, so a plan with at most 32 masks never exceeds the work limit. The
  work limit bounds the stage's run time, which grows with this sum. The mask count bounds per-mask memory and
  per-mask fixed costs.
- Every mask comes from one merge candidate: a kept candidate is a face, and a consumed candidate that grew a box is
  a part. So the mask count of a detection is never above its candidate count.
- **Errors**, by where the masks come from:

  | Call | Error |
  |---|---|
  | `detect`, `maskInPlace`, `maskFile` with detection (masks from the model) | `ModelOutputInvalid` with the new finding code `TOO_MANY_MASKS`, for either limit. The 0.1.0 code list gains it; as the code rules allow, a later release may add codes. The message names the mask count and the profile's score threshold and says that the threshold is very likely too low for this model; check it with faceblur-eval (ADR 0012 decision 7). |
  | A caller's plan: `MaskPlan` construction (count), `applyMasks` (work), `maskFile` with a plan (work) | `InvalidConfiguration("masks")` |
  | The core's second check of the count (unreachable through the public API, which checks first) | `Internal`, like the core's other second-layer refusals (ADR 0002 decision 9) |

  - So `maskInPlace` and `detect` followed by `applyMasks(toMaskPlan())` can report the work limit with different
    types. The `maskInPlace` KDoc ("error rules as there") gets this exception. In-memory and file mode report it
    the same way for the same source of masks.
  - In each case the image is not written. Detection has read it; the mask stage has not.
- **Values.** Both limits and the per-face cap of decision 1 are final. Each is a generous technical limit, and
  exceeding it fails safely (ADR 0012 decision 6). Their values come from the memory and time each stands for:
  - masks per image: at most 128 bytes of per-mask arrays (8 MiB at the limit, decision 6) plus about 150-300 bytes
    of objects per mask (ESTIMATE), and a fixed fill cost per mask. A photo has far fewer faces; a model reaches the
    limit only at a score threshold far below its useful range;
  - mask work: one pass over the summed working area, so the stage's run time is bounded at 32 passes over the
    image (over 1 MP for small images);
  - per-face cap: at most 33 masks per face.

  The rule of ADR 0012 decision 6 applies: the mask stage at both limits is measured on both reference phones; if a
  phone cannot complete it, or it takes minutes, the limit is lowered and the project owner decides.

  This rule was applied: after the phone runs at the work limit 64, the project owner lowered
  `MAX_MASK_AREA_FACTOR` to 32. It is a generous technical limit; exceeding it fails safely. The guides give the
  worst-case rule (memory and mask limit) but state no masking times; slower phones take longer. Run masking off the main
  thread and offer cancellation.

### 3. Linear mask stage: no groups, a coverage map, and writing into a shadow

The stage is rebuilt in three steps. Its output is bit-identical to the old engine's for the same plan on images
with straight (non-premultiplied) alpha; two exceptions are noted under Consequences.

1. **Check and reserve** (no image access).
   - Validate every mask.
   - Compute its write, read and opaque rectangles into primitive arrays.
   - Apply the two limits of decision 2.
   - Compute the size of the whole working area and allocate all of it (decision 6): the shadow tiles with their
     write marks, the coverage map, the fill scratch for the largest mask, and the run buffers (one array for each
     run length from 1 to 64 pixels, twice: for reading and staging, and for the read-back of step 3). Then touch
     every page of the reservation once, so that its memory is committed before any pixel of the image is
     written.
2. **Compute** (reads the caller's image, never writes it; allocates no array; cancellable).
   - Build a **coverage map**: one bit per pixel, for the tiles that some opaque rectangle touches. A bit is set
     when the pixel's centre lies inside the drawn opaque mask of **any** mask, with the same float expression as
     today. Fills test this bit instead of scanning the other masks. A lookup on a tile without coverage bits reads
     "not covered", which is exact, because a bit can only be set inside an opaque rectangle. "Never sample a pixel
     inside any mask" stays exact, and the cost is the summed area of the opaque rectangles.
   - For each mask in plan order:
     - read the original pixels it needs from the caller's image, row by row along its read rectangle (only the
       rows the fill samples), in runs of at most 64 pixels, each into the reserved run buffer of exactly that
       length. Nothing has been written yet, so these are the original pixels, as in the old two-phase groups, and
       the fill visits them in the old order;
     - compute its fill in the reserved fill scratch (decision 6 says how the fills work tile by tile);
     - composite it, tile by tile, into a **shadow** of 64 × 64-pixel tiles. Each tile is loaded from the caller's
       image on its first touch, directly into the tile's own array, which has exactly the tile's size. Each tile
       also keeps a mark of which of its pixels lie inside some mask's write rectangle.
   - **Cancellation** is read between masks, at least once every 65,536 units of work inside a mask, and once more
     at the end of the step, right before step 3. One unit is one loop step of any phase:
     - coverage-map write or lookup;
     - pixel of a block read or tile load;
     - ring or known-pixel sample;
     - colour-table entry;
     - cell of a SMOOTH_FILL pull or push level;
     - grid point, upsampled pixel or composited pixel.
3. **Copy** (the only step that writes).
   - For each touched tile in row order, write exactly the marked pixels (the same set the old engine wrote: the
     union of the write rectangles), as row runs of 1 to 64 pixels. Each run is staged in the reserved run buffer of
     exactly its length, written, read back into the second reserved buffer of that length, and compared as in
     ADR 0002 decision 9.
   - Every array passed to `readPixels` or `writePixels` therefore has exactly `w × h` elements. The public pixel
     contract is unchanged.
   - This step only copies finished pixels: it creates no object except the exception it may throw, and runs no fill
     code. FaceBlur's own platform adapters allocate nothing in `readPixels` and `writePixels`. A copy unit (one
     pixel) counts as work too.

- **Groups are not needed.** Every fill reads original pixels, because nothing is written before step 3. Compositing
  in plan order gives the result the old groups gave, because masks of different groups never write the same
  pixels (each write rectangle lies inside its read rectangle, and groups were the overlap components of read
  rectangles).
- **Cost:** O(masks + summed working area + shadow area). There is no step over pairs of masks and no per-pixel scan
  over other masks.
- **Memory (peak):** the working area of decision 6, all of it allocated in step 1:
  - the shadow: at most the touched tiles, 4 bytes per pixel plus 1 bit for the write mark, never more than the
    image;
  - the coverage map: at most 1 bit per image pixel;
  - the fill scratch of the largest single mask, which no longer grows with the mask's area beyond a fixed cap
    (decision 6);
  - a few primitive values per mask.

  The peak does not grow with how often masks overlap. In the worst case it is about one copy of the image plus a
  fixed amount (decision 6).
- **Part-test work** (decision 1 and ADR 0012 decision 3) now counts towards the merge work bound: one unit per
  evaluation, up to the existing 4,096 per part.

### 4. A failed call leaves the image untouched, except a failure of the image during the final copy; a cancelled call leaves it untouched or fully masked

- Every check, every allocation of the stage and all fill computation happen in steps 1-2, before the first write.
  Any failure there leaves the caller's image untouched:
  - a limit or an invalid mask;
  - cancellation;
  - an internal fault;
  - an exception from the caller's `readPixels`. It surfaces as `Internal` with the exception as its cause, as
    today; the image has not been written.
  - the working area cannot be reserved: `NotEnoughMemory` (decision 6), on every platform;
  - **on the JVM and Android:** an `OutOfMemoryError` anywhere else in steps 1-2 (outside the reservation, only
    small objects are allocated there). Fatal JVM errors pass unchanged (ADR 0002 decision 9); the image is
    untouched.
- Step 3 can fail only through the caller's image, or through a fatal error raised there. The image failure is an
  exception from `writePixels` or `readPixels`, or a read-back mismatch; it is `MaskWriteFailed`, as today. Only then
  can the image be partly written. It must be discarded, as the KDoc says.
- **Cancellation** (ADR 0002 decision 8):
  - a request seen during steps 1-2 gives `Cancelled` with the image untouched;
  - a request that arrives during the copy is reported after the copy completes, with the image fully masked;
  - the image is never half-masked by a cancellation.
- **Contract text for the KDoc:** "If this call throws a `FaceMaskException`, the image is untouched, with two
  exceptions. `MaskWriteFailed`: your image failed while the finished masks were copied into it; discard it.
  `Cancelled` requested during that final copy: it is reported after the copy, and the image is fully masked. If the
  call ends with any other error (for example an `OutOfMemoryError` on Android), discard the image."
- **File mode:** unchanged. On any failure, no output is written and the temporary output is removed.

### 5. Model loading: a graph-input limit instead of cancellation

- Pre-parse refuses a main graph that declares more than **65,536 inputs** (`NOT_ONNX`). This joins the existing
  description limits (1,024 outputs, 1,024 opset domains, 64 dims). Older IR versions list initializers as graph
  inputs, but real models stay far below this limit.
- The limit caps the sorted name table and its memory. The table sort then needs at most n·⌈log₂ n⌉ name
  comparisons, and each initializer lookup at most ⌈log₂(n + 1)⌉. Every comparison is bounded by the shorter name, so
  the pre-parse time grows about linearly with the file size.
- `DetectorModel.load` gets no cancellation parameter (no public API change). Its KDoc states that it reads, hashes
  and checks the whole file in one blocking call whose time grows with the file size, and that it is meant to be
  called once per model, off the main thread.

### 6. The working area is allocated completely before the first write; if it cannot be, the call fails with the image untouched

The project owner accepted that the stage may need about one extra copy of the image in the worst case, on three
conditions: the whole working area is allocated before the first pixel of the caller's image is written; a failed
allocation (an out-of-memory error or a size limit) gives an error and leaves the image untouched, never
half-masked; and the KDoc of the in-place calls states the worst-case extra memory.

**Fills work tile by tile.** So that the worst case stays at about one copy, no fill holds a buffer of its mask's
area any more:

- **Reading.** A fill reads the original pixels from the caller's image row by row along its read rectangle, in runs
  of at most 64 pixels through the reserved run buffers (decision 3 step 2). The caller's image holds the original
  pixels until step 3, so no block of them is kept.
- **Compositing.** The fill's value is computed for each pixel of a shadow tile while that tile is composited. There
  is no fill buffer of the write rectangle.
- **GRADIENT_OVAL.** The ring is sampled in two passes over the same rows in the same order, the first for the
  per-bin histograms and the second for the weighted means, instead of a stored list of samples. The colour field
  is evaluated per shadow tile on the grid points that the tile needs, with a border of one grid point, in a buffer
  of at most 66 × 66 values. Every value is the same function of the same inputs as before, so the output is
  unchanged.
- **SMOOTH_FILL.** The grid pyramid is the solve's state and stays. Its base level is capped at
  **524,288 cells** (`MAX_SMOOTH_FILL_CELLS`, provisional): when the read rectangle would need more cells at the
  usual grid spacing, the spacing is raised to the smallest value that fits. Only masks several times longer than
  they are wide reach the cap, for example elongated caller masks or RECTANGLE covers (ESTIMATE; a test records the
  largest cell count on the public fixtures, which must stay below the cap). Above the cap the fill is computed on a
  coarser grid; the mask stays opaque under the opacity invariant (ADR 0002 decision 10). The upsampling is done per
  shadow tile.
- **SOLID** needs no scratch.

**The working area** is every buffer the stage uses from step 1 to the end of step 3:

| Part | Size |
|---|---|
| Shadow tiles (every tile that a write rectangle touches, clipped to the image; the exact union, each tile once) | 4 bytes per pixel |
| Write marks (per shadow tile) and coverage map (per tile that an opaque rectangle touches; the exact union, each tile once) | 512 bytes per tile each, 1 bit per pixel of a 64 × 64 tile |
| Tile tables for the shadow and the coverage map | one entry per image tile each |
| Per-mask primitive arrays (rectangles, geometry, order) | at most 128 bytes per mask |
| Fill scratch for the largest mask of the plan, reused by every mask | GRADIENT_OVAL: under 128 KiB; SMOOTH_FILL: at most 37 bytes per base cell plus 16 bytes per pyramid level (four float values, the upper pyramid levels with their rounding up, the push's index and flag), so at most 18.5 MiB at the cap |
| Run buffers: one array of each length from 1 to 64 pixels, twice | 16,640 bytes plus the array headers |

- **Exact union of tiles.** The shadow and the coverage map are sized by the number of distinct tiles touched, not
  by a sum over masks: overlapping masks, and a face with its part masks, reserve each tile once. Step 1 does this
  in two parts. First it reserves the two tile tables (one entry per image tile each: 6,204 entries at
  6,000 × 4,166 px, at most 524,288 at the in-memory pixel limit) through the same memory check, count of reserved
  bytes and allocator as the rest. Then it marks in the shadow table every tile that some write rectangle touches
  and in the coverage table every tile that some opaque rectangle touches, counts the marked tiles, and reserves the
  rest of the working area for exactly those tiles. Marking costs the number of tiles each rectangle touches
  (counted as work), and the image is not accessed. If the second part does not fit, the tables are released and
  the call throws `NotEnoughMemory`, with the image untouched.
- **Exact size first.** Step 1 computes the size of the working area in bytes in 64-bit arithmetic, from the plan,
  the image size and the call's settings. Every product and sum is overflow-checked (on the JVM `Math.multiplyExact`
  and `Math.addExact`; in common code an equivalent checked helper). Within the library's limits no overflow is
  possible (at most 2³¹ − 1 pixels and 65,536 masks); if one occurs anyway, the call throws `NotEnoughMemory`
  without allocating.
- **Then allocate and touch.** Step 1 allocates all of it and then writes one element in every 4,096 bytes of every
  reserved array (and one element of every smaller array). The touch writes 0, each array's initial value, so it
  changes no state: the write marks, the coverage map and the SMOOTH_FILL sums all rely on starting at 0. This commits
  the memory of the whole reservation before any pixel of the image is written. On the JVM and Android the virtual
  machine has already zeroed the arrays, so the touch costs little; on iOS it moves the footprint growth in front of
  the first write (see below).
- **Nothing after the reservation.** Step 2 allocates no array; it creates only small objects of a fixed size, and an
  error there leaves the image untouched. Step 3 creates no object except the exception it may throw. FaceBlur's own
  platform adapters allocate nothing in `readPixels` and `writePixels`.
- **Worst case.** For an image of N pixels the working area is at most **4.3 bytes × N + 32 MiB**:
  - 4 bytes per pixel for the shadow, 0.25 for the marks and the coverage map, and under 0.02 for the tile tables
    and the per-tile array headers;
  - at most 8 MiB of per-mask arrays (65,536 masks), 18.5 MiB of fill scratch, 2 MiB for the bits of partial tiles
    at the image edges, and the run buffers.

  At the default pixel limit of 25 MP this is **about 141 MB**, against 100 MB for the image's own pixels. That is
  about 1.07 copies of the image plus the fixed part. A photo with a few faces needs a few megabytes, also when
  its faces have part masks, because each tile is reserved once. The figure
  covers FaceBlur's own buffers; a caller's `MutablePixelImage` that allocates inside `readPixels` or `writePixels`
  adds its own. The project owner confirmed this worst case.
- **Sized per call, needed per call.**
  - The fixed part is not a block reserved on every call. Each of its items is a cap: the per-mask arrays grow with
    the number of masks of the plan (128 bytes each), the fill scratch with the largest mask, and the edge bits with
    the partial tiles that a write rectangle touches. Step 1 sizes each item for the call's plan.
  - The working area belongs to a call, not to a `FaceMasker` instance. It is held from step 1 to the end of step 3
    and released when the call returns. Instances hold no working area between calls. N calls running in parallel
    (on N instances) need up to N times the figure of their plans at the same time.
- **KDoc text** for `maskInPlace`, `applyMasks` and their platform overloads, and in the Swift facade, in substance
  (the KDoc of `FaceMasker` is the binding wording; the sentences on parallel calls and on time are word for word):
  "Extra memory:
  before it changes any pixel, this call reserves its working area, sized for this call's masks and released when
  the call returns. In the worst case (masks over almost the whole image) it is up to
  4.3 bytes per image pixel plus 32 MiB, about 141 MB for a 25-megapixel image, whose own pixels take 100 MB. The
  working area is needed per call, not per instance: N calls running in parallel need up to N times this. At most 2
  masking calls in parallel on phones is recommended (see the documentation). If the working area does not fit in
  the memory that is available now, the call throws `NotEnoughMemory` and the image is untouched. If the call ends
  with an error that is not a `FaceMaskException` (for example an `OutOfMemoryError` on Android), discard the
  image." The detector's own buffers are stated separately, as today.

  A separate KDoc paragraph follows:

  "Masking time depends on the image and the phone; slower phones take longer. Run masking off the main thread and
  offer cancellation."

  - No typical-photo figure is published in 0.1.0: the KDoc and the guides state the worst case only (a test
    asserts that the KDoc contains no typical figure). The recommended number of parallel calls (at most 2) was set
    by the project owner (KDoc text above). The release checklist checks that no placeholder text is left in the
    public documentation.

**Error: `NotEnoughMemory`, on every platform.**

- The type exists in the 0.1.0 error set (ADR 0002 decision 9; Swift `notEnoughMemory`, ADR 0011) but is not yet in
  the code. Its meaning fits: the call needs more memory than is available now, and nothing was changed. The change
  that implements this ADR adds it to the code and to the ABI dump. No new type is needed.
- Not `ImageTooLarge`: the image is within every size limit, and the same call can succeed when more memory is free.
- Not `Internal`: it is not a fault of the library.
- **KDoc of `NotEnoughMemory`:** "The call needed more memory than was available, and the image was not masked; it
  is untouched. Do not publish, upload or share the image or the original instead, as if it were masked. Retry
  later, with less work running in parallel, or with a smaller image."
- In file mode the same reservation runs on the working image; on failure nothing is written and the temporary
  output is removed, as for every other error.

**All platforms: a process-wide count of reserved bytes.** Several `FaceMasker` instances may run in parallel
(ADR 0002 decision 8), and two checks could read the same free memory. So every platform keeps one process-wide
atomic count of the bytes of reservations that have been checked but are not yet visible in the platform's memory
figure:

- a check subtracts the count from the platform's figure (below), and adds its own bytes to the count with a
  compare-and-set, so two checks never both use the same free memory;
- a call removes its bytes from the count once its reservation is allocated and touched (from then on the figure
  shows them as used), or when it fails before. For a short time the same bytes can be counted twice, which can only
  refuse a call, never let one through;
- so the working area of every other running call is subtracted, either through the count or through the figure;
- a documented rule "one in-place call at a time per process" was rejected: the library cannot enforce it, and an
  app that breaks it would fail open (an out-of-memory error on any thread, or the process ends) instead of getting
  `NotEnoughMemory`.

**JVM and Android: check the remaining heap first; the caught error is only a backstop.**

- **The check is the contract.** Before step 1 allocates, the call computes the remaining heap from
  `java.lang.Runtime`:

  remaining = `maxMemory()` − (`totalMemory()` − `freeMemory()`) − the count of bytes reserved by other calls

  If the working area does not fit in the remaining heap, the call throws `NotEnoughMemory` without allocating,
  with the image untouched.
  - `maxMemory()` is "the maximum amount of memory that the Java virtual machine will attempt to use", and
    `Long.MAX_VALUE` if there is no inherent limit; `totalMemory()` − `freeMemory()` is the heap in use now (Android
    and Java SE `Runtime` reference). The subtraction is done in checked 64-bit arithmetic; a negative result counts
    as 0.
  - A working area larger than `maxMemory()` can never fit: the call throws at once, with no garbage collection.
  - **One collection before refusing.** The heap in use counts garbage that a collection would free, for example
    the working area of the previous photo. If the working area does not fit, the call requests one garbage
    collection (`Runtime.gc()`, which the reference describes as the virtual machine's "best effort to recycle all
    discarded objects") and computes the remaining heap again. If it still does not fit, the call throws
    `NotEnoughMemory`. A runtime that ignores explicit collections makes the call refuse, the safe direction.
  - There is no margin on top of the working area for now. Whether the phone measurements call for one (for the
    rest of the app's allocations while the call runs) is a follow-up; a margin can only refuse more calls.
  - Android on the reference phones keeps `Bitmap` pixels outside the Java heap, so the caller's bitmap does not
    count against `maxMemory()`; the working area does (ASSUMPTION, checked by the phone test below).
- **Why not the catch.** On Android an `OutOfMemoryError` is not limited to the thread whose request failed: when
  the heap is exhausted, any thread of the app (the main thread, another library) can be the one whose allocation
  fails, and an app usually crashes on it. A library must not drive the heap there. The check keeps the stage's
  reservation inside the heap that is free when it starts.
- **Allocation, with the catch as a last-resort backstop.** One internal function allocates every buffer of the
  working area, touches it, and does nothing else:
  - it catches only `OutOfMemoryError` (by type in the JVM and Android source sets; in common code by the class-name
    match of ADR 0002 decision 9, restricted to that one name). Every other throwable, including the rest of
    `VirtualMachineError`, is rethrown unchanged;
  - on that error it clears its references to the buffers allocated so far and throws `NotEnoughMemory` with the
    error as its cause;
  - it calls no caller code: no `readPixels` or `writePixels`, no cancellation token, no listener.
  - The catch is reached only when the check was passed and the heap still ran out, for example because other
    threads of the app allocated between the check and the allocation, or because no contiguous block of the size
    was free. It keeps the image untouched in that case; it is not relied on, and the tests show that the check,
    not the catch, refuses calls that do not fit.
- This is the only place where FaceBlur catches an `OutOfMemoryError`, a narrow exception to the rule of ADR 0002
  decision 9. It is safe there: the failed request is one array allocation, nothing has been changed, and no other
  code runs inside the function. Everywhere else the rule stays: the error passes unchanged. The code review checks
  these points.
- **Residual.** The figures are a snapshot. Memory that other threads of the app allocate between the check and the
  end of the allocation can still exhaust the heap, and the resulting `OutOfMemoryError` can then hit another
  thread. The KDoc's advice on parallel calls and the recommended number for large photos limit this.

**iOS (Kotlin/Native).**

- On iOS a failed allocation cannot be relied on to give a catchable error: the system usually ends an app whose
  memory footprint crosses its limit before any allocation fails. So iOS needs a check before the allocation, not a
  catch after it.
- **Check.** Before step 1 allocates, the working area plus a margin is compared with the memory that the system
  reports as still available to the app: `os_proc_available_memory()`, "the current dirty memory limit minus the
  dirty memory footprint" (SDK header `os/proc.h`).
  - The margin is 64 MiB, **provisional**, taken from the benchmark app's decode check; the phone test below
    validates it.
  - If the reservation does not fit, the library runs one garbage collection and reads the figure again. If it still
    does not fit, the call throws `NotEnoughMemory` with the image untouched.
  - Whether Kotlin/Native's platform libraries expose `os_proc_available_memory` is not yet verified (ASSUMPTION).
    If they do not, the library reads `limit_bytes_remaining` from the task's VM information, as the benchmark app
    does from Kotlin/Native today. The SDK header calls the two equivalent.
- **A figure of 0.** The header says 0 means that the process is not an app or that it already exceeds its memory
  limit. The decision depends on the build target, not on the value:
  - device target (`iosArm64`): 0 gives `NotEnoughMemory`, with the image untouched (fail closed);
  - simulator target (`iosSimulatorArm64`): 0 skips the check and the call goes ahead, because a simulator has no
    app memory limit and may report 0 (ASSUMPTION, checked on both simulator runtimes). The KDoc says so.
- **Concurrent calls:** the process-wide count of reserved bytes above. The check subtracts it from the available
  figure; a call's bytes leave the count after the touch, when they show in the footprint.
- **Residual.**
  - The figure is a snapshot. Memory that other code of the app allocates between the check and the end of the touch
    can still cross the limit, and the system then ends the process. After the touch, the stage's own footprint does
    not grow.
  - When the process ends, a process-private image ends with it. A buffer shared with another process (for example
    a `CVPixelBuffer` backed by an IOSurface that another process uses, or a buffer over a shared file mapping) can
    keep a partly written image if the process ends during the copy. The DocC of the in-place calls advises
    process-private buffers.
- An `OutOfMemoryError` that the runtime does raise inside the reservation (exact name `kotlin.OutOfMemoryError`)
  is mapped to `NotEnoughMemory`, as on the JVM. This is the one exception on Kotlin/Native to the wrapping rule of
  ADR 0002 decision 9 (every other throwable becomes `Internal`); either way the image is untouched.

**Test seams** (internal, not public API):

- an allocator through which the stage makes every allocation of the working area. The production allocator calls
  the array constructors; a test allocator records each request or throws `OutOfMemoryError` at a chosen request;
- a memory probe for the check (JVM and Android: the heap maximum, the heap in use and a garbage-collection request;
  iOS: the available figure and a garbage-collection request). A test probe reports chosen values and records each
  collection request.

## Alternatives considered

- **Drop a part mask that the union of the other masks covers.** It keeps the masks exact. But states that share
  edges with each other each own points of their boundary, so the analytic test drops almost nothing. A pixel-level
  version costs the summed area of the parts and still has no bound. Rejected as the bound, though it could be added
  later to save area.
- **A covering ellipse instead of the rectangle.** One concentric ellipse that holds every possible earlier state
  needs up to about 1.55 times the face's ellipse area at the default expansion of the design stage (1.35 / 1.55; not recomputed for 1.6 / 1.8) (for example, for a full-height strip
  at the side of the box), and an ellipse fitted to the actual parts is smaller. Neither is monotone between
  thresholds: the ellipse of a box does not always contain the ellipse of a box inside it, which is the problem part
  masks solve in the first place. Rejected.
- **A cover defined by radii only (no snapping).** Covers the raised parts once the 1.05 px term is added, but at
  shared edges and large coordinates float rounding can put a pixel of the higher threshold's cover outside the
  lower threshold's cover. Rejected for the snapped edges.
- **Cap the part masks kept, by count.** Not monotone, because a larger final mask covers more earlier states (see
  decision 1). Rejected.
- **Only a per-image mask limit, no per-face cap.** A face that grew many times would then fail the call instead of
  being covered. With the cap, such a face gets its cover and the call succeeds. Both are kept.
- **Sub-quadratic grouping (sort and sweep) with bounded group memory.** It removes the quadratic step, but each
  group must still hold the original pixels it needs, and writing would still start before all fills are computed,
  so a later failure could still leave a partly written image. The shadow solves both. Rejected.
- **Effects computed in place, one mask after another, from the current pixels.** Less memory, but the fills of
  overlapping masks would read each other's soft edges, so the output depends on plan order and changes for
  overlapping faces. Rejected.
- **Restoring the original pixels after a failure.** Restoring means writing again into an image that has just
  failed, and the result is the unmasked image, which helps nobody. Rejected.
- **Writing whole shadow tiles.** Simpler, but it would pass pixels outside every write rectangle through the
  caller's write path. With premultiplied storage that relies on an exact round trip of untouched translucent
  pixels. Rejected for the marked runs.
- **Allocating the working area as the stage needs it** (tiles on first touch, fill buffers per mask). Less memory
  for most photos, but an allocation can then fail after other work and the stage cannot know in advance whether it
  will fit. The owner's rule asks for a complete allocation first. Rejected.
- **Letting the `OutOfMemoryError` pass, as everywhere else.** The image would be untouched, but an app that catches
  `FaceMaskException` would not see a typed error, and many apps do not catch `Error`s at all. Rejected for the one
  allocation function; kept everywhere else.
- **Only the heap maximum as the limit on the JVM and Android, with the caught `OutOfMemoryError` as the real
  check** (the first accepted version of this ADR). It never refuses a call that a collection would make possible,
  but it lets the reservation run the heap out, and on Android the resulting `OutOfMemoryError` can hit any thread
  of the app, not only the call's. Rejected by the project owner; the catch stays only as a backstop.
- **A remaining-heap check without a collection.** It would refuse calls whenever garbage, such as the previous
  photo's working area, is still counted as used. Rejected for one collection before refusing.
- **Allowing arrays longer than `w × h` in `readPixels` and `writePixels`** (one staging buffer for every run). Fewer
  buffers and fewer calls, but it changes the public pixel contract, and an existing implementation that relies on
  the exact length would misread the data. Rejected for one reserved array per run length, which keeps the contract.
- **Keeping fill buffers of a mask's whole area.** Simpler, but for one mask over the whole image they are several
  copies of the image (a block of original pixels, the fill and the SMOOTH_FILL grids), which breaks the owner's
  bound of about one copy. Rejected for the tile-by-tile fills.
- **A cancellation token for `DetectorModel.load`.** It would mean a public API change for a case that needs a file
  the app pinned itself; the input limit removes the slow case. Rejected.

## Consequences

- **Positive:**
  - The stage's time and memory are bounded by the plan, not by how masks overlap.
  - A face whose box grew many times is covered by one rectangle instead of many masks.
  - A failed in-memory call leaves an untouched image. The exceptions are `MaskWriteFailed` (the caller's image
    failed during the final copy) and a cancellation requested during that copy (image fully masked).
  - Cancellation now works during all fill computation.
  - Running short of memory in the mask stage is a typed error (`NotEnoughMemory`) with the image untouched, not a
    crash between two writes.
- **Negative:**
  - At very low thresholds or with unusual model output, some faces are masked by a rectangle (about 27 % more area
    than their ellipse), and demo editors show a rectangle for them.
  - Two more limits; very low thresholds can now also fail with `TOO_MANY_MASKS`.
  - The stage's peak memory changes from about one group's fills to the working area of decision 6: for a photo with
    few faces a few megabytes; for a photo masked almost everywhere up to 4.3 bytes per pixel plus 32 MiB (about
    141 MB at 25 MP). It is reserved in full before the first write, so a call can fail with `NotEnoughMemory` where
    a stage that allocated as it went might have finished.
  - The fills read the caller's image in runs of at most 64 pixels, and GRADIENT_OVAL samples its ring twice. This
    costs many more calls into the caller's image and more work per mask than one block read (to be measured on the
    phones).
  - On iOS the memory check depends on the figure the system reports at one moment (decision 6, residual).
  - On the JVM and Android the check can refuse a call that would have fitted after more collections, and a refused
    call that does not fit at first costs one explicit garbage collection. Parallel calls on large photos are
    refused when their working areas together do not fit; the app has to retry them or run fewer in parallel.
- **Exceptions to bit-identity:**
  - SMOOTH_FILL masks whose base grid would exceed `MAX_SMOOTH_FILL_CELLS` are filled on a coarser grid
    (decision 6). Masks of face proportions stay below the cap and are unchanged.
  - An image adapter that stores premultiplied pixels (ADR 0004): the old engine wrote each group's result
    through that storage and read it back rounded whenever a later mask's write rectangle covered a soft-edge value
    again. The shadow composites the exact values. Results can differ where a pixel whose original alpha is below
    255 lies in the feather band of one mask and inside the write rectangle of a later mask. The size of the
    difference is not bounded by this ADR; the test locates where differences occur. The read-back comparison is
    unchanged.
- **Follow-ups:**
  - the final value of `MAX_SMOOTH_FILL_CELLS` (the two limits and the per-face cap are final);
  - the slowest cases that are still within the limits, on both phones: done at the work limit 64, after which the
    project owner lowered it to 32; the worst case at 32 is run on both phones and its time goes into the
    documentation;
  - the phone measurements of the stage, including the worst case of decision 6;
  - on iOS: whether Kotlin/Native exposes `os_proc_available_memory` (otherwise the task's VM information), whether
    the simulator reports 0, and the final value of the 64 MiB margin;
  - the recommended number of parallel calls (2, set; no typical-photo figure is published in 0.1.0);
  - whether the JVM and Android check needs a margin on top of the working area (decision 6), from the phone
    measurements;
  - the free-memory headroom check of file mode (ADR 0005) must cover the larger of the decode peak and the
    mask-stage peak (the working image plus the working area of decision 6), by formula or by the phone
    measurement.

## Changes to other ADRs (applied on acceptance)

- ADR 0002, decision 7: `maskInPlace` equals `detect` + `applyMasks`, except for the error type of the mask limits
  (decision 2).
- ADR 0002, decision 8: cancellation is read during mask computation; the wording on writing applies to the copy
  step.
- ADR 0002, decision 9:
  - the untouched-image rule and contract text of decision 4;
  - `InvalidConfiguration("masks")` for plans above the limits;
  - `TOO_MANY_MASKS` in the `ModelOutputInvalid` 0.1.0 set;
  - `NotEnoughMemory` from the working-area reservation (on the JVM and Android from the remaining-heap check), and
    the one place where an `OutOfMemoryError` is caught, as a backstop (decision 6).
- ADR 0003, decision 5, and ADR 0004, decision 4, with their memory tests: the memory a call adds includes the
  working area of decision 6 (about one copy of the image in the worst case) instead of "no full-size allocation".
- ADR 0012, decision 3:
  - the growth cap and the cover (decision 1);
  - the part test's small-box rule;
  - containment stated on the drawn geometry.
- ADR 0012, decision 4 item 8: the graph-input limit.
- ADR 0012, decision 6:
  - the two limits and the cap in the limits table;
  - the validation rule extended to them.
- ADR 0012, decision 7: the finding-code list.
- ARCHITECTURE.md: the mask-stage data flow, memory, errors and cancellation.

## Implementation notes (from the reviews)

- The process-wide count of reserved bytes is an `AtomicLong` or an equivalent primitive atomic, not an
  `AtomicReference<Long>` (which boxes on every update). It is one count shared by the JVM, Android and iOS code
  paths (common code). The call removes its bytes exactly once: after the touch, or in a `finally` on every path that
  ends before it, and the count's holder is initialised before the first check.
- On the JVM and Android the check reads `maxMemory()`, `totalMemory()` and `freeMemory()` through the memory probe
  only; no other code of the stage reads them.
- The OOM catch compares the fully qualified class name exactly: `java.lang.OutOfMemoryError` on the JVM and
  Android, `kotlin.OutOfMemoryError` on Kotlin/Native. A simple name or a substring is not enough. A test covers
  each platform's mapping.
- "FaceBlur's own adapters allocate nothing in `readPixels` and `writePixels`" is tested on the JVM path only. For the
  Android `Bitmap` adapter and the iOS buffer adapters it is an item of the implementation-review checklist, with the
  evidence (the code lines) recorded in the review.
- The iPhone footprint test after step 1 (growth at least the reservation) is a release gate for the iOS in-place
  path, not only a follow-up.
- The simulator rule for a figure of 0 stays an ASSUMPTION until it has been checked on the iOS 18.0 and 27.0
  simulator runtimes.

## Verification

All containment and union tests draw through the stage's own geometry path, including the 1 px minimum radius.

- **Per-face cap:**
  - A chain of N growth steps on one cut face: N ≤ 32 gives the face plus its part masks, and N = 33 gives exactly
    one RECTANGLE cover.
  - The plan's mask count is asserted, not a time.
  - A long growth chain on one face (thousands of steps) ends with one mask for that face and completes.
- **Containment at pixel level:**
  - Random boxes and sub-boxes: edges shared or moved inwards, boxes below 6 px on either axis included, coordinates
    up to 65,535 px, both shapes, independent x and y expansions from the floor to 3.0. Also growth chains clamped
    at image edges. No drawn pixel of an earlier state or of the face's own mask lies outside the cover.
  - The cover's pixel set equals its integer rectangle.
  - The cover at a higher threshold lies inside the cover at a lower threshold.
- **Part test:** for boxes whose radius at the smallest expansion is below 1.05 px, the test answers "not covered".
  Above that, no part it drops has a drawn pixel outside the drawn face mask.
- **Union property:** the existing pixel-oracle test gets cases where a face is below the cap at the higher threshold
  and above it at the lower one, and cases with tiny boxes. Negative control: drawing only the face's own ellipse
  above the cap (no cover) fails the pixel check on a cut-half case.
- **Limits:**
  - 65,536 masks pass and 65,537 give `TOO_MANY_MASKS` from detection and `InvalidConfiguration("masks")` from
    `MaskPlan`. The `TOO_MANY_MASKS` message contains the mask count, the profile's score threshold and the
    faceblur-eval advice (ADR 0012 decision 7).
  - The summed working area exactly at the limit passes; one pixel more fails, with the error of decision 2 for each
    call.
  - In each case the mask stage makes no read and no write; a recording image counts the calls.
- **Cover edges at large coordinates:** a unit test of the cover's edge formula with boxes near 65,535 px whose edges
  are shared at quarter-pixel positions, where a cover defined by radii only loses pixels. It asserts exact integer
  nesting of the higher threshold's cover inside the lower threshold's cover.
- **Linear stage.** A test seam counts every unit of work of decision 3, including the copy. Assert
  count ≤ c · (masks + summed working area + shadow area), with c fixed in the test, for:
  - many disjoint small masks (10,000 → 20,000 → 40,000);
  - many heavily overlapping masks over one region (1,000 → 2,000 → 4,000 identical or stacked masks).

  A step over pairs of masks, or a per-pixel scan over other masks, makes the overlapping case fail.
  - c is derived from the per-unit costs of the stage (the largest number of units that one mask, one pixel of a
    working rectangle and one shadow pixel can cost by construction), and the derivation is written next to it in
    the test. It is not fitted to the largest run, so a quadratic step cannot hide inside a generous c.
  - The implementation review checks that every loop of the stage goes through the counter; an uncounted loop over
    pairs would not show in the count.
- **Equality:**
  - With the old two-phase engine (a copy kept in the tests), pixel for pixel, on straight-alpha images: the public
    fixtures and random plans with overlapping masks, for every style and both shapes.
  - A separate test with a premultiplied adapter shows that differences appear only at pixels whose original alpha
    is below 255 and that lie in the feather band of one mask and inside the write rectangle of a later mask, and
    that the read-back comparison passes.
- **Copy region:** a premultiplied adapter with translucent pixels outside every write rectangle, inside a touched
  tile, sees no write to those pixels.
- **Untouched on failure.** A test seam injects a failure at each point of steps 1-2:
  - each limit;
  - an exception from a fill;
  - an `OutOfMemoryError` thrown from a fill on the JVM, which passes unchanged;
  - an exception from the caller's `readPixels`, which gives `Internal`;
  - cancellation.

  In every case the recording image shows zero writes, the pixels equal the input (checksum), and the typed error is
  as stated. In step 3:
  - throwing from `writePixels` at the second run gives `MaskWriteFailed`;
  - cancellation requested from inside the first `writePixels` gives `Cancelled`, with the image equal to the
    uncancelled result.
- **Cancellation:**
  - For each style (SOLID, GRADIENT_OVAL, SMOOTH_FILL), one mask covers a large image, and a counting token flips
    after k reads. The result is `Cancelled` with zero writes, and the work counter shows the flip was read within
    65,536 units.
  - With 40,000 small masks, the flip is read within one mask.
  - The final read before step 3 is asserted.
- **Working area (decision 6):**
  - **Worst case.** A 25 MP image (6,000 × 4,166 px) with masks over almost the whole image, for every style and
    both shapes: one mask over the whole image, and a plan of many masks that together cover at least 95 % of the
    pixels. The recording allocator shows that the reserved bytes equal the size computed in step 1 and stay within
    4.3 bytes × N + 32 MiB, and that every allocation through it happens in step 1. The output equals the old
    engine (straight alpha; SMOOTH_FILL masks above the cell cap are compared with the reference at the raised
    spacing). The old engine alone holds about five image-sized arrays for a whole-image mask, so these tests run in
    their own Gradle `Test` task with a maximum heap of 2 GiB and one fork; the regular test tasks keep their heap
    and check equality on images up to 4 MP.
  - **No allocation after the reservation (JVM).** A test reads the calling thread's allocated bytes
    (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) around step 3 and around step 2. Step 3: no
    allocation except the exception object when one is thrown; if the JIT or the runtime needs a small fixed
    allowance, its value and the reason are written in the test. Step 2: a bound that does not grow with the mask
    area (the same small allowance for 1 MP and 25 MP plans). A source gate in CI refuses any array constructor or
    array copy (`IntArray(`, `FloatArray(`, `ByteArray(`, `LongArray(`, `arrayOf`, `copyOf`, and similar) in the
    stage's files outside the allocator.
  - **Exact union of tiles.** The reserved bytes for one face plus k nested part masks (k = 0, 4, 16, 32) at 25 MP
    equal those for the union of their tiles counted once, which for nested masks is the face's own tiles. Many
    identical stacked masks reserve the tiles of one. Disjoint masks reserve the sum of their tiles. The coverage
    map is reserved only for tiles of opaque rectangles, and a lookup on any other tile reads "not covered". A
    refusal after the tile tables leaves the count at 0 and the image untouched.
  - **Exact array sizes.** An image fake fails the test if any array passed to `readPixels` or `writePixels` does
    not have exactly `w × h` elements, for every style, run lengths 1 to 64 and image edges.
  - **Touch.** Every reserved array is all zero when step 2 starts (checked through the recording allocator, for
    every style). That the touch happened is shown by the iPhone footprint test: after step 1 the footprint has
    grown by at least the reservation. This test is a release gate for the iOS in-place path.
  - **Forced allocation failure.** The test allocator throws `OutOfMemoryError` at the first, a middle and the last
    request of the reservation (and, in a separate loop, at every request of a small plan). Each call gives
    `NotEnoughMemory` with the `OutOfMemoryError` as its cause, the recording image shows no read and no write, and
    the pixels equal the input (checksum). A test allocator that throws another `Error` (for example
    `StackOverflowError`) or a `RuntimeException` sees it rethrown unchanged, not as `NotEnoughMemory`.
  - **Checked size.** A size computation whose product overflows 64 bits (driven through the internal size function
    with values beyond the public limits) gives `NotEnoughMemory` without any allocation.
  - **Check (JVM and Android).** A memory probe reports a heap maximum, a heap in use and a reserved count whose
    remaining heap is one byte below the working area: `NotEnoughMemory` with no cause, no allocation request
    through the recording allocator, no read and no write, and exactly one collection request. Exactly at the
    working area the call proceeds with no collection request. Below before the collection and at or above after
    it: the call proceeds. A working area above the heap maximum: `NotEnoughMemory` with no collection request. A
    heap maximum of `Long.MAX_VALUE` does not overflow the computation, and a heap in use plus a count above the
    heap maximum gives a remaining heap of 0.
  - **Check (iOS).** An available figure one byte below the requirement gives `NotEnoughMemory` with no read and no
    write; exactly at the requirement the call proceeds. The probe also reports below, then above the requirement
    after the garbage collection: the call proceeds.
  - **The check, not the catch, refuses.** In every refused case of the check tests and of the parallel-calls tests
    below, `NotEnoughMemory` has no `OutOfMemoryError` as its cause and the test allocator saw no request.
  - **A figure of 0 on iOS.** The decision takes the build target as a parameter and is unit-tested for both
    targets: device target with a figure of 0 gives `NotEnoughMemory` with no read and no write; simulator target
    with a figure of 0 proceeds. The test run on the iPhone (`iosArm64`) and the simulator run
    (`iosSimulatorArm64`) each assert that the library picked its own target's rule.
  - **Parallel calls (every platform's test run: JVM, Android host tests, iOS simulator).** Calls on separate
    instances, released together by a barrier so that their checks overlap, with a probe whose figure fits a
    chosen number of their working areas:
    - two calls, figure fits one: exactly one proceeds and the other gives `NotEnoughMemory`;
    - eight calls with equal working areas, figure fits three: exactly three proceed and five give
      `NotEnoughMemory`; repeated 100 times with a random start order;
    - every refused call made no allocation request, no read and no write, and its pixels equal the input
      (checksum); every call that proceeded equals the result of the same call run alone;
    - the process-wide count returns to 0 after every call (success, each error, cancellation, a refusal).
  - **Real heap, parallel calls (JVM).** A test in its own process with no test seam. The maximum heap is sized so
    that one working area of a 25 MP whole-image plan (about 141 MB) fits in the remaining heap after the test's
    images are allocated, and two do not; the test first asserts that the remaining heap computed from `Runtime`
    lies in that range, so that a different JVM cannot change the case silently. Two calls on two instances start
    together: one succeeds, the other gives `NotEnoughMemory` with no `OutOfMemoryError` as its cause and an
    unchanged pixel checksum, and an uncaught-exception handler on every thread of the process records no
    `OutOfMemoryError`. The same test with one call and a remaining heap below one working area gives
    `NotEnoughMemory` from the check.
  - **Backstop (JVM).** With a test probe that passes the check and a test allocator that throws
    `OutOfMemoryError` (the forced allocation failure above), the call gives `NotEnoughMemory` with the error as its
    cause and the count returns to 0: the backstop still works when the check is passed.
  - **Fill cap.** A SMOOTH_FILL mask just below and just above `MAX_SMOOTH_FILL_CELLS`: below, the output equals the
    old engine; above, the spacing is the smallest that fits and the mask is opaque under the opacity invariant. The
    largest cell count on the public fixtures is recorded and stays below the cap.
  - **KDoc.** A documentation check finds the worst-case text of decision 6, the per-call and N-parallel-calls
    sentences, the "discard the image after any other error" sentence and the `NotEnoughMemory` text in the KDoc of
    `maskInPlace`, `applyMasks` and their platform overloads and in the Swift facade. The KDoc contains no typical-photo
    figure (the test asserts it).
- **Model loading:**
  - A model with 65,536 main-graph inputs is accepted (when otherwise valid), and one with 65,537 gives `NOT_ONNX`.
  - A comparison counter in the name table stays at or below n·⌈log₂ n⌉ for the sort and ⌈log₂(n + 1)⌉ per lookup.
- **Phones:** time and peak memory of the stage at both limits, and with a 25 MP image masked almost everywhere, on
  both reference phones. The measured peak of the 25 MP case is compared with the figure of decision 6, and the iOS
  check of decision 6 is shown to refuse a call before the system ends the app, which validates the provisional
  64 MiB margin. On the iPhone, the figure read through Kotlin/Native is compared with the task's
  `limit_bytes_remaining`. On both phones, 1, 2, 3 and 4 parallel calls on separate instances with 25 MP images
  masked almost everywhere: every call either succeeds or gives `NotEnoughMemory` with the image untouched; no
  `OutOfMemoryError` on any thread of the app and no termination of the app. On Android the test also records
  whether the bitmaps' pixels count against `Runtime.maxMemory()`. These runs check the recommended number of
  parallel calls.
