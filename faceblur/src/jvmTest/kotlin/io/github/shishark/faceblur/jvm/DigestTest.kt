// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelDigest
import io.github.shishark.faceblur.testmodel.Onnx
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The common SHA-256 against the JDK's, its memory, and the installable platform digest. */
class DigestTest {
    private val jdk = ModelDigest { MessageDigest.getInstance("SHA-256").digest(it) }

    @AfterTest
    fun reset() = FaceBlurRuntime.installDigest(null)

    @Test
    fun theCommonDigestEqualsTheJdkDigestForEveryPaddingCase() {
        val random = Random(256)
        val sizes = (0..300).toList() + listOf(1_000, 4_095, 4_096, 65_535, 65_536, 1_048_577)
        for (n in sizes) {
            val data = ByteArray(n).also { random.nextBytes(it) }
            assertEquals(Sha256.toHex(jdk.sha256(data)), Sha256.hex(data), "size $n")
        }
    }

    /** Hashing reads the input in place: 50 MiB are hashed with well under 1 MiB of allocation (no second copy). */
    @Test
    fun hashingAllocatesNoCopyOfTheModel() {
        val data = ByteArray(52_428_800) { (it * 31 + 7).toByte() }
        Sha256.digest(data) // warm-up
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        Sha256.digest(data)
        val allocated = threads.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 1_048_576L, "allocated $allocated bytes for a 50 MiB hash")
    }

    @Test
    fun anInstalledPlatformDigestIsUsedForThePin() {
        val bytes = Onnx.yunetModel()
        val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, Sha256.hex(bytes))
        var calls = 0
        FaceBlurRuntime.installDigest {
            calls++
            jdk.sha256(it)
        }
        val m = DetectorModel.load(ModelSource.fromBytes(bytes), profile)
        assertEquals(1, calls, "one digest per load")
        assertEquals(profile.expectedSha256, m.info.sha256)
        // A digest that disagrees makes the pin fail (the platform result is what is compared).
        FaceBlurRuntime.installDigest { ByteArray(32) }
        assertFailsWith<FaceMaskException.ModelIntegrity> { DetectorModel.load(ModelSource.fromBytes(bytes), profile) }
        // A broken digest (wrong length) is a library failure, never a pass.
        FaceBlurRuntime.installDigest { ByteArray(20) }
        assertFailsWith<FaceMaskException.Internal> { DetectorModel.load(ModelSource.fromBytes(bytes), profile) }
    }

    @Test
    fun theExpectedHashIsStoredInLowerCaseAndNamesNoHashOnMismatch() {
        val bytes = Onnx.yunetModel()
        val sha = Sha256.hex(bytes)
        val upper = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, sha.uppercase())
        assertEquals(sha, upper.expectedSha256)
        assertEquals(DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, sha), upper)
        assertEquals(upper.expectedSha256, DetectorModel.load(ModelSource.fromBytes(bytes), upper).info.sha256)
        val other = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, "a".repeat(64))
        val e =
            assertFailsWith<FaceMaskException.ModelIntegrity> {
                DetectorModel.load(ModelSource.fromBytes(bytes), other)
            }
        assertTrue(sha !in e.message!! && "a".repeat(64) !in e.message!!, "the message names no hash: ${e.message}")
    }
}
