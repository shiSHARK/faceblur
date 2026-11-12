// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import io.github.shishark.faceblur.FaceMaskException
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The error mapping of the engine. The library's exceptions have internal constructors (an app cannot make them), so
 * the tests build them by reflection with neutral arguments; the mapping itself only looks at the class and, for
 * model output errors, at the finding codes.
 */
class LibraryEngineHelpersTest {
    private fun make(type: Class<out FaceMaskException>, findings: List<String> = listOf("X")) =
        libraryException(type, findings)

    private fun kind(type: Class<out FaceMaskException>, findings: List<String> = listOf("X")) =
        failureOf(make(type, findings)).kind

    @Test
    fun `each library error maps to the failure the screen explains`() {
        assertEquals(FailureKind.CANCELLED, kind(FaceMaskException.Cancelled::class.java))
        assertEquals(FailureKind.NOT_ENOUGH_MEMORY, kind(FaceMaskException.NotEnoughMemory::class.java))
        assertEquals(FailureKind.MODEL_INTEGRITY, kind(FaceMaskException.ModelIntegrity::class.java))
        assertEquals(FailureKind.MODEL_REJECTED, kind(FaceMaskException.ModelRejected::class.java))
        assertEquals(FailureKind.IMAGE_TOO_LARGE, kind(FaceMaskException.ImageTooLarge::class.java))
        assertEquals(FailureKind.IMAGE_TOO_LARGE, kind(FaceMaskException.SourceTooLarge::class.java))
        assertEquals(FailureKind.UNSUPPORTED_IMAGE, kind(FaceMaskException.UnsupportedImage::class.java))
        assertEquals(FailureKind.SOURCE_CHANGED, kind(FaceMaskException.SourceChanged::class.java))
        assertEquals(FailureKind.TARGET_REFUSED, kind(FaceMaskException.UnsupportedTarget::class.java))
        assertEquals(FailureKind.IO, kind(FaceMaskException.IoFailed::class.java))
        assertEquals(FailureKind.OTHER, kind(FaceMaskException.Closed::class.java))
    }

    @Test
    fun `model output errors are told apart by their finding codes and keep the library message`() {
        val masks = failureOf(make(FaceMaskException.ModelOutputInvalid::class.java, listOf("TOO_MANY_MASKS")))
        assertEquals(FailureKind.TOO_MANY_MASKS, masks.kind)
        assertTrue(masks.detail!!.startsWith("Model output invalid: TOO_MANY_MASKS"))
        assertEquals(
            FailureKind.TOO_MANY_PAIR_CHECKS,
            kind(FaceMaskException.ModelOutputInvalid::class.java, listOf("TOO_MANY_NMS_PAIR_CHECKS")),
        )
        assertEquals(FailureKind.OTHER, kind(FaceMaskException.ModelOutputInvalid::class.java, listOf("RUN_FAILED")))
    }

    @Test
    fun `a failure keeps the leftover flag and other exceptions carry no detail`() {
        assertTrue(failureOf(make(FaceMaskException.IoFailed::class.java), leftover = true).leftover)
        val other = failureOf(IllegalStateException("secret text"))
        assertEquals(FailureKind.OTHER, other.kind)
        assertNull(other.detail)
        assertFalse(other.leftover)
    }

    @Test
    fun `the model hash is the lower-case SHA-256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `a model file is read up to the limit and a longer one is refused`() {
        val bytes = ByteArray(100) { it.toByte() }
        assertEquals(bytes.toList(), readLimited(ByteArrayInputStream(bytes), limit = 100).toList())
        val failure = assertFailsWith<DemoFailure> { readLimited(ByteArrayInputStream(bytes), limit = 99) }
        assertEquals(FailureKind.MODEL_TOO_LARGE, failure.kind)
    }
}
