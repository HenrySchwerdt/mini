/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib comparisons/Comparisons.kt and _Comparisons.kt (subset).
 */
package kotlin.comparisons

public fun <T : Comparable<*>> compareValues(a: T?, b: T?): Int {
    if (a === b) return 0
    if (a == null) return -1
    if (b == null) return 1
    @Suppress("UNCHECKED_CAST")
    return (a as Comparable<Any>).compareTo(b)
}

public inline fun <T> compareBy(crossinline selector: (T) -> Comparable<*>?): Comparator<T> =
    Comparator { a, b -> compareValues(selector(a), selector(b)) }

public fun <T> compareBy(vararg selectors: (T) -> Comparable<*>?): Comparator<T> {
    if (selectors.size == 0) throw IllegalArgumentException("Failed requirement.")
    return Comparator { a, b -> compareValuesByImpl(a, b, selectors) }
}

private fun <T> compareValuesByImpl(a: T, b: T, selectors: Array<out (T) -> Comparable<*>?>): Int {
    for (fn in selectors) {
        val v1 = fn(a)
        val v2 = fn(b)
        val diff = compareValues(v1, v2)
        if (diff != 0) return diff
    }
    return 0
}

public inline fun <T> compareByDescending(crossinline selector: (T) -> Comparable<*>?): Comparator<T> =
    Comparator { a, b -> compareValues(selector(b), selector(a)) }

public fun <T : Comparable<T>> naturalOrder(): Comparator<T> = Comparator { a, b -> a.compareTo(b) }

public fun <T : Comparable<T>> reverseOrder(): Comparator<T> = Comparator { a, b -> b.compareTo(a) }

public fun <T> Comparator<T>.reversed(): Comparator<T> = Comparator { a, b -> this.compare(b, a) }

public infix fun <T> Comparator<T>.thenBy(selector: (T) -> Comparable<*>?): Comparator<T> =
    Comparator { a, b ->
        val r = this.compare(a, b)
        if (r != 0) r else compareValues(selector(a), selector(b))
    }

public inline infix fun <T> Comparator<T>.thenByDescending(crossinline selector: (T) -> Comparable<*>?): Comparator<T> =
    Comparator { a, b ->
        val r = this.compare(a, b)
        if (r != 0) r else compareValues(selector(b), selector(a))
    }

public infix fun <T> Comparator<T>.thenComparator(comparison: (a: T, b: T) -> Int): Comparator<T> =
    Comparator { a, b ->
        val r = this.compare(a, b)
        if (r != 0) r else comparison(a, b)
    }

public infix fun <T> Comparator<T>.then(comparator: Comparator<in T>): Comparator<T> =
    Comparator { a, b ->
        val r = this.compare(a, b)
        if (r != 0) r else comparator.compare(a, b)
    }

public fun maxOf(a: Int, b: Int): Int = if (a >= b) a else b
public fun maxOf(a: Long, b: Long): Long = if (a >= b) a else b
public fun maxOf(a: Double, b: Double): Double = if (a >= b) a else b
public fun maxOf(a: Char, b: Char): Char = if (a >= b) a else b
public fun maxOf(a: Int, b: Int, c: Int): Int = maxOf(a, maxOf(b, c))
public fun maxOf(a: Long, b: Long, c: Long): Long = maxOf(a, maxOf(b, c))
public fun <T : Comparable<T>> maxOf(a: T, b: T): T = if (a >= b) a else b

public fun minOf(a: Int, b: Int): Int = if (a <= b) a else b
public fun minOf(a: Long, b: Long): Long = if (a <= b) a else b
public fun minOf(a: Double, b: Double): Double = if (a <= b) a else b
public fun minOf(a: Char, b: Char): Char = if (a <= b) a else b
public fun minOf(a: Int, b: Int, c: Int): Int = minOf(a, minOf(b, c))
public fun minOf(a: Long, b: Long, c: Long): Long = minOf(a, minOf(b, c))
public fun <T : Comparable<T>> minOf(a: T, b: T): T = if (a <= b) a else b
