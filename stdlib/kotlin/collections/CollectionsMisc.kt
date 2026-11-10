/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * A few remaining kotlin.collections / kotlin.sequences functions (arrays of pairs, delegates, Iterable factory).
 */
package kotlin.collections

import kotlin.random.Random
import kotlin.reflect.KProperty

public inline fun <T> Iterable(crossinline iterator: () -> Iterator<T>): Iterable<T> = object : Iterable<T> {
    override fun iterator(): Iterator<T> = iterator()
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
