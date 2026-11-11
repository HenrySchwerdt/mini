/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * The long tail of the kotlin.collections functions (ported from kotlin-stdlib _UArrays.kt).
 */
package kotlin.collections

import kotlin.random.Random


public inline fun UIntArray.dropLastWhile(predicate: (UInt) -> Boolean): List<UInt> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return take(index + 1)
        }
    }
    return emptyList()
}

public inline fun ULongArray.dropLastWhile(predicate: (ULong) -> Boolean): List<ULong> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return take(index + 1)
        }
    }
    return emptyList()
}

public inline fun UByteArray.dropLastWhile(predicate: (UByte) -> Boolean): List<UByte> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return take(index + 1)
        }
    }
    return emptyList()
}

public inline fun UShortArray.dropLastWhile(predicate: (UShort) -> Boolean): List<UShort> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return take(index + 1)
        }
    }
    return emptyList()
}

public inline fun <C : MutableCollection<in UInt>> UIntArray.filterIndexedTo(destination: C, predicate: (index: Int, UInt) -> Boolean): C {
    forEachIndexed { index, element ->
        if (predicate(index, element)) destination.add(element)
    }
    return destination
}

public inline fun <C : MutableCollection<in ULong>> ULongArray.filterIndexedTo(destination: C, predicate: (index: Int, ULong) -> Boolean): C {
    forEachIndexed { index, element ->
        if (predicate(index, element)) destination.add(element)
    }
    return destination
}

public inline fun <C : MutableCollection<in UByte>> UByteArray.filterIndexedTo(destination: C, predicate: (index: Int, UByte) -> Boolean): C {
    forEachIndexed { index, element ->
        if (predicate(index, element)) destination.add(element)
    }
    return destination
}

public inline fun <C : MutableCollection<in UShort>> UShortArray.filterIndexedTo(destination: C, predicate: (index: Int, UShort) -> Boolean): C {
    forEachIndexed { index, element ->
        if (predicate(index, element)) destination.add(element)
    }
    return destination
}

public inline fun <C : MutableCollection<in UInt>> UIntArray.filterNotTo(destination: C, predicate: (UInt) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <C : MutableCollection<in ULong>> ULongArray.filterNotTo(destination: C, predicate: (ULong) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <C : MutableCollection<in UByte>> UByteArray.filterNotTo(destination: C, predicate: (UByte) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <C : MutableCollection<in UShort>> UShortArray.filterNotTo(destination: C, predicate: (UShort) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun UIntArray.takeLastWhile(predicate: (UInt) -> Boolean): List<UInt> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return drop(index + 1)
        }
    }
    return toList()
}

public inline fun ULongArray.takeLastWhile(predicate: (ULong) -> Boolean): List<ULong> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return drop(index + 1)
        }
    }
    return toList()
}

public inline fun UByteArray.takeLastWhile(predicate: (UByte) -> Boolean): List<UByte> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return drop(index + 1)
        }
    }
    return toList()
}

public inline fun UShortArray.takeLastWhile(predicate: (UShort) -> Boolean): List<UShort> {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) {
            return drop(index + 1)
        }
    }
    return toList()
}

public fun UIntArray.shuffle(): Unit {
    shuffle(Random)
}

public fun ULongArray.shuffle(): Unit {
    shuffle(Random)
}

public fun UByteArray.shuffle(): Unit {
    shuffle(Random)
}

public fun UShortArray.shuffle(): Unit {
    shuffle(Random)
}

public fun UIntArray.shuffle(random: Random): Unit {
    for (i in lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val copy = this[i]
        this[i] = this[j]
        this[j] = copy
    }
}

public fun ULongArray.shuffle(random: Random): Unit {
    for (i in lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val copy = this[i]
        this[i] = this[j]
        this[j] = copy
    }
}

public fun UByteArray.shuffle(random: Random): Unit {
    for (i in lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val copy = this[i]
        this[i] = this[j]
        this[j] = copy
    }
}

public fun UShortArray.shuffle(random: Random): Unit {
    for (i in lastIndex downTo 1) {
        val j = random.nextInt(i + 1)
        val copy = this[i]
        this[i] = this[j]
        this[j] = copy
    }
}

public inline fun <V, M : MutableMap<in UInt, in V>> UIntArray.associateWithTo(destination: M, valueSelector: (UInt) -> V): M {
    for (element in this) {
        destination.put(element, valueSelector(element))
    }
    return destination
}

public inline fun <V, M : MutableMap<in ULong, in V>> ULongArray.associateWithTo(destination: M, valueSelector: (ULong) -> V): M {
    for (element in this) {
        destination.put(element, valueSelector(element))
    }
    return destination
}

public inline fun <V, M : MutableMap<in UByte, in V>> UByteArray.associateWithTo(destination: M, valueSelector: (UByte) -> V): M {
    for (element in this) {
        destination.put(element, valueSelector(element))
    }
    return destination
}

public inline fun <V, M : MutableMap<in UShort, in V>> UShortArray.associateWithTo(destination: M, valueSelector: (UShort) -> V): M {
    for (element in this) {
        destination.put(element, valueSelector(element))
    }
    return destination
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UIntArray.flatMapIndexed(transform: (index: Int, UInt) -> Iterable<R>): List<R> {
    return flatMapIndexedTo(ArrayList<R>(), transform)
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> ULongArray.flatMapIndexed(transform: (index: Int, ULong) -> Iterable<R>): List<R> {
    return flatMapIndexedTo(ArrayList<R>(), transform)
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UByteArray.flatMapIndexed(transform: (index: Int, UByte) -> Iterable<R>): List<R> {
    return flatMapIndexedTo(ArrayList<R>(), transform)
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UShortArray.flatMapIndexed(transform: (index: Int, UShort) -> Iterable<R>): List<R> {
    return flatMapIndexedTo(ArrayList<R>(), transform)
}

@OverloadResolutionByLambdaReturnType
public inline fun <R, C : MutableCollection<in R>> UIntArray.flatMapIndexedTo(destination: C, transform: (index: Int, UInt) -> Iterable<R>): C {
    var index = 0
    for (element in this) {
        val list = transform(index++, element)
        destination.addAll(list)
    }
    return destination
}

@OverloadResolutionByLambdaReturnType
public inline fun <R, C : MutableCollection<in R>> ULongArray.flatMapIndexedTo(destination: C, transform: (index: Int, ULong) -> Iterable<R>): C {
    var index = 0
    for (element in this) {
        val list = transform(index++, element)
        destination.addAll(list)
    }
    return destination
}

@OverloadResolutionByLambdaReturnType
public inline fun <R, C : MutableCollection<in R>> UByteArray.flatMapIndexedTo(destination: C, transform: (index: Int, UByte) -> Iterable<R>): C {
    var index = 0
    for (element in this) {
        val list = transform(index++, element)
        destination.addAll(list)
    }
    return destination
}

@OverloadResolutionByLambdaReturnType
public inline fun <R, C : MutableCollection<in R>> UShortArray.flatMapIndexedTo(destination: C, transform: (index: Int, UShort) -> Iterable<R>): C {
    var index = 0
    for (element in this) {
        val list = transform(index++, element)
        destination.addAll(list)
    }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UIntArray.flatMapTo(destination: C, transform: (UInt) -> Iterable<R>): C {
    for (element in this) {
        val list = transform(element)
        destination.addAll(list)
    }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> ULongArray.flatMapTo(destination: C, transform: (ULong) -> Iterable<R>): C {
    for (element in this) {
        val list = transform(element)
        destination.addAll(list)
    }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UByteArray.flatMapTo(destination: C, transform: (UByte) -> Iterable<R>): C {
    for (element in this) {
        val list = transform(element)
        destination.addAll(list)
    }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UShortArray.flatMapTo(destination: C, transform: (UShort) -> Iterable<R>): C {
    for (element in this) {
        val list = transform(element)
        destination.addAll(list)
    }
    return destination
}

public inline fun <K, V> UIntArray.groupBy(keySelector: (UInt) -> K, valueTransform: (UInt) -> V): Map<K, List<V>> {
    return groupByTo(LinkedHashMap<K, MutableList<V>>(), keySelector, valueTransform)
}

public inline fun <K, V> ULongArray.groupBy(keySelector: (ULong) -> K, valueTransform: (ULong) -> V): Map<K, List<V>> {
    return groupByTo(LinkedHashMap<K, MutableList<V>>(), keySelector, valueTransform)
}

public inline fun <K, V> UByteArray.groupBy(keySelector: (UByte) -> K, valueTransform: (UByte) -> V): Map<K, List<V>> {
    return groupByTo(LinkedHashMap<K, MutableList<V>>(), keySelector, valueTransform)
}

public inline fun <K, V> UShortArray.groupBy(keySelector: (UShort) -> K, valueTransform: (UShort) -> V): Map<K, List<V>> {
    return groupByTo(LinkedHashMap<K, MutableList<V>>(), keySelector, valueTransform)
}

public inline fun <K, M : MutableMap<in K, MutableList<UInt>>> UIntArray.groupByTo(destination: M, keySelector: (UInt) -> K): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<UInt>() }
        list.add(element)
    }
    return destination
}

public inline fun <K, M : MutableMap<in K, MutableList<ULong>>> ULongArray.groupByTo(destination: M, keySelector: (ULong) -> K): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<ULong>() }
        list.add(element)
    }
    return destination
}

public inline fun <K, M : MutableMap<in K, MutableList<UByte>>> UByteArray.groupByTo(destination: M, keySelector: (UByte) -> K): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<UByte>() }
        list.add(element)
    }
    return destination
}

public inline fun <K, M : MutableMap<in K, MutableList<UShort>>> UShortArray.groupByTo(destination: M, keySelector: (UShort) -> K): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<UShort>() }
        list.add(element)
    }
    return destination
}

public inline fun <K, V, M : MutableMap<in K, MutableList<V>>> UIntArray.groupByTo(destination: M, keySelector: (UInt) -> K, valueTransform: (UInt) -> V): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<V>() }
        list.add(valueTransform(element))
    }
    return destination
}

public inline fun <K, V, M : MutableMap<in K, MutableList<V>>> ULongArray.groupByTo(destination: M, keySelector: (ULong) -> K, valueTransform: (ULong) -> V): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<V>() }
        list.add(valueTransform(element))
    }
    return destination
}

public inline fun <K, V, M : MutableMap<in K, MutableList<V>>> UByteArray.groupByTo(destination: M, keySelector: (UByte) -> K, valueTransform: (UByte) -> V): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<V>() }
        list.add(valueTransform(element))
    }
    return destination
}

public inline fun <K, V, M : MutableMap<in K, MutableList<V>>> UShortArray.groupByTo(destination: M, keySelector: (UShort) -> K, valueTransform: (UShort) -> V): M {
    for (element in this) {
        val key = keySelector(element)
        val list = destination.getOrPut(key) { ArrayList<V>() }
        list.add(valueTransform(element))
    }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UIntArray.mapIndexedTo(destination: C, transform: (index: Int, UInt) -> R): C {
    var index = 0
    for (item in this)
        destination.add(transform(index++, item))
    return destination
}

public inline fun <R, C : MutableCollection<in R>> ULongArray.mapIndexedTo(destination: C, transform: (index: Int, ULong) -> R): C {
    var index = 0
    for (item in this)
        destination.add(transform(index++, item))
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UByteArray.mapIndexedTo(destination: C, transform: (index: Int, UByte) -> R): C {
    var index = 0
    for (item in this)
        destination.add(transform(index++, item))
    return destination
}

public inline fun <R, C : MutableCollection<in R>> UShortArray.mapIndexedTo(destination: C, transform: (index: Int, UShort) -> R): C {
    var index = 0
    for (item in this)
        destination.add(transform(index++, item))
    return destination
}

public inline fun <R> UIntArray.foldRight(initial: R, operation: (UInt, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun <R> ULongArray.foldRight(initial: R, operation: (ULong, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun <R> UByteArray.foldRight(initial: R, operation: (UByte, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun <R> UShortArray.foldRight(initial: R, operation: (UShort, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun <R> UIntArray.foldRightIndexed(initial: R, operation: (index: Int, UInt, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun <R> ULongArray.foldRightIndexed(initial: R, operation: (index: Int, ULong, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun <R> UByteArray.foldRightIndexed(initial: R, operation: (index: Int, UByte, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun <R> UShortArray.foldRightIndexed(initial: R, operation: (index: Int, UShort, acc: R) -> R): R {
    var index = lastIndex
    var accumulator = initial
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

@kotlin.jvm.JvmName("maxByOrThrow-U")
public inline fun <R : Comparable<R>> UIntArray.maxBy(selector: (UInt) -> R): UInt {
    if (isEmpty()) throw NoSuchElementException()
    var maxElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return maxElem
    var maxValue = selector(maxElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    }
    return maxElem
}

@kotlin.jvm.JvmName("maxByOrThrow-U")
public inline fun <R : Comparable<R>> ULongArray.maxBy(selector: (ULong) -> R): ULong {
    if (isEmpty()) throw NoSuchElementException()
    var maxElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return maxElem
    var maxValue = selector(maxElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    }
    return maxElem
}

@kotlin.jvm.JvmName("maxByOrThrow-U")
public inline fun <R : Comparable<R>> UByteArray.maxBy(selector: (UByte) -> R): UByte {
    if (isEmpty()) throw NoSuchElementException()
    var maxElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return maxElem
    var maxValue = selector(maxElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    }
    return maxElem
}

@kotlin.jvm.JvmName("maxByOrThrow-U")
public inline fun <R : Comparable<R>> UShortArray.maxBy(selector: (UShort) -> R): UShort {
    if (isEmpty()) throw NoSuchElementException()
    var maxElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return maxElem
    var maxValue = selector(maxElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (maxValue < v) {
            maxElem = e
            maxValue = v
        }
    }
    return maxElem
}

public inline fun <R : Comparable<R>> UIntArray.maxOfOrNull(selector: (UInt) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> ULongArray.maxOfOrNull(selector: (ULong) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> UByteArray.maxOfOrNull(selector: (UByte) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> UShortArray.maxOfOrNull(selector: (UShort) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UIntArray.maxOfWith(comparator: Comparator<in R>, selector: (UInt) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> ULongArray.maxOfWith(comparator: Comparator<in R>, selector: (ULong) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UByteArray.maxOfWith(comparator: Comparator<in R>, selector: (UByte) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UShortArray.maxOfWith(comparator: Comparator<in R>, selector: (UShort) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UIntArray.maxOfWithOrNull(comparator: Comparator<in R>, selector: (UInt) -> R): R? {
    if (isEmpty()) return null
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> ULongArray.maxOfWithOrNull(comparator: Comparator<in R>, selector: (ULong) -> R): R? {
    if (isEmpty()) return null
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UByteArray.maxOfWithOrNull(comparator: Comparator<in R>, selector: (UByte) -> R): R? {
    if (isEmpty()) return null
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UShortArray.maxOfWithOrNull(comparator: Comparator<in R>, selector: (UShort) -> R): R? {
    if (isEmpty()) return null
    var maxValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(maxValue, v) < 0) {
            maxValue = v
        }
    }
    return maxValue
}

@kotlin.jvm.JvmName("maxWithOrThrow-U")
public fun UIntArray.maxWith(comparator: Comparator<in UInt>): UInt {
    if (isEmpty()) throw NoSuchElementException()
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

@kotlin.jvm.JvmName("maxWithOrThrow-U")
public fun ULongArray.maxWith(comparator: Comparator<in ULong>): ULong {
    if (isEmpty()) throw NoSuchElementException()
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

@kotlin.jvm.JvmName("maxWithOrThrow-U")
public fun UByteArray.maxWith(comparator: Comparator<in UByte>): UByte {
    if (isEmpty()) throw NoSuchElementException()
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

@kotlin.jvm.JvmName("maxWithOrThrow-U")
public fun UShortArray.maxWith(comparator: Comparator<in UShort>): UShort {
    if (isEmpty()) throw NoSuchElementException()
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

public fun UIntArray.maxWithOrNull(comparator: Comparator<in UInt>): UInt? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

public fun ULongArray.maxWithOrNull(comparator: Comparator<in ULong>): ULong? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

public fun UByteArray.maxWithOrNull(comparator: Comparator<in UByte>): UByte? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

public fun UShortArray.maxWithOrNull(comparator: Comparator<in UShort>): UShort? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

@kotlin.jvm.JvmName("minByOrThrow-U")
public inline fun <R : Comparable<R>> UIntArray.minBy(selector: (UInt) -> R): UInt {
    if (isEmpty()) throw NoSuchElementException()
    var minElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return minElem
    var minValue = selector(minElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    }
    return minElem
}

@kotlin.jvm.JvmName("minByOrThrow-U")
public inline fun <R : Comparable<R>> ULongArray.minBy(selector: (ULong) -> R): ULong {
    if (isEmpty()) throw NoSuchElementException()
    var minElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return minElem
    var minValue = selector(minElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    }
    return minElem
}

@kotlin.jvm.JvmName("minByOrThrow-U")
public inline fun <R : Comparable<R>> UByteArray.minBy(selector: (UByte) -> R): UByte {
    if (isEmpty()) throw NoSuchElementException()
    var minElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return minElem
    var minValue = selector(minElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    }
    return minElem
}

@kotlin.jvm.JvmName("minByOrThrow-U")
public inline fun <R : Comparable<R>> UShortArray.minBy(selector: (UShort) -> R): UShort {
    if (isEmpty()) throw NoSuchElementException()
    var minElem = this[0]
    val lastIndex = this.lastIndex
    if (lastIndex == 0) return minElem
    var minValue = selector(minElem)
    for (i in 1..lastIndex) {
        val e = this[i]
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
        }
    }
    return minElem
}

public inline fun <R : Comparable<R>> UIntArray.minOfOrNull(selector: (UInt) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> ULongArray.minOfOrNull(selector: (ULong) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> UByteArray.minOfOrNull(selector: (UByte) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}

public inline fun <R : Comparable<R>> UShortArray.minOfOrNull(selector: (UShort) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UIntArray.minOfWith(comparator: Comparator<in R>, selector: (UInt) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> ULongArray.minOfWith(comparator: Comparator<in R>, selector: (ULong) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UByteArray.minOfWith(comparator: Comparator<in R>, selector: (UByte) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UShortArray.minOfWith(comparator: Comparator<in R>, selector: (UShort) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UIntArray.minOfWithOrNull(comparator: Comparator<in R>, selector: (UInt) -> R): R? {
    if (isEmpty()) return null
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> ULongArray.minOfWithOrNull(comparator: Comparator<in R>, selector: (ULong) -> R): R? {
    if (isEmpty()) return null
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UByteArray.minOfWithOrNull(comparator: Comparator<in R>, selector: (UByte) -> R): R? {
    if (isEmpty()) return null
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@OverloadResolutionByLambdaReturnType
public inline fun <R> UShortArray.minOfWithOrNull(comparator: Comparator<in R>, selector: (UShort) -> R): R? {
    if (isEmpty()) return null
    var minValue = selector(this[0])
    for (i in 1..lastIndex) {
        val v = selector(this[i])
        if (comparator.compare(minValue, v) > 0) {
            minValue = v
        }
    }
    return minValue
}

@kotlin.jvm.JvmName("minWithOrThrow-U")
public fun UIntArray.minWith(comparator: Comparator<in UInt>): UInt {
    if (isEmpty()) throw NoSuchElementException()
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

@kotlin.jvm.JvmName("minWithOrThrow-U")
public fun ULongArray.minWith(comparator: Comparator<in ULong>): ULong {
    if (isEmpty()) throw NoSuchElementException()
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

@kotlin.jvm.JvmName("minWithOrThrow-U")
public fun UByteArray.minWith(comparator: Comparator<in UByte>): UByte {
    if (isEmpty()) throw NoSuchElementException()
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

@kotlin.jvm.JvmName("minWithOrThrow-U")
public fun UShortArray.minWith(comparator: Comparator<in UShort>): UShort {
    if (isEmpty()) throw NoSuchElementException()
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public fun UIntArray.minWithOrNull(comparator: Comparator<in UInt>): UInt? {
    if (isEmpty()) return null
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public fun ULongArray.minWithOrNull(comparator: Comparator<in ULong>): ULong? {
    if (isEmpty()) return null
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public fun UByteArray.minWithOrNull(comparator: Comparator<in UByte>): UByte? {
    if (isEmpty()) return null
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public fun UShortArray.minWithOrNull(comparator: Comparator<in UShort>): UShort? {
    if (isEmpty()) return null
    var min = this[0]
    for (i in 1..lastIndex) {
        val e = this[i]
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public inline fun UIntArray.onEach(action: (UInt) -> Unit): UIntArray {
    return apply { for (element in this) action(element) }
}

public inline fun ULongArray.onEach(action: (ULong) -> Unit): ULongArray {
    return apply { for (element in this) action(element) }
}

public inline fun UByteArray.onEach(action: (UByte) -> Unit): UByteArray {
    return apply { for (element in this) action(element) }
}

public inline fun UShortArray.onEach(action: (UShort) -> Unit): UShortArray {
    return apply { for (element in this) action(element) }
}

public inline fun UIntArray.onEachIndexed(action: (index: Int, UInt) -> Unit): UIntArray {
    return apply { forEachIndexed(action) }
}

public inline fun ULongArray.onEachIndexed(action: (index: Int, ULong) -> Unit): ULongArray {
    return apply { forEachIndexed(action) }
}

public inline fun UByteArray.onEachIndexed(action: (index: Int, UByte) -> Unit): UByteArray {
    return apply { forEachIndexed(action) }
}

public inline fun UShortArray.onEachIndexed(action: (index: Int, UShort) -> Unit): UShortArray {
    return apply { forEachIndexed(action) }
}

public inline fun UIntArray.reduceIndexedOrNull(operation: (index: Int, acc: UInt, UInt) -> UInt): UInt? {
    if (isEmpty())
        return null
    var accumulator = this[0]
    for (index in 1..lastIndex) {
        accumulator = operation(index, accumulator, this[index])
    }
    return accumulator
}

public inline fun ULongArray.reduceIndexedOrNull(operation: (index: Int, acc: ULong, ULong) -> ULong): ULong? {
    if (isEmpty())
        return null
    var accumulator = this[0]
    for (index in 1..lastIndex) {
        accumulator = operation(index, accumulator, this[index])
    }
    return accumulator
}

public inline fun UByteArray.reduceIndexedOrNull(operation: (index: Int, acc: UByte, UByte) -> UByte): UByte? {
    if (isEmpty())
        return null
    var accumulator = this[0]
    for (index in 1..lastIndex) {
        accumulator = operation(index, accumulator, this[index])
    }
    return accumulator
}

public inline fun UShortArray.reduceIndexedOrNull(operation: (index: Int, acc: UShort, UShort) -> UShort): UShort? {
    if (isEmpty())
        return null
    var accumulator = this[0]
    for (index in 1..lastIndex) {
        accumulator = operation(index, accumulator, this[index])
    }
    return accumulator
}

public inline fun UIntArray.reduceRight(operation: (UInt, acc: UInt) -> UInt): UInt {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun ULongArray.reduceRight(operation: (ULong, acc: ULong) -> ULong): ULong {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun UByteArray.reduceRight(operation: (UByte, acc: UByte) -> UByte): UByte {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun UShortArray.reduceRight(operation: (UShort, acc: UShort) -> UShort): UShort {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun UIntArray.reduceRightIndexed(operation: (index: Int, UInt, acc: UInt) -> UInt): UInt {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun ULongArray.reduceRightIndexed(operation: (index: Int, ULong, acc: ULong) -> ULong): ULong {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UByteArray.reduceRightIndexed(operation: (index: Int, UByte, acc: UByte) -> UByte): UByte {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UShortArray.reduceRightIndexed(operation: (index: Int, UShort, acc: UShort) -> UShort): UShort {
    var index = lastIndex
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UIntArray.reduceRightIndexedOrNull(operation: (index: Int, UInt, acc: UInt) -> UInt): UInt? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun ULongArray.reduceRightIndexedOrNull(operation: (index: Int, ULong, acc: ULong) -> ULong): ULong? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UByteArray.reduceRightIndexedOrNull(operation: (index: Int, UByte, acc: UByte) -> UByte): UByte? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UShortArray.reduceRightIndexedOrNull(operation: (index: Int, UShort, acc: UShort) -> UShort): UShort? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(index, get(index), accumulator)
        --index
    }
    return accumulator
}

public inline fun UIntArray.reduceRightOrNull(operation: (UInt, acc: UInt) -> UInt): UInt? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun ULongArray.reduceRightOrNull(operation: (ULong, acc: ULong) -> ULong): ULong? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun UByteArray.reduceRightOrNull(operation: (UByte, acc: UByte) -> UByte): UByte? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun UShortArray.reduceRightOrNull(operation: (UShort, acc: UShort) -> UShort): UShort? {
    var index = lastIndex
    if (index < 0) return null
    var accumulator = get(index--)
    while (index >= 0) {
        accumulator = operation(get(index--), accumulator)
    }
    return accumulator
}

public inline fun <R> UIntArray.runningFold(initial: R, operation: (acc: R, UInt) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
        result.add(accumulator)
    }
    return result
}

public inline fun <R> ULongArray.runningFold(initial: R, operation: (acc: R, ULong) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UByteArray.runningFold(initial: R, operation: (acc: R, UByte) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UShortArray.runningFold(initial: R, operation: (acc: R, UShort) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UIntArray.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, UInt) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (index in indices) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun <R> ULongArray.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, ULong) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (index in indices) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UByteArray.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, UByte) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (index in indices) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UShortArray.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, UShort) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1).apply { add(initial) }
    var accumulator = initial
    for (index in indices) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UIntArray.runningReduce(operation: (acc: UInt, UInt) -> UInt): List<UInt> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UInt>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun ULongArray.runningReduce(operation: (acc: ULong, ULong) -> ULong): List<ULong> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<ULong>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UByteArray.runningReduce(operation: (acc: UByte, UByte) -> UByte): List<UByte> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UByte>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UShortArray.runningReduce(operation: (acc: UShort, UShort) -> UShort): List<UShort> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UShort>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UIntArray.runningReduceIndexed(operation: (index: Int, acc: UInt, UInt) -> UInt): List<UInt> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UInt>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun ULongArray.runningReduceIndexed(operation: (index: Int, acc: ULong, ULong) -> ULong): List<ULong> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<ULong>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UByteArray.runningReduceIndexed(operation: (index: Int, acc: UByte, UByte) -> UByte): List<UByte> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UByte>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun UShortArray.runningReduceIndexed(operation: (index: Int, acc: UShort, UShort) -> UShort): List<UShort> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<UShort>(size).apply { add(accumulator) }
    for (index in 1 until size) {
        accumulator = operation(index, accumulator, this[index])
        result.add(accumulator)
    }
    return result
}

public inline fun <R> UIntArray.scan(initial: R, operation: (acc: R, UInt) -> R): List<R> {
    return runningFold(initial, operation)
}

public inline fun <R> ULongArray.scan(initial: R, operation: (acc: R, ULong) -> R): List<R> {
    return runningFold(initial, operation)
}

public inline fun <R> UByteArray.scan(initial: R, operation: (acc: R, UByte) -> R): List<R> {
    return runningFold(initial, operation)
}

public inline fun <R> UShortArray.scan(initial: R, operation: (acc: R, UShort) -> R): List<R> {
    return runningFold(initial, operation)
}

public inline fun <R> UIntArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UInt) -> R): List<R> {
    return runningFoldIndexed(initial, operation)
}

public inline fun <R> ULongArray.scanIndexed(initial: R, operation: (index: Int, acc: R, ULong) -> R): List<R> {
    return runningFoldIndexed(initial, operation)
}

public inline fun <R> UByteArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UByte) -> R): List<R> {
    return runningFoldIndexed(initial, operation)
}

public inline fun <R> UShortArray.scanIndexed(initial: R, operation: (index: Int, acc: R, UShort) -> R): List<R> {
    return runningFoldIndexed(initial, operation)
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UIntArray.sumBy(selector: (UInt) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun ULongArray.sumBy(selector: (ULong) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UByteArray.sumBy(selector: (UByte) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UShortArray.sumBy(selector: (UShort) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UIntArray.sumByDouble(selector: (UInt) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun ULongArray.sumByDouble(selector: (ULong) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UByteArray.sumByDouble(selector: (UByte) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public inline fun UShortArray.sumByDouble(selector: (UShort) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfDouble")
@kotlin.jvm.JvmName("sumOfIntUIntArraySelector")
public inline fun UIntArray.sumOf(selector: (UInt) -> Int): Int {
    var sum: Int = 0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfLongUIntArraySelector")
public inline fun UIntArray.sumOf(selector: (UInt) -> Long): Long {
    var sum: Long = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfUIntUIntArraySelector")
public inline fun UIntArray.sumOf(selector: (UInt) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfULongUIntArraySelector")
public inline fun UIntArray.sumOf(selector: (UInt) -> ULong): ULong {
    var sum: ULong = 0uL
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleUIntArraySelector")
public inline fun UIntArray.sumOf(selector: (UInt) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfDouble")
@kotlin.jvm.JvmName("sumOfIntULongArraySelector")
public inline fun ULongArray.sumOf(selector: (ULong) -> Int): Int {
    var sum: Int = 0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfLongULongArraySelector")
public inline fun ULongArray.sumOf(selector: (ULong) -> Long): Long {
    var sum: Long = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfUIntULongArraySelector")
public inline fun ULongArray.sumOf(selector: (ULong) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfULongULongArraySelector")
public inline fun ULongArray.sumOf(selector: (ULong) -> ULong): ULong {
    var sum: ULong = 0uL
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleULongArraySelector")
public inline fun ULongArray.sumOf(selector: (ULong) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfDouble")
@kotlin.jvm.JvmName("sumOfIntUByteArraySelector")
public inline fun UByteArray.sumOf(selector: (UByte) -> Int): Int {
    var sum: Int = 0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfLongUByteArraySelector")
public inline fun UByteArray.sumOf(selector: (UByte) -> Long): Long {
    var sum: Long = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfUIntUByteArraySelector")
public inline fun UByteArray.sumOf(selector: (UByte) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfULongUByteArraySelector")
public inline fun UByteArray.sumOf(selector: (UByte) -> ULong): ULong {
    var sum: ULong = 0uL
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleUByteArraySelector")
public inline fun UByteArray.sumOf(selector: (UByte) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@OverloadResolutionByLambdaReturnType
@kotlin.jvm.JvmName("sumOfDouble")
@kotlin.jvm.JvmName("sumOfIntUShortArraySelector")
public inline fun UShortArray.sumOf(selector: (UShort) -> Int): Int {
    var sum: Int = 0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfLongUShortArraySelector")
public inline fun UShortArray.sumOf(selector: (UShort) -> Long): Long {
    var sum: Long = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfUIntUShortArraySelector")
public inline fun UShortArray.sumOf(selector: (UShort) -> UInt): UInt {
    var sum: UInt = 0u
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfULongUShortArraySelector")
public inline fun UShortArray.sumOf(selector: (UShort) -> ULong): ULong {
    var sum: ULong = 0uL
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleUShortArraySelector")
public inline fun UShortArray.sumOf(selector: (UShort) -> Double): Double {
    var sum: Double = 0.0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}
