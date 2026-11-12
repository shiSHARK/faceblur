// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android

import android.content.Context
import android.net.Uri
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.maskFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Wraps one masker: one call at a time (a second call would throw ConcurrentUse), off the main thread. */
class MaskingService(private val masker: FaceMasker) {
    private val oneAtATime = Mutex()

    /** Cancelling the calling coroutine asks FaceBlur to stop; nothing is written when it stops. */
    suspend fun maskFile(context: Context, source: Uri, target: OutputTarget): FileResult =
        oneAtATime.withLock {
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext().job
                val token = object : CancellationToken {
                    override val isCancellationRequested: Boolean get() = !job.isActive
                }
                try {
                    masker.maskFile(context, source, target, cancellation = token)
                } catch (e: FaceMaskException.Cancelled) {
                    throw CancellationException("masking cancelled") // not masked: do not publish
                }
            }
        }
}
