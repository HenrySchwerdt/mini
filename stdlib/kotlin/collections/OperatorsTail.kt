/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * The remaining plus / minus / plusAssign / minusAssign overloads of kotlin.collections (arrays and sequences as operands).
 */
package kotlin.collections

// ---- Iterable / Collection / Set: plus and minus ----------------------------------------------------------------------------

public operator fun <T> Iterable<T>.plus(elements: Array<out T>): List<T> {
    if (this is Collection<T>) return this.plus(elements)
    val result = ArrayList<T>()
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Collection<T>.plus(elements: Array<out T>): List<T> {
    val result = ArrayList<T>(this.size + elements.size)
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Iterable<T>.plus(elements: Sequence<T>): List<T> {
    val result = ArrayList<T>()
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Collection<T>.plus(elements: Sequence<T>): List<T> {
    val result = ArrayList<T>(this.size + 10)
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Iterable<T>.minus(elements: Array<out T>): List<T> {
    if (elements.isEmpty()) return this.toList()
    val other = elements.toHashSet()
    return this.filterNot { it in other }
}

public operator fun <T> Iterable<T>.minus(elements: Sequence<T>): List<T> {
    val other = elements.toHashSet()
    if (other.isEmpty()) return this.toList()
    return this.filterNot { it in other }
}

public operator fun <T> Set<T>.plus(elements: Array<out T>): Set<T> {
    val result = LinkedHashSet<T>(mapCapacity(this.size + elements.size))
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Set<T>.plus(elements: Sequence<T>): Set<T> {
    val result = LinkedHashSet<T>(mapCapacity(this.size * 2))
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Set<T>.minus(elements: Array<out T>): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.removeAll(elements.toSet())
    return result
}

public operator fun <T> Set<T>.minus(elements: Sequence<T>): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.removeAll(elements.toSet())
    return result
}

// ---- MutableCollection ------------------------------------------------------------------------------------------------------

public operator fun <T> MutableCollection<in T>.plusAssign(elements: Array<T>) {
    this.addAll(elements)
}

public operator fun <T> MutableCollection<in T>.plusAssign(elements: Sequence<T>) {
    this.addAll(elements)
}

public operator fun <T> MutableCollection<in T>.minusAssign(elements: Iterable<T>) {
    this.removeAll(elements)
}

public operator fun <T> MutableCollection<in T>.minusAssign(elements: Array<T>) {
    this.removeAll(elements)
}

public operator fun <T> MutableCollection<in T>.minusAssign(elements: Sequence<T>) {
    this.removeAll(elements)
}

// ---- Map --------------------------------------------------------------------------------------------------------------------

public operator fun <K, V> Map<out K, V>.plus(pairs: Array<out Pair<K, V>>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for (p in pairs) result.put(p.first, p.second)
    return result
}

public operator fun <K, V> Map<out K, V>.plus(pairs: Sequence<Pair<K, V>>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for (p in pairs) result.put(p.first, p.second)
    return result
}

public operator fun <K, V> Map<out K, V>.minus(keys: Array<out K>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for (k in keys) result.remove(k)
    return result
}

public operator fun <K, V> Map<out K, V>.minus(keys: Sequence<K>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    for (k in keys) result.remove(k)
    return result
}

public operator fun <K, V> MutableMap<in K, in V>.plusAssign(pairs: Iterable<Pair<K, V>>) {
    for (p in pairs) put(p.first, p.second)
}

public operator fun <K, V> MutableMap<in K, in V>.plusAssign(pairs: Array<out Pair<K, V>>) {
    for (p in pairs) put(p.first, p.second)
}

public operator fun <K, V> MutableMap<in K, in V>.plusAssign(pairs: Sequence<Pair<K, V>>) {
    for (p in pairs) put(p.first, p.second)
}

public operator fun <K, V> MutableMap<in K, in V>.plusAssign(map: Map<K, V>) {
    putAll(map)
}

public operator fun <K, V> MutableMap<K, V>.minusAssign(keys: Iterable<K>) {
    for (k in keys) remove(k)
}

public operator fun <K, V> MutableMap<K, V>.minusAssign(keys: Array<out K>) {
    for (k in keys) remove(k)
}

public operator fun <K, V> MutableMap<K, V>.minusAssign(keys: Sequence<K>) {
    for (k in keys) remove(k)
}
