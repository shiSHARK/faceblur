// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An empty, opaque activity of the device-test APK (never shipped). While it is resumed the test process is the
 * foreground app of the phone, as a real app is: some phones' app freezers (OEM background-process management) freeze
 * the instrumentation process of a test run that has no visible activity, and a frozen process stops without any
 * output. The activity shows nothing, takes no input and reads or writes nothing.
 */
class KeepAliveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumedSignal.countDown()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        resumed = false
        super.onDestroy()
    }

    internal companion object {
        @Volatile var resumed: Boolean = false

        @Volatile var resumedSignal: CountDownLatch = CountDownLatch(1)
    }
}

/**
 * The per-process owner of [KeepAliveActivity]: it starts the activity before a test when none is resumed, and checks
 * after the test that it is still resumed. (The instrumentation runner finishes every activity after each test, so the
 * activity is started again for the next one; the gap is a few milliseconds.)
 *
 * After every test it appends one line to [MARKER] in the test app's private storage:
 * `test=<class>#<method> before=<ok|lost> after=<ok|lost>`. The phone script (`scripts/phone-device-tests.sh`) reads
 * that file after the run and ends the phone step BLOCKED, not FAILED, when a test ran without the foreground
 * keep-alive: the run is then not a valid measurement of the library, whatever its result. The marker holds test names
 * and these two words only.
 */
internal object ForegroundKeepAlive {
    const val MARKER = "keepalive-marker.txt"
    private const val START_WAIT_SECONDS = 15L

    private val lock = Any()

    /** Starts the activity when it is not running and waits until it is resumed; true when it is. */
    fun acquire(): Boolean = synchronized(lock) {
        if (!KeepAliveActivity.resumed) {
            KeepAliveActivity.resumedSignal = CountDownLatch(1)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val intent = Intent()
                .setComponent(ComponentName(context.packageName, KeepAliveActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { instrumentation.startActivitySync(intent) }
            KeepAliveActivity.resumedSignal.await(START_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        KeepAliveActivity.resumed
    }

    /** Appends the marker line of one finished test. */
    fun release(test: String, before: Boolean) = synchronized(lock) {
        val after = KeepAliveActivity.resumed
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, MARKER)
        file.appendText("test=$test before=${word(before)} after=${word(after)}\n")
    }

    private fun word(ok: Boolean) = if (ok) "ok" else "lost"
}

/** Rule of [TimeLimitedDeviceTest]: the keep-alive around every device test. It never fails a test. */
internal class ForegroundKeepAliveRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val before = ForegroundKeepAlive.acquire()
            try {
                base.evaluate()
            } finally {
                ForegroundKeepAlive.release("${description.className}#${description.methodName}", before)
            }
        }
    }
}
