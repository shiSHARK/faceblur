// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * A copy of [this] that cannot be changed through any reference: Kotlin's read-only collection classes throw
 * `UnsupportedOperationException` for every mutator called from Java (ADR 0012 decision 1, deep immutability).
 * `toList()` alone is not enough, because it returns a `java.util.ArrayList` on the JVM.
 */
internal fun <T> List<T>.readOnly(): List<T> = if (this is ReadOnlyList<T>) this else ReadOnlyList(toTypedArray<Any?>())

/** A read-only copy of [this], in the iteration order of [this]. */
internal fun <K, V> Map<K, V>.readOnly(): Map<K, V> = ReadOnlyMap(
    entries.map {
        ReadOnlyEntry(it.key, it.value)
    }.readOnly(),
)

private class ReadOnlyList<T>(private val items: Array<Any?>) : AbstractList<T>() {
    override val size: Int get() = items.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T = items[index] as T
}

private class ReadOnlyEntry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V> {
    override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value

    override fun hashCode(): Int = key.hashCode() xor value.hashCode()

    override fun toString(): String = "$key=$value"
}

private class ReadOnlyMap<K, V>(private val list: List<Map.Entry<K, V>>) : AbstractMap<K, V>() {
    override val entries: Set<Map.Entry<K, V>> = ReadOnlyEntrySet(list)
}

private class ReadOnlyEntrySet<K, V>(private val list: List<Map.Entry<K, V>>) : AbstractSet<Map.Entry<K, V>>() {
    override val size: Int get() = list.size

    override fun iterator(): Iterator<Map.Entry<K, V>> = list.iterator()
}
