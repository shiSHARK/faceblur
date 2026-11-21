// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Base of every device test class: each test method (with its `@Before` and `@After`) is limited to
 * [LIMIT_MINUTES] minutes. A hang then FAILS that test, with the stack trace of the stuck thread (and of a deadlock if
 * there is one), instead of the run waiting for ever.
 *
 * The limit is far above the real time: the whole device suite of 36 tests took about 20 seconds on the test phone, so
 * no test needs a higher limit. A test that must be faster states it with `@Test(timeout = ...)`, which applies in
 * addition to this limit (FileTargetsDeviceTest does so for its FIFO test). Every new device test class extends this
 * class.
 *
 * The base class also keeps the test process in the foreground ([ForegroundKeepAlive]): a rule has an empty activity of
 * the test APK resumed during every test and records that in a marker the phone script checks after the run.
 */
abstract class TimeLimitedDeviceTest {
    @get:Rule
    val timeLimit: Timeout = Timeout.builder()
        .withTimeout(LIMIT_MINUTES, TimeUnit.MINUTES)
        .withLookingForStuckThread(true)
        .build()

    @get:Rule
    val foregroundKeepAlive: TestRule = ForegroundKeepAliveRule()

    private companion object {
        const val LIMIT_MINUTES = 5L
    }
}
