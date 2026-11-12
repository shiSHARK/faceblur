// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Holds the controller for the life of the process, so that a rotation or a font-scale change does not lose the
 * photo being reviewed. Work runs on one background thread; results are posted to the main thread. The app writes
 * nothing to disk by itself and keeps no cache.
 */
class DemoApp : Application() {
    /** Set by `onCreate`; internal so that screen tests can supply a controller with a test engine. */
    lateinit var controller: DemoController<Uri>
        internal set

    /** The in-memory pipeline screen's state; same worker thread as the controller. */
    lateinit var pipeline: PipelineController<Uri>
        internal set

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds only: any disk, network or slow call on the main thread is reported in the log with its
            // stack (no image data, no coordinates), so that work on the main thread is found early.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        val main = Handler(Looper.getMainLooper())
        val engine = LibraryEngine(this)
        val worker = newWorker()
        controller = DemoController(engine, worker) { main.post(it) }
        pipeline = PipelineController(engine.bitmapPipeline(getString(R.string.pipeline_watermark)), worker) {
            main.post(it)
        }
    }

    companion object {
        /**
         * The one background thread for model loading, detection, decoding and saving. It runs at background
         * priority, so that the work (and the runtime's threads, which start from it) cannot starve the main thread.
         */
        fun newWorker(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                WORKER_NAME,
            )
        }

        const val WORKER_NAME = "faceblur-demo-worker"
    }
}
