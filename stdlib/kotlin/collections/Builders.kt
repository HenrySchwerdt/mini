/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/Collections.kt, Sets.kt, Maps.kt (factory functions and map helpers).
 * Read-only lists/sets/maps are the mutable implementations behind the read-only interfaces (as on Kotlin/Native).
 */
package kotlin.collections

// ---- lists -----------------------------------------------------------------------------------

public fun <T> emptyList(): List<T> = EmptyList as List<T>

private object EmptyList : AbstractList<Nothing>(), RandomAccess {
    override val size: Int
        get() = 0

    override fun get(index: Int): Nothing = mini.internal.throwIndexOutOfBounds("Empty list doesn't contain element at index $index.")

    override fun equals(other: Any?): Boolean = other is List<*> && other.isEmpty()

    override fun hashCode(): Int = 1

    override fun toString(): String = "[]"
}

public fun <T> listOf(element: T): List<T> {
    val list = ArrayList<T>(1)
    list.add(element)
    return list
}

public fun <T> listOf(vararg elements: T): List<T> {
    if (elements.size == 0) return emptyList()
    val list = ArrayList<T>(elements.size)
    var i = 0
    while (i < elements.size) {
        list.add(elements[i])
        i++
    }
    return list
}

public fun <T> mutableListOf(): MutableList<T> = ArrayList()

public fun <T> mutableListOf(vararg elements: T): MutableList<T> {
    val list = ArrayList<T>(elements.size)
    var i = 0
    while (i < elements.size) {
        list.add(elements[i])
        i++
    }
    return list
}

public fun <T> arrayListOf(): ArrayList<T> = ArrayList()

public fun <T> arrayListOf(vararg elements: T): ArrayList<T> {
    val list = ArrayList<T>(elements.size)
    var i = 0
    while (i < elements.size) {
        list.add(elements[i])
        i++
    }
    return list
}

public fun <T : Any> listOfNotNull(element: T?): List<T> = if (element != null) listOf(element) else emptyList()

public fun <T : Any> listOfNotNull(vararg elements: T?): List<T> {
    val list = ArrayList<T>()
    var i = 0
    while (i < elements.size) {
        val e = elements[i]
        if (e != null) list.add(e)
        i++
    }
    return list
}

public inline fun <T> List(size: Int, init: (index: Int) -> T): List<T> = MutableList(size, init)

public inline fun <T> MutableList(size: Int, init: (index: Int) -> T): MutableList<T> {
    val list = ArrayList<T>(size)
    repeat(size) { index -> list.add(init(index)) }
    return list
}

public inline fun <T> buildList(builderAction: MutableList<T>.() -> Unit): List<T> {
    val list = ArrayList<T>()
    list.builderAction()
    return list
}

public fun <T> Array<out T>.asList(): List<T> {
    val list = ArrayList<T>(size)
    var i = 0
    while (i < size) {
        list.add(this[i])
        i++
    }
    return list
}

public fun <T> Array<out T>.toList(): List<T> = asList()

// ---- sets ---------------------------------------------------------------------------------------

public fun <T> emptySet(): Set<T> = EmptySet as Set<T>

private object EmptySet : AbstractSet<Nothing>() {
    override val size: Int
        get() = 0

    override fun iterator(): Iterator<Nothing> = EmptyList.iterator()

    override fun equals(other: Any?): Boolean = other is Set<*> && other.isEmpty()

    override fun hashCode(): Int = 0

    override fun toString(): String = "[]"
}

public fun <T> setOf(element: T): Set<T> {
    val set = LinkedHashSet<T>()
    set.add(element)
    return set
}

public fun <T> setOf(vararg elements: T): Set<T> {
    if (elements.size == 0) return emptySet()
    val set = LinkedHashSet<T>()
    var i = 0
    while (i < elements.size) {
        set.add(elements[i])
        i++
    }
    return set
}

public fun <T> mutableSetOf(): MutableSet<T> = LinkedHashSet()

public fun <T> mutableSetOf(vararg elements: T): MutableSet<T> {
    val set = LinkedHashSet<T>()
    var i = 0
    while (i < elements.size) {
        set.add(elements[i])
        i++
    }
    return set
}

public fun <T> hashSetOf(): HashSet<T> = HashSet()

public fun <T> hashSetOf(vararg elements: T): HashSet<T> {
    val set = HashSet<T>(mapCapacity(elements.size))
    var i = 0
    while (i < elements.size) {
        set.add(elements[i])
        i++
    }
    return set
}

public fun <T> linkedSetOf(vararg elements: T): LinkedHashSet<T> {
    val set = LinkedHashSet<T>()
    var i = 0
    while (i < elements.size) {
        set.add(elements[i])
        i++
    }
    return set
}

public infix fun <T> Iterable<T>.intersect(other: Iterable<T>): Set<T> {
    val set = this.toMutableSet()
    set.retainAll(other.toSet())
    return set
}

public infix fun <T> Iterable<T>.union(other: Iterable<T>): Set<T> {
    val set = this.toMutableSet()
    set.addAll(other)
    return set
}

public infix fun <T> Iterable<T>.subtract(other: Iterable<T>): Set<T> {
    val set = this.toMutableSet()
    set.removeAll(other.toSet())
    return set
}

public operator fun <T> Set<T>.plus(element: T): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.add(element)
    return result
}

public operator fun <T> Set<T>.minus(element: T): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.remove(element)
    return result
}

// ---- maps ----------------------------------------------------------------------------------------

public fun <K, V> emptyMap(): Map<K, V> = LinkedHashMap()

public fun <K, V> mapOf(pair: Pair<K, V>): Map<K, V> {
    val map = LinkedHashMap<K, V>()
    map.put(pair.first, pair.second)
    return map
}

public fun <K, V> mapOf(vararg pairs: Pair<K, V>): Map<K, V> {
    val map = LinkedHashMap<K, V>()
    var i = 0
    while (i < pairs.size) {
        map.put(pairs[i].first, pairs[i].second)
        i++
    }
    return map
}

public fun <K, V> mutableMapOf(): MutableMap<K, V> = LinkedHashMap()

public fun <K, V> mutableMapOf(vararg pairs: Pair<K, V>): MutableMap<K, V> {
    val map = LinkedHashMap<K, V>()
    var i = 0
    while (i < pairs.size) {
        map.put(pairs[i].first, pairs[i].second)
        i++
    }
    return map
}

public fun <K, V> hashMapOf(): HashMap<K, V> = HashMap()

public fun <K, V> hashMapOf(vararg pairs: Pair<K, V>): HashMap<K, V> {
    val map = HashMap<K, V>(mapCapacity(pairs.size))
    var i = 0
    while (i < pairs.size) {
        map.put(pairs[i].first, pairs[i].second)
        i++
    }
    return map
}

public fun <K, V> linkedMapOf(vararg pairs: Pair<K, V>): LinkedHashMap<K, V> {
    val map = LinkedHashMap<K, V>()
    var i = 0
    while (i < pairs.size) {
        map.put(pairs[i].first, pairs[i].second)
        i++
    }
    return map
}

public inline fun <K, V> buildMap(builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> {
    val map = LinkedHashMap<K, V>()
    map.builderAction()
    return map
}

public fun <K, V> Map<K, V>.getValue(key: K): V {
    val v = get(key)
    if (v == null && !containsKey(key)) mini.internal.throwNoSuchElement("Key $key is missing in the map.")
    @Suppress("UNCHECKED_CAST")
    return v as V
}

public inline fun <K, V> Map<K, V>.getOrElse(key: K, defaultValue: () -> V): V {
    val v = get(key)
    if (v == null && !containsKey(key)) return defaultValue()
    @Suppress("UNCHECKED_CAST")
    return v as V
}

public fun <K, V> Map<K, V>.getOrDefault(key: K, defaultValue: V): V {
    val v = get(key)
    if (v == null && !containsKey(key)) return defaultValue
    @Suppress("UNCHECKED_CAST")
    return v as V
}

public inline fun <K, V> MutableMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
    val value = get(key)
    return if (value == null) {
        val answer = defaultValue()
        put(key, answer)
        answer
    } else {
        value
    }
}

public operator fun <K, V> MutableMap<K, V>.set(key: K, value: V) {
    put(key, value)
}

@Suppress("UNCHECKED_CAST")
public operator fun <K, V> Map<out K, V>.contains(key: K): Boolean = (this as Map<K, V>).containsKey(key)

public fun <K, V> Map<K, V>.isNotEmpty(): Boolean = !isEmpty()

public fun <K, V> Map<K, V>?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

public operator fun <K, V> Map.Entry<K, V>.component1(): K = key

public operator fun <K, V> Map.Entry<K, V>.component2(): V = value

public fun <K, V> Map.Entry<K, V>.toPair(): Pair<K, V> = Pair(key, value)

public inline fun <K, V> Map<out K, V>.forEach(action: (Map.Entry<K, V>) -> Unit) {
    for (element in this.entries) action(element)
}

public inline fun <K, V, R> Map<out K, V>.map(transform: (Map.Entry<K, V>) -> R): List<R> {
    val destination = ArrayList<R>(size)
    for (item in this.entries) destination.add(transform(item))
    return destination
}

public inline fun <K, V> Map<out K, V>.filter(predicate: (Map.Entry<K, V>) -> Boolean): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (entry in this.entries) {
        if (predicate(entry)) result.put(entry.key, entry.value)
    }
    return result
}

public inline fun <K, V> Map<out K, V>.filterKeys(predicate: (K) -> Boolean): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (entry in this.entries) {
        if (predicate(entry.key)) result.put(entry.key, entry.value)
    }
    return result
}

public inline fun <K, V> Map<out K, V>.filterValues(predicate: (V) -> Boolean): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (entry in this.entries) {
        if (predicate(entry.value)) result.put(entry.key, entry.value)
    }
    return result
}

public inline fun <K, V, R> Map<out K, V>.mapValues(transform: (Map.Entry<K, V>) -> R): Map<K, R> {
    val result = LinkedHashMap<K, R>(size)
    for (entry in this.entries) result.put(entry.key, transform(entry))
    return result
}

public inline fun <K, V, R> Map<out K, V>.mapKeys(transform: (Map.Entry<K, V>) -> R): Map<R, V> {
    val result = LinkedHashMap<R, V>(size)
    for (entry in this.entries) result.put(transform(entry), entry.value)
    return result
}

public inline fun <K, V> Map<out K, V>.any(predicate: (Map.Entry<K, V>) -> Boolean): Boolean {
    for (element in this.entries) if (predicate(element)) return true
    return false
}

public inline fun <K, V> Map<out K, V>.all(predicate: (Map.Entry<K, V>) -> Boolean): Boolean {
    for (element in this.entries) if (!predicate(element)) return false
    return true
}

public inline fun <K, V> Map<out K, V>.count(predicate: (Map.Entry<K, V>) -> Boolean): Int {
    var count = 0
    for (element in this.entries) if (predicate(element)) count++
    return count
}

public fun <K, V> Map<out K, V>.toList(): List<Pair<K, V>> {
    val result = ArrayList<Pair<K, V>>(size)
    for (e in this.entries) result.add(Pair(e.key, e.value))
    return result
}

public fun <K, V> Iterable<Pair<K, V>>.toMap(): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (p in this) result.put(p.first, p.second)
    return result
}

public fun <K, V> Map<out K, V>.toMap(): Map<K, V> = LinkedHashMap(this)

public fun <K, V> Map<out K, V>.toMutableMap(): MutableMap<K, V> = LinkedHashMap(this)

public operator fun <K, V> Map<out K, V>.plus(pair: Pair<K, V>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    result.put(pair.first, pair.second)
    return result
}

public operator fun <K, V> Map<out K, V>.plus(map: Map<out K, V>): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    result.putAll(map)
    return result
}

public operator fun <K, V> Map<out K, V>.minus(key: K): Map<K, V> {
    val result = LinkedHashMap<K, V>(this)
    result.remove(key)
    return result
}

public operator fun <K, V> MutableMap<in K, in V>.plusAssign(pair: Pair<K, V>) {
    put(pair.first, pair.second)
}

public inline operator fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>> = entries.iterator()

public inline operator fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = entries.iterator()
