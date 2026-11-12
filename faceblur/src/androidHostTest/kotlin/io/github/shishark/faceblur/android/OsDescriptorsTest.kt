// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.OsConstants
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * `OsDescriptors.open` is read-only by construction (PRIV-005): any flag that can write, create, truncate or append is
 * refused before the platform call, whoever calls it. Files are created only through `create` (the masked output's
 * temporary file, in PathWriteRules). Robolectric supplies the Linux `OsConstants` values; the refusal happens before
 * `Os.open`, so the host sees the real decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OsDescriptorsTest {
    private val path = "/nonexistent/faceblur-open-probe"

    @Test
    fun theFlagsUnderTestAreRealDistinctBits() {
        // Guards the refusal tests below against a host where the constants were all zero.
        assertEquals(0, OsConstants.O_RDONLY)
        for (flag in listOf(
            OsConstants.O_WRONLY,
            OsConstants.O_RDWR,
            OsConstants.O_CREAT,
            OsConstants.O_TRUNC,
            OsConstants.O_APPEND,
            OsConstants.O_ACCMODE,
        )) {
            assertNotEquals(0, flag)
        }
        assertEquals(OsConstants.O_ACCMODE, OsConstants.O_ACCMODE or OsConstants.O_WRONLY or OsConstants.O_RDWR)
    }

    @Test
    fun openRefusesEveryFlagThatCanWriteBeforeThePlatformCall() {
        val base = OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC
        val refused =
            mapOf(
                "O_WRONLY" to OsConstants.O_WRONLY,
                "O_RDWR" to OsConstants.O_RDWR,
                "O_CREAT" to OsConstants.O_CREAT,
                "O_TRUNC" to OsConstants.O_TRUNC,
                "O_APPEND" to OsConstants.O_APPEND,
                "O_RDWR|O_CREAT" to (OsConstants.O_RDWR or OsConstants.O_CREAT),
                "O_WRONLY|O_CREAT|O_EXCL" to (OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL),
                "PathWriteRules.createFlags" to PathWriteRules.createFlags,
            )
        for ((name, flags) in refused) {
            for (extra in listOf(0, base)) {
                val e = assertFailsWith<IllegalArgumentException>(name) { OsDescriptors.open(path, flags or extra) }
                assertEquals("a read-only open was given flags that can write", e.message, name)
            }
        }
    }

    @Test
    fun theReadOnlyFlagsOfTodaysCallersPassTheCheck() {
        // The three callers of FileCalls.open: model files, path sources and the target directory (fsync).
        for (flags in listOf(
            ModelFileRules.openFlags,
            PathSourceRules.openFlags,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
        )) {
            PathWriteRules.requireReadOnly(flags)
        }
    }
}
