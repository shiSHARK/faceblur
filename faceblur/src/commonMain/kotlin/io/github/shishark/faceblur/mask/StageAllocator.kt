// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.HiddenFromJava

/**
 * Every array of the mask stage's working area is made through this interface (ADR 0013 decision 6, test seam).
 * This file is the only file of the stage that may contain array constructors (source gate in the JVM tests).
 */
internal interface StageAllocator {
    fun ints(size: Int): IntArray

    fun longs(size: Int): LongArray

    fun floats(size: Int): FloatArray

    fun bytes(size: Int): ByteArray

    /** A table of [size] empty references to int arrays (the shadow tiles and the run buffers). */
    fun intTable(size: Int): Array<IntArray?>
}

/** The production allocator: the plain array constructors, nothing else. */
internal object ArrayAllocator : StageAllocator {
    override fun ints(size: Int): IntArray = IntArray(size)

    override fun longs(size: Int): LongArray = LongArray(size)

    override fun floats(size: Int): FloatArray = FloatArray(size)

    override fun bytes(size: Int): ByteArray = ByteArray(size)

    override fun intTable(size: Int): Array<IntArray?> = arrayOfNulls(size)
}

/**
 * Allocates and touches the two tile tables of an image of [tiles] tiles through [allocator] (the first part of the
 * working area, ADR 0013 decision 6), and does nothing else. Same backstop as [reserveWorkingArea]. Both are
 * synthetic on the JVM and Android ([HiddenFromJava]): they take the [StageAllocator] test seam.
 */
@HiddenFromJava
internal fun reserveTileTables(tiles: Int, allocator: StageAllocator): TileTables = backstop {
    TileTables(allocator.ints(tiles), allocator.ints(tiles)).also { it.touch() }
}

/**
 * Allocates and touches the rest of the working area of [layout] through [allocator] (ADR 0013 decision 6), and
 * does nothing else. It calls no caller code: no image, no cancellation token, no listener.
 *
 * The two reservation functions are the one place where FaceBlur catches an out-of-memory error, as a LAST-RESORT
 * BACKSTOP only: the contract is the memory pre-check before each of them ([ReservedBytes.admit]), which refuses a
 * part that does not fit without allocating it. An out-of-memory error here means that something else (on Android:
 * any thread of the app) took the memory between the check and the allocation; such an error can just as well hit
 * another thread. An [OutOfMemoryError] raised here (exact class name `java.lang.OutOfMemoryError` on the JVM and
 * Android, `kotlin.OutOfMemoryError` on Kotlin/Native) drops the buffers allocated so far and becomes
 * [CoreFailure.NotEnoughMemory] with the error as its cause. Every other throwable, including the rest of the
 * virtual-machine errors, passes unchanged. Nothing of the caller's image has been read or written at this point.
 */
@HiddenFromJava
internal fun reserveWorkingArea(layout: AreaLayout, tables: TileTables, allocator: StageAllocator): WorkingArea =
    backstop { WorkingArea(layout, tables).also { it.allocateAndTouch(allocator) } }

private inline fun <T> backstop(allocate: () -> T): T = try {
    allocate()
} catch (t: Throwable) {
    if (!isOutOfMemory(t)) throw t
    // The partly filled area is referenced from nowhere once this throws: its buffers can be collected.
    throw CoreFailure.NotEnoughMemory(t)
}

/** Exact class-name match of the platform's out-of-memory error (no subclass, no simple name, no substring). */
internal fun isOutOfMemory(t: Throwable): Boolean {
    val name = t::class.qualifiedName
    return name == JVM_OUT_OF_MEMORY || name == NATIVE_OUT_OF_MEMORY
}

private const val JVM_OUT_OF_MEMORY = "java.lang.OutOfMemoryError"
private const val NATIVE_OUT_OF_MEMORY = "kotlin.OutOfMemoryError"
