<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threading, cancellation and memory

Related guides: [FaceBlur on Android](android.md), [Providing your model](providing-your-model.md),
[Score threshold](score-threshold.md).

## Run masking off the main thread, and offer cancellation

Every `FaceMasker` method blocks: `detect`, `checkProfile`, `applyMasks` and `maskInPlace` run the detector and write
masks, and `DetectorModel.load` reads, hashes and checks the model, and `FaceMasker(model)` opens a runtime session and runs
one check pass. None of them is rejected on the
main thread, and all of them can take long enough to freeze a screen. Always call them from a background thread, and
give the person a way to cancel.

- **One call at a time per instance.** A second call that enters while one is running throws
  `FaceMaskException.ConcurrentUse`; it does not wait. For parallel work create one `FaceMasker` per worker from the
  same `DetectorModel`.
- **Create once, reuse, close.** Creating a masker opens a runtime session and runs one pass on a grey input: do it
  off the main thread, keep the instance for several photos and call `close()` when done. Calls after `close()` throw
  `Closed`.
- **Two detector passes at a time.** The first detection of a photo that needs at least two tiles opens a second
  runtime session, created and checked like the first, and from then on the tiles alternate between the two sessions,
  so two passes run at the same time. The faces are merged in tile order, so the result is the same as with one
  session. Each session gets half of `onnxThreads`: 2 threads each when it is 0 (the default), otherwise the first
  session gets half rounded up and the second the rest, so `onnxThreads = 1` keeps one session. If the runtime cannot
  create the second session, or reports it differently from `modelInfo`, detection runs on one session with the same
  result. `close()` releases both.
- **Cancellation is cooperative.** Pass a `CancellationToken` to the call. FaceBlur reads it before detection,
  between detector passes, in long loops over many detections and immediately before writing masks. A pass already
  inside the runtime is not interrupted, and `DetectorModel.load` cannot be cancelled.
- **Every run has a time limit.** A call never waits for a model run for ever. A pass that is still going at its limit
  (the larger of 2 seconds and 20 times the duration of the check pass at creation) ends the call with
  `ModelOutputInvalid` (finding `RUN_TIMEOUT`), and creating a `FaceMasker` that takes longer than 60 seconds fails with
  `ModelRejected` (finding `TIMEOUT`). Nothing is written, and treat the photo as not masked. The next call on the same
  `FaceMasker` opens a new runtime session first.
- **Two sessions, one call.** Every run of both sessions has the limit above, and a run that times out or fails in
  either session fails the whole call; the pass still running in the other session is given up and nothing of it is
  read. What is used again afterwards:
  - after a timeout in either session, neither session: the next call that detects opens new ones;
  - after any other failure of a detection that had given tiles to the second session, neither session either;
  - after any other failure before that (in the pass over the whole image, or while the masker runs one session),
    both sessions are kept, as with one session before;
  - after `Cancelled`, the passes already running end, their results are dropped and both sessions are kept;
  - when creating the second session takes longer than its limit the call fails with `RUN_TIMEOUT`, and when its
    check pass fails, with `RUN_FAILED`; the first session is kept and the next detection that needs two tiles tries
    the second again.
- **Runs that cannot be stopped.** A run that the runtime cannot stop (on iOS none can) keeps a thread and its
  session's memory until it returns, so the number of such runs is bounded for the whole process (the runs of both
  sessions count): when the bound is reached, every `FaceMasker` in the process fails at once with the same errors
  until one of them returns.
- **What the image looks like after `Cancelled`.** If cancellation was seen before the finished masks were copied into
  the image, the image is untouched. If it was requested during that final copy, the copy completes first (the image
  is fully masked) and `Cancelled` is thrown afterwards. An image is never half-masked by a cancellation. Your app
  cannot tell the two cases apart, so treat `Cancelled` as "not masked": do not publish, upload or share the image.

The Kotlin sample uses `java.util.concurrent`, so it is for Android and the JVM; in common code use another atomic
flag, for example `kotlin.concurrent.atomics.AtomicBoolean`.

```kotlin
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.MutablePixelImage
import java.util.concurrent.atomic.AtomicBoolean

/** One cancellable masking job. Start [run] on a background thread, never on the main thread. */
class MaskJob(private val masker: FaceMasker, private val image: MutablePixelImage) {
    private val stop = AtomicBoolean(false)
    private val token = object : CancellationToken {
        override val isCancellationRequested: Boolean get() = stop.get()
    }

    /** Call from the Cancel button; safe from any thread. */
    fun cancel() = stop.set(true)

    /** True only when the image is masked and may be encoded; false for cancellation or lack of memory. */
    fun run(): Boolean = try {
        masker.maskInPlace(image, token)
        true
    } catch (e: FaceMaskException.Cancelled) {
        false // treat as not masked
    } catch (e: FaceMaskException.NotEnoughMemory) {
        false // image untouched and not masked; retry later, with less work in parallel or a smaller image
    }
}
```

Other `FaceMaskException` subtypes still propagate; handle them as errors and never publish the original image as if it
were masked.

The same in Java:

```java
import io.github.shishark.faceblur.CancellationToken;
import io.github.shishark.faceblur.FaceMaskException;
import io.github.shishark.faceblur.FaceMasker;
import io.github.shishark.faceblur.MutablePixelImage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MaskJob {
    private final FaceMasker masker;
    private final MutablePixelImage image;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final CancellationToken token = stop::get;

    public MaskJob(FaceMasker masker, MutablePixelImage image) {
        this.masker = masker;
        this.image = image;
    }

    /** Call from the Cancel button; safe from any thread. */
    public void cancel() {
        stop.set(true);
    }

    /** Run on a background thread. True only when the image is masked and may be encoded. */
    public boolean run() {
        try {
            masker.maskInPlace(image, token);
            return true;
        } catch (FaceMaskException.Cancelled e) {
            return false; // treat as not masked
        } catch (FaceMaskException.NotEnoughMemory e) {
            return false; // image untouched and not masked
        }
    }
}
```

## Memory of the in-place call

`maskInPlace` and `applyMasks` write into the image you pass. Before they change any pixel they reserve a **working
area**, sized for this call's masks and released when the call returns. It grows with the number and size of the
masks. Its documented worst case (masks over almost the whole image) is up to 4.3 bytes per image pixel plus 32 MiB, so
it can reach about 1.4 times the image itself.

- The fixed part is not reserved on every call: it is sized from the plan (at most 128 bytes per mask, the fill
  scratch of the largest mask, and the bits of the partial tiles at the image edges).
- The detector's own buffers come on top (two model inputs at a time), and so does the memory of the two runtime
  sessions of each instance: the second session holds about as much as the first. While instances are created, the
  model itself needs several times its file size.
- The working area is needed **per call, not per instance**. N calls running in parallel need up to N times the
  working area of one call.
- Each call checks the memory that is free now (on Android and the JVM the heap that remains, on iOS the memory the
  system reports as available) together with the reservations of the other running calls, so parallel calls never
  count the same free memory twice.
- If the working area does not fit, the call throws `NotEnoughMemory`, and the image is untouched. **Do not publish
  the original instead**: it was not masked. Retry later, with less work in parallel, or with a smaller image.
- If a call ends with an error that is not a `FaceMaskException` (for example an `OutOfMemoryError` on Android),
  discard the image. After `MaskWriteFailed` (your image failed while the masks were copied in) discard it too.

The same rules hold for `applyMasks`, which writes a mask plan you made or edited yourself.

### How many photos to mask in parallel on a phone

On phones, run at most 2 masking calls in parallel; this is a recommendation, not a hard limit. Photos that are large
and have many masks need the most memory, and a call that does not fit throws `NotEnoughMemory` (see above); the
refused call writes no pixel. Slower phones take longer per photo, so run masking off the main thread and offer
cancellation. The masks' summed working area is limited to 32 times the image area, a generous technical limit;
exceeding it fails safely and leaves the image untouched.

### Stream form (Android)

The stream form reads the whole source into memory, at most 64 MiB. Near that cap, run one stream call at a time, and
use the file or Uri form for large inputs: the platform decoder reads those from the file or the provider.
