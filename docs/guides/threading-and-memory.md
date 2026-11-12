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
- **Cancellation is cooperative.** Pass a `CancellationToken` to the call. FaceBlur reads it before detection,
  between detector passes, in long loops over many detections and immediately before writing masks. A pass already
  inside the runtime is not interrupted, and `DetectorModel.load` cannot be cancelled.
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
- The detector's own buffers come on top (one model input at a time). While instances are created, the model itself
  needs several times its file size.
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
