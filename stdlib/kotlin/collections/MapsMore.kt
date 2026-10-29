/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib collections/Maps.kt, generated/_Maps.kt and java.util.Map (v1-A additions).
 */
package kotlin.collections

/**
 * `java.util.Map.putIfAbsent`: associates [value] with [key] unless the key is already mapped to a non-null value;
 * returns the previous value (null if there was none).
 */
public fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? {
    val old = get(key)
    if (old == null) put(key, value)
    return old
}

public fun <K, V> MutableMap<in K, in V>.putAll(pairs: Iterable<Pair<K, V>>) {
    for ((key, value) in pairs) put(key, value)
}

public operator fun <K, V> MutableMap<out K, V>.minusAssign(key: K) {
    @Suppress("UNCHECKED_CAST")
    (this as MutableMap<K, V>).remove(key)
}

public inline fun <K, V, R : Any> Map<out K, V>.mapNotNull(transform: (Map.Entry<K, V>) -> R?): List<R> {
    val destination = ArrayList<R>()
    for (element in this) {
        val result = transform(element)
        if (result != null) destination.add(result)
    }
    return destination
}

public inline fun <K, V, R> Map<out K, V>.flatMap(transform: (Map.Entry<K, V>) -> Iterable<R>): List<R> {
    val destination = ArrayList<R>()
    for (element in this) destination.addAll(transform(element))
    return destination
}

public fun <K, V> Map<out K, V>.none(): Boolean = isEmpty()

public inline fun <K, V> Map<out K, V>.none(predicate: (Map.Entry<K, V>) -> Boolean): Boolean {
    for (element in this) if (predicate(element)) return false
    return true
}

public fun <K, V> Map<out K, V>.any(): Boolean = !isEmpty()

public fun <K, V> Map<out K, V>.count(): Int = size

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.maxByOrNull(selector: (Map.Entry<K, V>) -> R): Map.Entry<K, V>? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var maxElem = iterator.next()
    var maxValue = selector(maxElem)
    while (iterator.hasNext()) {
        val e = iterator.next()
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    }
    return maxElem
}

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.minByOrNull(selector: (Map.Entry<K, V>) -> R): Map.Entry<K, V>? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var minElem = iterator.next()
    var minValue = selector(minElem)
    while (iterator.hasNext()) {
        val e = iterator.next()
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    }
    return minElem
}

public fun <K, V> Map<out K, V>.asIterable(): Iterable<Map.Entry<K, V>> = entries

public inline fun <K, V, M : MutableMap<in K, in V>> Map<out K, V>.filterTo(destination: M, predicate: (Map.Entry<K, V>) -> Boolean): M {
    for (element in this) if (predicate(element)) destination.put(element.key, element.value)
    return destination
}

public fun <K, V> Map<out K, V>.minus(keys: Iterable<K>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for (k in keys) result.remove(k)
    return result
}

public fun <K, V> Map<out K, V>.plus(pairs: Iterable<Pair<K, V>>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for ((k, v) in pairs) result.put(k, v)
    return result
}
