// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.os.Looper
import android.os.Process
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Guards against work on the main thread: the app's worker is another thread, at background priority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoAppTest {
    @Test
    fun `the worker is not the main thread and runs at background priority`() {
        val worker = DemoApp.newWorker()
        try {
            val result = worker.submit<Pair<Thread, Int>> {
                Thread.currentThread() to Process.getThreadPriority(Process.myTid())
            }.get(5, TimeUnit.SECONDS)
            assertNotEquals(Looper.getMainLooper().thread, result.first)
            assertEquals(DemoApp.WORKER_NAME, result.first.name)
            assertEquals(Process.THREAD_PRIORITY_BACKGROUND, result.second)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `the controller of the app sends engine work to the worker, never to the main thread`() {
        val engine = FakeEngine()
        val worker = DemoApp.newWorker()
        val posted = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val controller = DemoController(engine, worker) { posted.add(it) }
        try {
            controller.chooseModel("model")
            posted.poll(5, TimeUnit.SECONDS)!!.run()
            controller.useModel()
            posted.poll(5, TimeUnit.SECONDS)!!.run()
            controller.choosePhoto("photo")
            posted.poll(5, TimeUnit.SECONDS)!!.run()
            controller.save("target")
            posted.poll(5, TimeUnit.SECONDS)!!.run()
            val main = Looper.getMainLooper().thread
            assertEquals(4, engine.threads.size)
            engine.threads.forEach {
                assertNotEquals(main, it)
                assertEquals(DemoApp.WORKER_NAME, it.name)
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
