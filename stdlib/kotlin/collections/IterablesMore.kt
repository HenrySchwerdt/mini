/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib collections/{ReversedViews,Collections,Iterables,MutableCollections}.kt and
 * generated/_Collections.kt (v1-A additions: what the backend sources and typical programs use).
 */
package kotlin.collections

// ---- reversed views (ReversedViews.kt) ----------------------------------------------------------------

private fun List<*>.reverseElementIndex(index: Int): Int {
    if (index < 0 || index > lastIndex) throw IndexOutOfBoundsException("Element index $index must be in range [${0..lastIndex}].")
    return lastIndex - index
}

private fun List<*>.reversePositionIndex(index: Int): Int {
    if (index < 0 || index > size) throw IndexOutOfBoundsException("Position index $index must be in range [${0..size}].")
    return size - index
}

private class ReversedListReadOnly<out T>(private val delegate: List<T>) : AbstractList<T>() {
    override val size: Int get() = delegate.size
    override fun get(index: Int): T = delegate[reverseElementIndex(index)]
}

private class ReversedList<T>(private val delegate: MutableList<T>) : AbstractMutableList<T>() {
    override val size: Int get() = delegate.size
    override fun get(index: Int): T = delegate[reverseElementIndex(index)]

    override fun clear() = delegate.clear()
    override fun removeAt(index: Int): T = delegate.removeAt(reverseElementIndex(index))
    override fun set(index: Int, element: T): T = delegate.set(reverseElementIndex(index), element)
    override fun add(index: Int, element: T) {
        delegate.add(reversePositionIndex(index), element)
    }
}

/** Returns a reversed read-only view of the original List. */
public fun <T> List<T>.asReversed(): List<T> = ReversedListReadOnly(this)

/** Returns a reversed mutable view of the original mutable List. */
public fun <T> MutableList<T>.asReversed(): MutableList<T> = ReversedList(this)

// ---- searching ----------------------------------------------------------------------------------------

public inline fun <T> Iterable<T>.indexOfLast(predicate: (T) -> Boolean): Int {
    var lastIndex = -1
    var index = 0
    for (item in this) {
        if (predicate(item)) lastIndex = index
        index++
    }
    return lastIndex
}

public inline fun <T> List<T>.indexOfLast(predicate: (T) -> Boolean): Int {
    val iterator = this.listIterator(size)
    while (iterator.hasPrevious()) {
        if (predicate(iterator.previous())) {
            return iterator.nextIndex()
        }
    }
    return -1
}

public inline fun <T> List<T>.findLast(predicate: (T) -> Boolean): T? = lastOrNull(predicate)

// ---- ifEmpty / orEmpty --------------------------------------------------------------------------------

@Suppress("BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER")
public inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : Collection<*>, C : R =
    if (isEmpty()) defaultValue() else this

@Suppress("BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER")
public inline fun <M, R> M.ifEmpty(defaultValue: () -> R): R where M : Map<*, *>, M : R =
    if (isEmpty()) defaultValue() else this

public fun <T> Set<T>?.orEmpty(): Set<T> = this ?: emptySet()

public fun <K, V> Map<K, V>?.orEmpty(): Map<K, V> = this ?: emptyMap()

// ---- type filtering -----------------------------------------------------------------------------------

public inline fun <reified R> Iterable<*>.filterIsInstance(): List<R> {
    return filterIsInstanceTo(ArrayList<R>())
}

public inline fun <reified R, C : MutableCollection<in R>> Iterable<*>.filterIsInstanceTo(destination: C): C {
    for (element in this) if (element is R) destination.add(element)
    return destination
}

public inline fun <reified R> Iterable<*>.firstIsInstanceOrNull(): R? {
    for (element in this) if (element is R) return element
    return null
}

// ---- sets ---------------------------------------------------------------------------------------------

public operator fun <T> Set<T>.plus(elements: Iterable<T>): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Set<T>.minus(elements: Iterable<T>): Set<T> {
    val result = LinkedHashSet<T>(this)
    result.removeAll(elements)
    return result
}

public operator fun <T> Iterable<T>.minus(elements: Iterable<T>): List<T> {
    val other = elements.toHashSet()
    if (other.isEmpty()) return this.toList()
    return this.filterNot { it in other }
}

// ---- more Iterable functions --------------------------------------------------------------------------

public inline fun <T> List<T>.takeLastWhile(predicate: (T) -> Boolean): List<T> {
    var i = size
    while (i > 0 && predicate(this[i - 1])) i--
    return subList(i, size)
}

public inline fun <T> List<T>.dropLastWhile(predicate: (T) -> Boolean): List<T> {
    var i = size
    while (i > 0 && predicate(this[i - 1])) i--
    return subList(0, i)
}

public inline fun <T, R> Iterable<T>.runningFold(initial: R, operation: (acc: R, T) -> R): List<R> {
    val result = ArrayList<R>()
    result.add(initial)
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
        result.add(accumulator)
    }
    return result
}

public inline fun <T, K, V> Iterable<T>.groupBy(keySelector: (T) -> K, valueTransform: (T) -> V): Map<K, List<V>> {
    val result = LinkedHashMap<K, MutableList<V>>()
    for (e in this) {
        val key = keySelector(e)
        val list = result.getOrPut(key) { ArrayList<V>() }
        list.add(valueTransform(e))
    }
    return result
}

public fun <T> Iterable<T>.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false): List<List<T>> {
    if (size <= 0 || step <= 0) throw IllegalArgumentException("size $size and step $step must be greater than zero.")
    val list = toList()
    val result = ArrayList<List<T>>()
    var index = 0
    while (index < list.size) {
        val end = index + size
        if (end > list.size) {
            if (!partialWindows) break
            result.add(list.subList(index, list.size))
        } else {
            result.add(list.subList(index, end))
        }
        index += step
    }
    return result
}

public fun <T> Iterable<T>.chunked(size: Int): List<List<T>> = windowed(size, size, partialWindows = true)

public fun <T> List<T>.slice(indices: Iterable<Int>): List<T> {
    val result = ArrayList<T>()
    for (i in indices) result.add(get(i))
    return result
}

public fun <T> Iterable<T>.zipWithNext(): List<Pair<T, T>> {
    val iterator = iterator()
    if (!iterator.hasNext()) return emptyList()
    val result = ArrayList<Pair<T, T>>()
    var current = iterator.next()
    while (iterator.hasNext()) {
        val next = iterator.next()
        result.add(current to next)
        current = next
    }
    return result
}

public fun <T, R> Iterable<Pair<T, R>>.unzip(): Pair<List<T>, List<R>> {
    val listT = ArrayList<T>()
    val listR = ArrayList<R>()
    for (pair in this) {
        listT.add(pair.first)
        listR.add(pair.second)
    }
    return listT to listR
}

public fun <T> MutableList<T>.removeLastOrNull(): T? = if (isEmpty()) null else removeAt(lastIndex)

public fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)

public fun <T> MutableCollection<in T>.addAll(elements: Array<out T>): Boolean {
    var changed = false
    for (e in elements) if (add(e)) changed = true
    return changed
}
