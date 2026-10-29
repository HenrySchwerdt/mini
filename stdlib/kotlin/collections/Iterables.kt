/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Subset of kotlin-stdlib generated/_Collections.kt (Iterable receiver; no Sequences, no contracts,
 * no @kotlin.internal.OnlyInputTypes). Semantics and exception messages follow the original.
 */
package kotlin.collections

import kotlin.comparisons.compareValues

// ---- sizes / basic queries ------------------------------------------------------------

public fun <T> Iterable<T>.count(): Int {
    if (this is Collection<*>) return this.size
    var n = 0
    val it = iterator()
    while (it.hasNext()) {
        it.next()
        n++
    }
    return n
}

public inline fun <T> Iterable<T>.count(predicate: (T) -> Boolean): Int {
    var n = 0
    for (element in this) if (predicate(element)) n++
    return n
}

public fun <T> Collection<T>.isNotEmpty(): Boolean = !isEmpty()

public fun <T> Collection<T>?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

public fun <T> Collection<T>?.orEmpty(): Collection<T> = this ?: emptyList()

public fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

public val Collection<*>.indices: IntRange
    get() = IntRange(0, size - 1)

public val <T> List<T>.lastIndex: Int
    get() = size - 1

public fun <@kotlin.internal.OnlyInputTypes T> Iterable<T>.contains(element: T): Boolean {
    if (this is Collection<*>) return (this as Collection<T>).contains(element)
    return indexOf(element) >= 0
}

public fun <@kotlin.internal.OnlyInputTypes T> Iterable<T>.indexOf(element: T): Int {
    if (this is List<*>) return (this as List<T>).indexOf(element)
    var index = 0
    for (item in this) {
        if (element == item) return index
        index++
    }
    return -1
}

public inline fun <T> Iterable<T>.indexOfFirst(predicate: (T) -> Boolean): Int {
    var index = 0
    for (item in this) {
        if (predicate(item)) return index
        index++
    }
    return -1
}

public inline fun <T> Iterable<T>.any(predicate: (T) -> Boolean): Boolean {
    for (element in this) if (predicate(element)) return true
    return false
}

public fun <T> Iterable<T>.any(): Boolean {
    if (this is Collection<*>) return !this.isEmpty()
    return iterator().hasNext()
}

public inline fun <T> Iterable<T>.all(predicate: (T) -> Boolean): Boolean {
    for (element in this) if (!predicate(element)) return false
    return true
}

public fun <T> Iterable<T>.none(): Boolean {
    if (this is Collection<*>) return this.isEmpty()
    return !iterator().hasNext()
}

public inline fun <T> Iterable<T>.none(predicate: (T) -> Boolean): Boolean {
    for (element in this) if (predicate(element)) return false
    return true
}

public inline fun <T> Iterable<T>.forEach(action: (T) -> Unit) {
    for (element in this) action(element)
}

public inline fun <T> Iterable<T>.forEachIndexed(action: (index: Int, T) -> Unit) {
    var index = 0
    for (item in this) action(index++, item)
}

// ---- element access ------------------------------------------------------------------

public fun <T> Iterable<T>.first(): T {
    when (this) {
        is List<*> -> {
            if (this.isEmpty()) mini.internal.throwNoSuchElement("List is empty.")
            return (this as List<T>)[0]
        }
        else -> {
            val iterator = iterator()
            if (!iterator.hasNext()) mini.internal.throwNoSuchElement("Collection is empty.")
            return iterator.next()
        }
    }
}

public inline fun <T> Iterable<T>.first(predicate: (T) -> Boolean): T {
    for (element in this) if (predicate(element)) return element
    mini.internal.throwNoSuchElement("Collection contains no element matching the predicate.")
}

public fun <T> Iterable<T>.firstOrNull(): T? {
    if (this is List<*>) {
        if (this.isEmpty()) return null
        return (this as List<T>)[0]
    }
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    return iterator.next()
}

public inline fun <T> Iterable<T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (element in this) if (predicate(element)) return element
    return null
}

public inline fun <T> Iterable<T>.find(predicate: (T) -> Boolean): T? = firstOrNull(predicate)

public fun <T> Iterable<T>.last(): T {
    if (this is List<*>) {
        if (this.isEmpty()) mini.internal.throwNoSuchElement("List is empty.")
        return (this as List<T>)[this.size - 1]
    }
    val iterator = iterator()
    if (!iterator.hasNext()) mini.internal.throwNoSuchElement("Collection is empty.")
    var last = iterator.next()
    while (iterator.hasNext()) last = iterator.next()
    return last
}

public inline fun <T> Iterable<T>.last(predicate: (T) -> Boolean): T {
    var last: T? = null
    var found = false
    for (element in this) {
        if (predicate(element)) {
            last = element
            found = true
        }
    }
    if (!found) mini.internal.throwNoSuchElement("Collection contains no element matching the predicate.")
    @Suppress("UNCHECKED_CAST")
    return last as T
}

public fun <T> Iterable<T>.lastOrNull(): T? {
    if (this is List<*>) return if (this.isEmpty()) null else (this as List<T>)[this.size - 1]
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var last = iterator.next()
    while (iterator.hasNext()) last = iterator.next()
    return last
}

public inline fun <T> Iterable<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var last: T? = null
    for (element in this) if (predicate(element)) last = element
    return last
}

public fun <T> Iterable<T>.single(): T {
    val iterator = iterator()
    if (!iterator.hasNext()) mini.internal.throwNoSuchElement("Collection is empty.")
    val single = iterator.next()
    if (iterator.hasNext()) mini.internal.throwIllegalArgument("Collection has more than one element.")
    return single
}

public fun <T> Iterable<T>.singleOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    val single = iterator.next()
    if (iterator.hasNext()) return null
    return single
}

public fun <T> Iterable<T>.elementAt(index: Int): T {
    if (this is List<*>) return (this as List<T>)[index]
    var i = 0
    for (element in this) {
        if (index == i++) return element
    }
    mini.internal.throwIndexOutOfBounds("Collection doesn't contain element at index $index.")
}

public fun <T> Iterable<T>.elementAtOrNull(index: Int): T? {
    if (index < 0) return null
    if (this is List<*>) return if (index < this.size) (this as List<T>)[index] else null
    var i = 0
    for (element in this) {
        if (index == i++) return element
    }
    return null
}

public fun <T> List<T>.getOrNull(index: Int): T? = if (index >= 0 && index < size) get(index) else null

public inline fun <T> List<T>.getOrElse(index: Int, defaultValue: (Int) -> T): T =
    if (index >= 0 && index < size) get(index) else defaultValue(index)

// ---- transformations -------------------------------------------------------------------

public inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> {
    val destination = ArrayList<R>(if (this is Collection<*>) this.size else 10)
    for (item in this) destination.add(transform(item))
    return destination
}

public inline fun <T, R> Iterable<T>.mapIndexed(transform: (index: Int, T) -> R): List<R> {
    val destination = ArrayList<R>(if (this is Collection<*>) this.size else 10)
    var index = 0
    for (item in this) destination.add(transform(index++, item))
    return destination
}

public inline fun <T, R : Any> Iterable<T>.mapNotNull(transform: (T) -> R?): List<R> {
    val destination = ArrayList<R>()
    for (element in this) {
        val r = transform(element)
        if (r != null) destination.add(r)
    }
    return destination
}

public inline fun <T, R> Iterable<T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
    val destination = ArrayList<R>()
    for (element in this) destination.addAll(transform(element))
    return destination
}

public fun <T> Iterable<Iterable<T>>.flatten(): List<T> {
    val result = ArrayList<T>()
    for (element in this) result.addAll(element)
    return result
}

public inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T> {
    val destination = ArrayList<T>()
    for (element in this) if (predicate(element)) destination.add(element)
    return destination
}

public inline fun <T> Iterable<T>.filterNot(predicate: (T) -> Boolean): List<T> {
    val destination = ArrayList<T>()
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <T> Iterable<T>.filterIndexed(predicate: (index: Int, T) -> Boolean): List<T> {
    val destination = ArrayList<T>()
    var index = 0
    for (item in this) if (predicate(index++, item)) destination.add(item)
    return destination
}

public fun <T : Any> Iterable<T?>.filterNotNull(): List<T> {
    val destination = ArrayList<T>()
    for (element in this) if (element != null) destination.add(element)
    return destination
}

public inline fun <T> Iterable<T>.partition(predicate: (T) -> Boolean): Pair<List<T>, List<T>> {
    val first = ArrayList<T>()
    val second = ArrayList<T>()
    for (element in this) {
        if (predicate(element)) first.add(element) else second.add(element)
    }
    return Pair(first, second)
}

public fun <T> Iterable<T>.take(n: Int): List<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    if (n == 0) return emptyList()
    val list = ArrayList<T>()
    if (this is Collection<*> && n >= this.size) {
        list.addAll(this as Collection<T>)
        return list
    }
    var count = 0
    for (item in this) {
        list.add(item)
        if (++count == n) break
    }
    return list
}

public fun <T> Iterable<T>.drop(n: Int): List<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    if (n == 0) return this.toList()
    val list = ArrayList<T>()
    var count = 0
    for (item in this) {
        if (count++ >= n) list.add(item)
    }
    return list
}

public inline fun <T> Iterable<T>.takeWhile(predicate: (T) -> Boolean): List<T> {
    val list = ArrayList<T>()
    for (item in this) {
        if (!predicate(item)) break
        list.add(item)
    }
    return list
}

public inline fun <T> Iterable<T>.dropWhile(predicate: (T) -> Boolean): List<T> {
    var yielding = false
    val list = ArrayList<T>()
    for (item in this) {
        if (yielding) {
            list.add(item)
        } else if (!predicate(item)) {
            list.add(item)
            yielding = true
        }
    }
    return list
}

public fun <T> List<T>.takeLast(n: Int): List<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    if (n == 0) return emptyList()
    val size = size
    if (n >= size) return toList()
    val list = ArrayList<T>(n)
    var i = size - n
    while (i < size) {
        list.add(this[i])
        i++
    }
    return list
}

public fun <T> List<T>.dropLast(n: Int): List<T> {
    require(n >= 0) { "Requested element count $n is less than zero." }
    return take(if (size - n > 0) size - n else 0)
}

public fun <T> Iterable<T>.reversed(): List<T> {
    if (this is Collection<*> && this.size <= 1) return this.toList()
    val list = this.toMutableList()
    list.reverse()
    return list
}

public fun <T> MutableList<T>.reverse() {
    var i = 0
    var j = size - 1
    while (i < j) {
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
        i++
        j--
    }
}

public fun <T> Iterable<T>.distinct(): List<T> = this.toMutableSet().toList()

public inline fun <T, K> Iterable<T>.distinctBy(selector: (T) -> K): List<T> {
    val set = HashSet<K>()
    val list = ArrayList<T>()
    for (e in this) {
        val key = selector(e)
        if (set.add(key)) list.add(e)
    }
    return list
}

public operator fun <@kotlin.internal.OnlyInputTypes T> Iterable<T>.plus(element: T): List<T> {
    if (this is Collection<*>) return (this as Collection<T>).plus(element)
    val result = ArrayList<T>()
    result.addAll(this)
    result.add(element)
    return result
}

public operator fun <@kotlin.internal.OnlyInputTypes T> Collection<T>.plus(element: T): List<T> {
    val result = ArrayList<T>(size + 1)
    result.addAll(this)
    result.add(element)
    return result
}

public operator fun <T> Collection<T>.plus(elements: Iterable<T>): List<T> {
    val result = ArrayList<T>(size + (if (elements is Collection<*>) elements.size else 10))
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <T> Iterable<T>.plus(elements: Iterable<T>): List<T> {
    val result = ArrayList<T>()
    result.addAll(this)
    result.addAll(elements)
    return result
}

public operator fun <@kotlin.internal.OnlyInputTypes T> Iterable<T>.minus(element: T): List<T> {
    val result = ArrayList<T>()
    var removed = false
    for (item in this) {
        if (!removed && item == element) {
            removed = true
        } else {
            result.add(item)
        }
    }
    return result
}

public infix fun <T, R> Iterable<T>.zip(other: Iterable<R>): List<Pair<T, R>> {
    val first = iterator()
    val second = other.iterator()
    val list = ArrayList<Pair<T, R>>()
    while (first.hasNext() && second.hasNext()) {
        list.add(Pair(first.next(), second.next()))
    }
    return list
}

public inline fun <T, R, V> Iterable<T>.zip(other: Iterable<R>, transform: (a: T, b: R) -> V): List<V> {
    val first = iterator()
    val second = other.iterator()
    val list = ArrayList<V>()
    while (first.hasNext() && second.hasNext()) {
        list.add(transform(first.next(), second.next()))
    }
    return list
}

public fun <T> Iterable<T>.withIndex(): Iterable<IndexedValue<T>> {
    val list = ArrayList<IndexedValue<T>>()
    var index = 0
    for (item in this) list.add(IndexedValue(index++, item))
    return list
}

internal fun mapCapacity(expectedSize: Int): Int = when {
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < 1073741824 -> ((expectedSize.toDouble() / 0.75) + 1.0).toInt()
    else -> Int.MAX_VALUE
}

public data class IndexedValue<out T>(public val index: Int, public val value: T)

// ---- conversions ---------------------------------------------------------------------

public fun <T> Iterable<T>.toList(): List<T> {
    if (this is Collection<*>) {
        return when (this.size) {
            0 -> emptyList()
            1 -> listOf(if (this is List<*>) (this as List<T>)[0] else iterator().next())
            else -> this.toMutableList()
        }
    }
    return this.toMutableList().optimizeReadOnlyList()
}

internal fun <T> List<T>.optimizeReadOnlyList(): List<T> = when (size) {
    0 -> emptyList()
    1 -> listOf(this[0])
    else -> this
}

public fun <T> Iterable<T>.toMutableList(): MutableList<T> {
    if (this is Collection<*>) return ArrayList(this as Collection<T>)
    val list = ArrayList<T>()
    for (item in this) list.add(item)
    return list
}

public fun <T> Iterable<T>.toSet(): Set<T> {
    if (this is Collection<*>) {
        return when (this.size) {
            0 -> emptySet()
            1 -> setOf(if (this is List<*>) (this as List<T>)[0] else iterator().next())
            else -> toCollection(LinkedHashSet(this.size))
        }
    }
    return toCollection(LinkedHashSet<T>()).optimizeReadOnlySet()
}

internal fun <T> Set<T>.optimizeReadOnlySet(): Set<T> = when (size) {
    0 -> emptySet()
    1 -> setOf(iterator().next())
    else -> this
}

public fun <T> Iterable<T>.toMutableSet(): MutableSet<T> {
    if (this is Collection<*>) return LinkedHashSet(this as Collection<T>)
    return toCollection(LinkedHashSet<T>())
}

public fun <T> Iterable<T>.toHashSet(): HashSet<T> =
    toCollection(HashSet<T>(mapCapacity(if (this is Collection<*>) this.size else 12)))

public fun <T, C : MutableCollection<in T>> Iterable<T>.toCollection(destination: C): C {
    for (item in this) destination.add(item)
    return destination
}

public fun <T> Iterable<T>.asIterable(): Iterable<T> = this

// ---- aggregation -----------------------------------------------------------------------

public inline fun <T, R> Iterable<T>.fold(initial: R, operation: (acc: R, T) -> R): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}

public inline fun <T, R> Iterable<T>.foldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): R {
    var index = 0
    var accumulator = initial
    for (element in this) accumulator = operation(index++, accumulator, element)
    return accumulator
}

public inline fun <S, T : S> Iterable<T>.reduce(operation: (acc: S, T) -> S): S {
    val iterator = this.iterator()
    if (!iterator.hasNext()) mini.internal.throwUnsupportedOperation("Empty collection can't be reduced.")
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) accumulator = operation(accumulator, iterator.next())
    return accumulator
}

@kotlin.jvm.JvmName("sumOfInt")
public fun Iterable<Int>.sum(): Int {
    var sum = 0
    for (element in this) sum += element
    return sum
}

@kotlin.jvm.JvmName("sumOfLong")
public fun Iterable<Long>.sum(): Long {
    var sum = 0L
    for (element in this) sum += element
    return sum
}

@kotlin.jvm.JvmName("sumOfDouble")
public fun Iterable<Double>.sum(): Double {
    var sum = 0.0
    for (element in this) sum += element
    return sum
}

@kotlin.jvm.JvmName("sumOfIntSelector")
@OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Int): Int {
    var sum = 0
    for (element in this) sum += selector(element)
    return sum
}

@kotlin.jvm.JvmName("sumOfLongSelector")
@OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) sum += selector(element)
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleSelector")
@OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Double): Double {
    var sum = 0.0
    for (element in this) sum += selector(element)
    return sum
}

public fun <T : Comparable<T>> Iterable<T>.maxOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var max = iterator.next()
    while (iterator.hasNext()) {
        val e = iterator.next()
        if (max < e) max = e
    }
    return max
}

public fun <T : Comparable<T>> Iterable<T>.minOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var min = iterator.next()
    while (iterator.hasNext()) {
        val e = iterator.next()
        if (min > e) min = e
    }
    return min
}

public fun <T : Comparable<T>> Iterable<T>.max(): T = maxOrNull() ?: mini.internal.throwNoSuchElement(null)

public fun <T : Comparable<T>> Iterable<T>.min(): T = minOrNull() ?: mini.internal.throwNoSuchElement(null)

public inline fun <T, R : Comparable<R>> Iterable<T>.maxByOrNull(selector: (T) -> R): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var maxElem = iterator.next()
    if (!iterator.hasNext()) return maxElem
    var maxValue = selector(maxElem)
    do {
        val e = iterator.next()
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    } while (iterator.hasNext())
    return maxElem
}

public inline fun <T, R : Comparable<R>> Iterable<T>.minByOrNull(selector: (T) -> R): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var minElem = iterator.next()
    if (!iterator.hasNext()) return minElem
    var minValue = selector(minElem)
    do {
        val e = iterator.next()
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    } while (iterator.hasNext())
    return minElem
}

public inline fun <T, R : Comparable<R>> Iterable<T>.maxBy(selector: (T) -> R): T =
    maxByOrNull(selector) ?: mini.internal.throwNoSuchElement(null)

public inline fun <T, R : Comparable<R>> Iterable<T>.minBy(selector: (T) -> R): T =
    minByOrNull(selector) ?: mini.internal.throwNoSuchElement(null)

public fun <T> Iterable<T>.average(): Double where T : Number {
    var sum = 0.0
    var count = 0
    for (element in this) {
        sum += element.toDouble()
        count++
    }
    return if (count == 0) Double.NaN else sum / count
}

// ---- associate / group ------------------------------------------------------------------

public inline fun <T, K, V> Iterable<T>.associate(transform: (T) -> Pair<K, V>): Map<K, V> {
    val destination = LinkedHashMap<K, V>()
    for (element in this) {
        val pair = transform(element)
        destination.put(pair.first, pair.second)
    }
    return destination
}

public inline fun <T, K> Iterable<T>.associateBy(keySelector: (T) -> K): Map<K, T> {
    val destination = LinkedHashMap<K, T>()
    for (element in this) destination.put(keySelector(element), element)
    return destination
}

public inline fun <T, K, V> Iterable<T>.associateBy(keySelector: (T) -> K, valueTransform: (T) -> V): Map<K, V> {
    val destination = LinkedHashMap<K, V>()
    for (element in this) destination.put(keySelector(element), valueTransform(element))
    return destination
}

public inline fun <K, V> Iterable<K>.associateWith(valueSelector: (K) -> V): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (element in this) result.put(element, valueSelector(element))
    return result
}

public inline fun <T, K> Iterable<T>.groupBy(keySelector: (T) -> K): Map<K, List<T>> {
    val destination = LinkedHashMap<K, MutableList<T>>()
    for (element in this) {
        val key = keySelector(element)
        var list = destination.get(key)
        if (list == null) {
            list = ArrayList<T>()
            destination.put(key, list)
        }
        list.add(element)
    }
    @Suppress("UNCHECKED_CAST")
    return destination as Map<K, List<T>>
}

// ---- strings -------------------------------------------------------------------------

public fun <T> Iterable<T>.joinTo(
    buffer: StringBuilder,
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: ((T) -> CharSequence)? = null,
): StringBuilder {
    buffer.append(prefix)
    var count = 0
    for (element in this) {
        if (++count > 1) buffer.append(separator)
        if (limit < 0 || count <= limit) {
            if (transform != null) {
                buffer.append(transform(element))
            } else {
                buffer.append(element.toString())
            }
        } else {
            break
        }
    }
    if (limit >= 0 && count > limit) buffer.append(truncated)
    buffer.append(postfix)
    return buffer
}

public fun <T> Iterable<T>.joinToString(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: ((T) -> CharSequence)? = null,
): String = joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString()

// ---- sorting ------------------------------------------------------------------------------

public fun <T : Comparable<T>> Iterable<T>.sorted(): List<T> {
    val list = this.toMutableList()
    list.sortWith(Comparator { a, b -> a.compareTo(b) })
    return list
}

public fun <T : Comparable<T>> Iterable<T>.sortedDescending(): List<T> {
    val list = this.toMutableList()
    list.sortWith(Comparator { a, b -> b.compareTo(a) })
    return list
}

public inline fun <T, R : Comparable<R>> Iterable<T>.sortedBy(crossinline selector: (T) -> R?): List<T> {
    val list = this.toMutableList()
    list.sortWith(Comparator { a, b -> compareValues(selector(a), selector(b)) })
    return list
}

public inline fun <T, R : Comparable<R>> Iterable<T>.sortedByDescending(crossinline selector: (T) -> R?): List<T> {
    val list = this.toMutableList()
    list.sortWith(Comparator { a, b -> compareValues(selector(b), selector(a)) })
    return list
}

public fun <T> Iterable<T>.sortedWith(comparator: Comparator<in T>): List<T> {
    val list = this.toMutableList()
    list.sortWith(comparator)
    return list
}

public fun <T : Comparable<T>> MutableList<T>.sort() {
    sortWith(Comparator { a, b -> a.compareTo(b) })
}

public inline fun <T, R : Comparable<R>> MutableList<T>.sortBy(crossinline selector: (T) -> R?) {
    sortWith(Comparator { a, b -> compareValues(selector(a), selector(b)) })
}

/** Stable merge sort (java.util.List.sort is stable too, so results agree with the JVM). */
public fun <T> MutableList<T>.sortWith(comparator: Comparator<in T>) {
    if (size < 2) return
    val src = ArrayList<T>(this)
    val tmp = ArrayList<T>(this)
    mergeSort(src, tmp, 0, size, comparator)
    var i = 0
    while (i < size) {
        this[i] = src[i]
        i++
    }
}

private fun <T> mergeSort(a: ArrayList<T>, tmp: ArrayList<T>, from: Int, to: Int, c: Comparator<in T>) {
    if (to - from < 2) return
    val mid = (from + to) ushr 1
    mergeSort(a, tmp, from, mid, c)
    mergeSort(a, tmp, mid, to, c)
    var i = from
    var j = mid
    var k = from
    while (i < mid && j < to) {
        if (c.compare(a[j], a[i]) < 0) {
            tmp[k++] = a[j++]
        } else {
            tmp[k++] = a[i++]
        }
    }
    while (i < mid) tmp[k++] = a[i++]
    while (j < to) tmp[k++] = a[j++]
    k = from
    while (k < to) {
        a[k] = tmp[k]
        k++
    }
}

// ---- mutable collection helpers ---------------------------------------------------------------

public operator fun <T> MutableCollection<in T>.plusAssign(element: T) {
    this.add(element)
}

public operator fun <T> MutableCollection<in T>.plusAssign(elements: Iterable<T>) {
    this.addAll(elements)
}

public operator fun <T> MutableCollection<in T>.minusAssign(element: T) {
    this.remove(element)
}

public fun <T> MutableCollection<in T>.addAll(elements: Iterable<T>): Boolean {
    var result = false
    for (item in elements) if (add(item)) result = true
    return result
}

public fun <T> MutableCollection<in T>.removeAll(elements: Iterable<T>): Boolean {
    var result = false
    for (item in elements) if (remove(item)) result = true
    return result
}

public inline fun <T> MutableIterable<T>.removeAll(predicate: (T) -> Boolean): Boolean {
    var result = false
    val iterator = this.iterator()
    while (iterator.hasNext()) {
        if (predicate(iterator.next())) {
            iterator.remove()
            result = true
        }
    }
    return result
}

public inline fun <T> MutableIterable<T>.retainAll(predicate: (T) -> Boolean): Boolean {
    var result = false
    val iterator = this.iterator()
    while (iterator.hasNext()) {
        if (!predicate(iterator.next())) {
            iterator.remove()
            result = true
        }
    }
    return result
}

public fun <T> MutableList<T>.removeFirst(): T {
    if (isEmpty()) mini.internal.throwNoSuchElement("List is empty.")
    return removeAt(0)
}

public fun <T> MutableList<T>.removeLast(): T {
    if (isEmpty()) mini.internal.throwNoSuchElement("List is empty.")
    return removeAt(lastIndex)
}
