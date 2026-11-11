/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * The long tail of the kotlin.collections functions (ported from kotlin-stdlib _Collections.kt).
 */
package kotlin.collections

import kotlin.contracts.*
import kotlin.random.Random


public fun <T> Iterable<T>.elementAtOrElse(index: Int, defaultValue: (Int) -> T): T {
    contract {
        callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
    }
    if (this is List)
        return this.getOrElse(index, defaultValue)
    if (index < 0)
        return defaultValue(index)
    val iterator = iterator()
    var count = 0
    while (iterator.hasNext()) {
        val element = iterator.next()
        if (index == count++)
            return element
    }
    return defaultValue(index)
}

public inline fun <T> Iterable<T>.findLast(predicate: (T) -> Boolean): T? {
    return lastOrNull(predicate)
}

public inline fun <S, T : S> List<T>.reduceRightIndexed(operation: (index: Int, T, acc: S) -> S): S {
    val iterator = listIterator(size)
    if (!iterator.hasPrevious())
        throw UnsupportedOperationException("Empty list can't be reduced.")
    var accumulator: S = iterator.previous()
    while (iterator.hasPrevious()) {
        val index = iterator.previousIndex()
        accumulator = operation(index, iterator.previous(), accumulator)
    }
    return accumulator
}

public inline fun <S, T : S> List<T>.reduceRightIndexedOrNull(operation: (index: Int, T, acc: S) -> S): S? {
    val iterator = listIterator(size)
    if (!iterator.hasPrevious())
        return null
    var accumulator: S = iterator.previous()
    while (iterator.hasPrevious()) {
        val index = iterator.previousIndex()
        accumulator = operation(index, iterator.previous(), accumulator)
    }
    return accumulator
}

public inline fun <S, T : S> Iterable<T>.runningReduceIndexed(operation: (index: Int, acc: S, T) -> S): List<S> {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return emptyList()
    var accumulator: S = iterator.next()
    val result = ArrayList<S>(10).apply { add(accumulator) }
    var index = 1
    while (iterator.hasNext()) {
        accumulator = operation(index++, accumulator, iterator.next())
        result.add(accumulator)
    }
    return result
}

public inline fun <T> Iterable<T>.plusElement(element: T): List<T> {
    return plus(element)
}
