/*
 * Mini stdlib: the rest of kotlin.comparisons (compareValuesBy, nullsFirst / nullsLast, minOf / maxOf for more types and arities)
 * with the semantics of kotlin-stdlib (floating point minOf / maxOf follow kotlin.math.min / max: NaN wins, -0.0 < 0.0).
 */
package kotlin.comparisons

public inline fun <T> compareValuesBy(a: T, b: T, selector: (T) -> Comparable<*>?): Int = compareValues(selector(a), selector(b))

public fun <T> compareValuesBy(a: T, b: T, vararg selectors: (T) -> Comparable<*>?): Int {
    if (selectors.size <= 0) throw IllegalArgumentException("Failed requirement.")
    for (fn in selectors) {
        val v1 = fn(a)
        val v2 = fn(b)
        val diff = compareValues(v1, v2)
        if (diff != 0) return diff
    }
    return 0
}

public inline fun <T, K> compareValuesBy(a: T, b: T, comparator: Comparator<in K>, selector: (T) -> K): Int =
    comparator.compare(selector(a), selector(b))

public inline fun <T, K> compareBy(comparator: Comparator<in K>, crossinline selector: (T) -> K): Comparator<T> =
    Comparator { a, b -> comparator.compare(selector(a), selector(b)) }

public inline fun <T, K> compareByDescending(comparator: Comparator<in K>, crossinline selector: (T) -> K): Comparator<T> =
    Comparator { a, b -> comparator.compare(selector(b), selector(a)) }

public inline fun <T, K> Comparator<T>.thenBy(comparator: Comparator<in K>, crossinline selector: (T) -> K): Comparator<T> =
    Comparator { a, b ->
        val previousCompare = this.compare(a, b)
        if (previousCompare != 0) previousCompare else comparator.compare(selector(a), selector(b))
    }

public fun <T : Comparable<T>> nullsFirst(): Comparator<T?> = nullsFirst(naturalOrder<T>())

public fun <T : Any> nullsFirst(comparator: Comparator<in T>): Comparator<T?> =
    Comparator { a, b ->
        if (a === b) 0
        else if (a == null) -1
        else if (b == null) 1
        else comparator.compare(a, b)
    }

public fun <T : Comparable<T>> nullsLast(): Comparator<T?> = nullsLast(naturalOrder<T>())

public fun <T : Any> nullsLast(comparator: Comparator<in T>): Comparator<T?> =
    Comparator { a, b ->
        if (a === b) 0
        else if (a == null) 1
        else if (b == null) -1
        else comparator.compare(a, b)
    }

// ---- minOf / maxOf ---------------------------------------------------------------------------------------

public fun maxOf(a: Byte, b: Byte): Byte = if (a >= b) a else b
public fun maxOf(a: Short, b: Short): Short = if (a >= b) a else b
public fun maxOf(a: Float, b: Float): Float = kotlin.math.max(a, b)
public fun minOf(a: Byte, b: Byte): Byte = if (a <= b) a else b
public fun minOf(a: Short, b: Short): Short = if (a <= b) a else b
public fun minOf(a: Float, b: Float): Float = kotlin.math.min(a, b)

public fun maxOf(a: Byte, b: Byte, c: Byte): Byte = maxOf(a, maxOf(b, c))
public fun maxOf(a: Short, b: Short, c: Short): Short = maxOf(a, maxOf(b, c))
public fun maxOf(a: Float, b: Float, c: Float): Float = maxOf(a, maxOf(b, c))
public fun maxOf(a: Double, b: Double, c: Double): Double = maxOf(a, maxOf(b, c))
public fun minOf(a: Byte, b: Byte, c: Byte): Byte = minOf(a, minOf(b, c))
public fun minOf(a: Short, b: Short, c: Short): Short = minOf(a, minOf(b, c))
public fun minOf(a: Float, b: Float, c: Float): Float = minOf(a, minOf(b, c))
public fun minOf(a: Double, b: Double, c: Double): Double = minOf(a, minOf(b, c))

public fun maxOf(a: Int, vararg other: Int): Int {
    var max = a
    for (e in other) max = maxOf(max, e)
    return max
}
public fun maxOf(a: Long, vararg other: Long): Long {
    var max = a
    for (e in other) max = maxOf(max, e)
    return max
}
public fun maxOf(a: Float, vararg other: Float): Float {
    var max = a
    for (e in other) max = maxOf(max, e)
    return max
}
public fun maxOf(a: Double, vararg other: Double): Double {
    var max = a
    for (e in other) max = maxOf(max, e)
    return max
}
public fun minOf(a: Int, vararg other: Int): Int {
    var min = a
    for (e in other) min = minOf(min, e)
    return min
}
public fun minOf(a: Long, vararg other: Long): Long {
    var min = a
    for (e in other) min = minOf(min, e)
    return min
}
public fun minOf(a: Float, vararg other: Float): Float {
    var min = a
    for (e in other) min = minOf(min, e)
    return min
}
public fun minOf(a: Double, vararg other: Double): Double {
    var min = a
    for (e in other) min = minOf(min, e)
    return min
}

public fun <T : Comparable<T>> maxOf(a: T, b: T, c: T): T = maxOf(a, maxOf(b, c))
public fun <T : Comparable<T>> minOf(a: T, b: T, c: T): T = minOf(a, minOf(b, c))

public fun <T : Comparable<T>> maxOf(a: T, vararg other: T): T {
    var max = a
    for (e in other) max = maxOf(max, e)
    return max
}
public fun <T : Comparable<T>> minOf(a: T, vararg other: T): T {
    var min = a
    for (e in other) min = minOf(min, e)
    return min
}

public fun <T> maxOf(a: T, b: T, comparator: Comparator<in T>): T = if (comparator.compare(a, b) >= 0) a else b
public fun <T> minOf(a: T, b: T, comparator: Comparator<in T>): T = if (comparator.compare(a, b) <= 0) a else b

public fun <T> maxOf(a: T, b: T, c: T, comparator: Comparator<in T>): T = maxOf(a, maxOf(b, c, comparator), comparator)
public fun <T> minOf(a: T, b: T, c: T, comparator: Comparator<in T>): T = minOf(a, minOf(b, c, comparator), comparator)

public fun <T> maxOf(a: T, vararg other: T, comparator: Comparator<in T>): T {
    var max = a
    for (e in other) if (comparator.compare(max, e) < 0) max = e
    return max
}
public fun <T> minOf(a: T, vararg other: T, comparator: Comparator<in T>): T {
    var min = a
    for (e in other) if (comparator.compare(min, e) > 0) min = e
    return min
}
