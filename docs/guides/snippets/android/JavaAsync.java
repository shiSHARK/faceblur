// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android;

import android.content.Context;
import android.net.Uri;
import io.github.shishark.faceblur.AndroidOutputTargets;
import io.github.shishark.faceblur.CancellationToken;
import io.github.shishark.faceblur.FaceMasker;
import io.github.shishark.faceblur.FaceMaskerAndroid;
import io.github.shishark.faceblur.FileOptions;
import io.github.shishark.faceblur.FileResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JavaAsync {
    private JavaAsync() {}

    /**
     * Masks the image at {@code source} into the empty document {@code target} on {@code executor}.
     * CompletableFuture.cancel() does not stop a running task, so cancelling the future sets the flag that the
     * FaceBlur CancellationToken reads. After a cancel, treat the target as not produced.
     */
    public static CompletableFuture<FileResult> maskAsync(
            FaceMasker masker, Context context, Uri source, Uri target, Executor executor) {
        AtomicBoolean stop = new AtomicBoolean(false);
        CancellationToken token = stop::get;
        CompletableFuture<FileResult> future = CompletableFuture.supplyAsync(
                () -> FaceMaskerAndroid.maskFile(
                        masker, context, source, AndroidOutputTargets.newFile(target),
                        new FileOptions(), null, token),
                executor);
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                stop.set(true);
            }
        });
        return future;
    }
}
