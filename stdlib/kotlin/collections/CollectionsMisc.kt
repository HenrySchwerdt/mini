/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * A few remaining kotlin.collections / kotlin.sequences functions (arrays of pairs, delegates, Iterable factory).
 */
package kotlin.collections

import kotlin.reflect.KProperty

public fun <T> Iterable(iterator: () -> Iterator<T>): Iterable<T> = LambdaIterable(iterator)

private class LambdaIterable<T>(private val source: () -> Iterator<T>) : Iterable<T> {
    override fun iterator(): Iterator<T> = source()
}

public fun <K, V> Array<out Pair<K, V>>.toMap(): Map<K, V> = when (size) {
    0 -> emptyMap()
    1 -> mapOf(this[0])
    else -> toMap(LinkedHashMap<K, V>(mapCapacity(size)))
}

public fun <K, V, M : MutableMap<in K, in V>> Array<out Pair<K, V>>.toMap(destination: M): M {
    for (p in this) destination.put(p.first, p.second)
    return destination
}

public fun <K, V> Sequence<Pair<K, V>>.toMap(): Map<K, V> = toMap(LinkedHashMap<K, V>()).optimizeReadOnlyMap()

public fun <K, V, M : MutableMap<in K, in V>> Sequence<Pair<K, V>>.toMap(destination: M): M {
    for (p in this) destination.put(p.first, p.second)
    return destination
}

private fun <K, V> Map<K, V>.optimizeReadOnlyMap(): Map<K, V> = when (size) {
    0 -> emptyMap()
    1 -> toSingletonMap()
    else -> this
}

private fun <K, V> Map<K, V>.toSingletonMap(): Map<K, V> {
    val e = entries.iterator().next()
    return mapOf(e.key to e.value)
}

public fun <T> Array<out T>?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

public inline fun <reified T> Array<out T>?.orEmpty(): Array<out T> = this ?: arrayOf<T>()

@Suppress("UNCHECKED_CAST")
public operator fun <V, V1 : V> MutableMap<in String, out V>.getValue(thisRef: Any?, property: KProperty<*>): V1 {
    val m = this as Map<String, V>
    val key = property.name
    if (m is MapWithDefault<*, *>) return (m as MapWithDefault<String, V>).getOrImplicitDefault(key) as V1
    val v = m[key]
    if (v == null && !m.containsKey(key)) throw NoSuchElementException("Key " + key + " is missing in the map.")
    return v as V1
}

/** Removes the entry for [key] only if it is mapped to [value] (`MutableMap.remove(key, value)` of the JVM). */
@mini.memory.Mutates public fun <K, V> MutableMap<K, V>.remove(key: K, value: V): Boolean {
    if (containsKey(key) && this[key] == value) {
        remove(key)
        return true
    }
    return false
}

public fun <T> ArrayDeque<T>.toArray(): Array<Any?> {
    val result = arrayOfNulls<Any?>(size)
    var i = 0
    for (e in this) result[i++] = e
    return result
}

@Suppress("UNCHECKED_CAST")
public fun <T> ArrayDeque<*>.toArray(array: Array<T>): Array<T> {
    val result: Array<T> = if (array.size >= size) array else array.copyOf(size) as Array<T>
    var i = 0
    for (e in this) result[i++] = e as T
    if (result.size > size) result[size] = null as T
    return result
}
