// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.internal.runtime.platformDefaults
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM side of ADR 0015 decisions 1 and 2: the JVM has no platform defaults (tests and tools install their bindings),
 * Java code cannot reach the registry or make a `PlatformTarget`.
 */
class PlatformDefaultsJvmTest {
    @Test
    fun theJvmHasNoPlatformDefaults() {
        assertSame(PlatformDefaults.NONE, platformDefaults())
    }

    @Test
    fun javaCannotCallAnyRegistryMember() {
        val callable =
            FaceBlurRuntime::class.java.declaredMethods
                .filter { javaCallable(it) && it.name != "equals" && it.name != "hashCode" && it.name != "toString" }
        assertTrue(callable.isEmpty(), "Java-callable registry members: $callable")
    }

    @Test
    fun javaCannotMakeOrReadAPlatformTarget() {
        val cls = OutputTarget.PlatformTarget::class.java
        assertEquals(emptyList(), javaCallableConstructors(cls), "Java-callable constructors")
        assertEquals(emptyList(), javaCallableMembers(cls), "Java-callable members")
        // Negative control: the same filters report a class that Java can make and read.
        assertEquals(1, javaCallableConstructors(PlantedTarget::class.java).size)
        assertEquals(listOf("getValue"), javaCallableMembers(PlantedTarget::class.java).map { it.name })
    }

    /** Public, non-synthetic constructors of [cls] and its nested classes. */
    private fun javaCallableConstructors(cls: Class<*>): List<Member> = (listOf(cls) + cls.declaredClasses)
        .flatMap { it.declaredConstructors.toList() }
        .filter(::javaCallable)

    /** Public, non-synthetic methods of [cls] and its nested classes, apart from `equals`, `hashCode`, `toString`. */
    private fun javaCallableMembers(cls: Class<*>): List<Member> = (listOf(cls) + cls.declaredClasses)
        .flatMap { it.declaredMethods.toList() }
        .filter { javaCallable(it) && it.name !in setOf("equals", "hashCode", "toString") }

    @Suppress("unused")
    class PlantedTarget(val value: String)

    private fun javaCallable(m: Member) = !m.isSynthetic && Modifier.isPublic(m.modifiers)
}
