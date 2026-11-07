/*
 * The kotlin.collections functions of Array<T> (the semantics follow kotlin-stdlib).
 * Most of them go through a List copy; the hot ones (indexOf, forEach, map, ...) are direct loops.
 */
package kotlin.collections

public fun <T> Array<out T>.isEmpty(): Boolean = size == 0
public fun <T> Array<out T>.isNotEmpty(): Boolean = size != 0

@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.copyOf(): Array<T> {
    val r = arrayOfNulls<T>(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r as Array<T>
}

public fun <T> Array<T>.copyOf(newSize: Int): Array<T?> {
    if (newSize < 0) throw IllegalArgumentException("Invalid new array size: " + newSize + ".")
    val r = arrayOfNulls<T?>(newSize) // (T?: an array like this one, with room for null)
    var i = 0
    val n = if (newSize < size) newSize else size
    while (i < n) { r[i] = this[i]; i++ }
    return r
}

@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.copyOfRange(fromIndex: Int, toIndex: Int): Array<T> {
    if (fromIndex > toIndex) throw IllegalArgumentException("fromIndex (" + fromIndex + ") > toIndex (" + toIndex + ")")
    if (fromIndex < 0 || toIndex > size) throw IndexOutOfBoundsException("fromIndex: " + fromIndex + ", toIndex: " + toIndex + ", size: " + size)
    val r = arrayOfNulls<T>(toIndex - fromIndex)
    var i = fromIndex
    while (i < toIndex) { r[i - fromIndex] = this[i]; i++ }
    return r as Array<T>
}

public fun <T> Array<out T>.copyInto(destination: Array<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): Array<T> {
    if (startIndex < 0 || endIndex > size || startIndex > endIndex) throw IndexOutOfBoundsException("startIndex: " + startIndex + ", endIndex: " + endIndex + ", size: " + size)
    if (destinationOffset < 0 || destinationOffset + (endIndex - startIndex) > destination.size) throw IndexOutOfBoundsException("The destination array is too small")
    if (this === destination && destinationOffset > startIndex) {
        var i = endIndex - 1
        while (i >= startIndex) { destination[destinationOffset + i - startIndex] = this[i]; i-- }
    } else {
        var i = startIndex
        while (i < endIndex) { destination[destinationOffset + i - startIndex] = this[i]; i++ }
    }
    return destination
}

public fun <T> Array<T>.fill(element: T, fromIndex: Int = 0, toIndex: Int = size) {
    if (fromIndex < 0 || toIndex > size) throw IndexOutOfBoundsException("fromIndex: " + fromIndex + ", toIndex: " + toIndex + ", size: " + size)
    var i = fromIndex
    while (i < toIndex) { this[i] = element; i++ }
}

public infix fun <T> Array<out T>?.contentEquals(other: Array<out T>?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    if (size != other.size) return false
    var i = 0
    while (i < size) { if (this[i] != other[i]) return false; i++ }
    return true
}

public fun <T> Array<out T>?.contentHashCode(): Int {
    if (this == null) return 0
    var h = 1
    var i = 0
    while (i < size) { h = 31 * h + (this[i]?.hashCode() ?: 0); i++ }
    return h
}

public fun <T> Array<out T>.asIterable(): Iterable<T> = asList()
public fun <T> Array<out T>.toMutableList(): MutableList<T> {
    val r = ArrayList<T>(size)
    var i = 0
    while (i < size) { r.add(this[i]); i++ }
    return r
}
public fun <T> Array<out T>.toSet(): Set<T> = toMutableList().toSet()
public fun <T> Array<out T>.toMutableSet(): MutableSet<T> = toMutableList().toMutableSet()
public fun <T> Array<out T>.toHashSet(): HashSet<T> = HashSet<T>(toMutableList())

public fun <T> Array<T>.reverse() {
    var i = 0
    var j = size - 1
    while (i < j) { val t = this[i]; this[i] = this[j]; this[j] = t; i++; j-- }
}
public fun <T> Array<out T>.reversed(): List<T> {
    val r = ArrayList<T>(size)
    var i = size - 1
    while (i >= 0) { r.add(this[i]); i-- }
    return r
}
@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.reversedArray(): Array<T> {
    val r = arrayOfNulls<T>(size)
    var i = 0
    while (i < size) { r[size - 1 - i] = this[i]; i++ }
    return r as Array<T>
}

public fun <T> Array<out T>.first(): T {
    if (isEmpty()) throw NoSuchElementException("Array is empty.")
    return this[0]
}
public fun <T> Array<out T>.last(): T {
    if (isEmpty()) throw NoSuchElementException("Array is empty.")
    return this[size - 1]
}
public fun <T> Array<out T>.firstOrNull(): T? = if (isEmpty()) null else this[0]
public fun <T> Array<out T>.lastOrNull(): T? = if (isEmpty()) null else this[size - 1]
public fun <T> Array<out T>.getOrNull(index: Int): T? = if (index >= 0 && index < size) this[index] else null
public inline fun <T> Array<out T>.getOrElse(index: Int, defaultValue: (Int) -> T): T = if (index >= 0 && index < size) this[index] else defaultValue(index)
public fun <T> Array<out T>.single(): T {
    if (size == 0) throw NoSuchElementException("Array is empty.")
    if (size != 1) throw IllegalArgumentException("Array has more than one element.")
    return this[0]
}
public fun <T> Array<out T>.singleOrNull(): T? = if (size == 1) this[0] else null
public inline fun <T> Array<out T>.first(predicate: (T) -> Boolean): T {
    for (e in this) if (predicate(e)) return e
    throw NoSuchElementException("Array contains no element matching the predicate.")
}
public inline fun <T> Array<out T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (e in this) if (predicate(e)) return e
    return null
}
public inline fun <T> Array<out T>.find(predicate: (T) -> Boolean): T? = firstOrNull(predicate)
public inline fun <T> Array<out T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var i = size - 1
    while (i >= 0) { val e = this[i]; if (predicate(e)) return e; i-- }
    return null
}
public inline fun <T> Array<out T>.findLast(predicate: (T) -> Boolean): T? = lastOrNull(predicate)

public fun <T> Array<out T>.indexOf(element: T): Int {
    var i = 0
    while (i < size) { if (this[i] == element) return i; i++ }
    return -1
}
public fun <T> Array<out T>.lastIndexOf(element: T): Int {
    var i = size - 1
    while (i >= 0) { if (this[i] == element) return i; i-- }
    return -1
}
public operator fun <T> Array<out T>.contains(element: T): Boolean = indexOf(element) >= 0
public inline fun <T> Array<out T>.indexOfFirst(predicate: (T) -> Boolean): Int {
    var i = 0
    while (i < size) { if (predicate(this[i])) return i; i++ }
    return -1
}
public inline fun <T> Array<out T>.indexOfLast(predicate: (T) -> Boolean): Int {
    var i = size - 1
    while (i >= 0) { if (predicate(this[i])) return i; i-- }
    return -1
}

public inline fun <T> Array<out T>.forEach(action: (T) -> Unit) { for (e in this) action(e) }
public inline fun <T> Array<out T>.forEachIndexed(action: (index: Int, T) -> Unit) {
    var i = 0
    while (i < size) { action(i, this[i]); i++ }
}
public inline fun <T, R> Array<out T>.map(transform: (T) -> R): List<R> {
    val r = ArrayList<R>(size)
    for (e in this) r.add(transform(e))
    return r
}
public inline fun <T, R> Array<out T>.mapIndexed(transform: (index: Int, T) -> R): List<R> {
    val r = ArrayList<R>(size)
    var i = 0
    while (i < size) { r.add(transform(i, this[i])); i++ }
    return r
}
public inline fun <T, R : Any> Array<out T>.mapNotNull(transform: (T) -> R?): List<R> {
    val r = ArrayList<R>()
    for (e in this) { val v = transform(e); if (v != null) r.add(v) }
    return r
}
public inline fun <T, R> Array<out T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
    val r = ArrayList<R>()
    for (e in this) r.addAll(transform(e))
    return r
}
public inline fun <T> Array<out T>.filter(predicate: (T) -> Boolean): List<T> {
    val r = ArrayList<T>()
    for (e in this) if (predicate(e)) r.add(e)
    return r
}
public inline fun <T> Array<out T>.filterIndexed(predicate: (index: Int, T) -> Boolean): List<T> {
    val r = ArrayList<T>()
    var i = 0
    while (i < size) { if (predicate(i, this[i])) r.add(this[i]); i++ }
    return r
}
public inline fun <T> Array<out T>.filterNot(predicate: (T) -> Boolean): List<T> {
    val r = ArrayList<T>()
    for (e in this) if (!predicate(e)) r.add(e)
    return r
}
public fun <T : Any> Array<out T?>.filterNotNull(): List<T> {
    val r = ArrayList<T>()
    for (e in this) if (e != null) r.add(e)
    return r
}
public inline fun <T> Array<out T>.partition(predicate: (T) -> Boolean): Pair<List<T>, List<T>> {
    val a = ArrayList<T>()
    val b = ArrayList<T>()
    for (e in this) if (predicate(e)) a.add(e) else b.add(e)
    return Pair(a, b)
}
public inline fun <T> Array<out T>.any(predicate: (T) -> Boolean): Boolean {
    for (e in this) if (predicate(e)) return true
    return false
}
public fun <T> Array<out T>.any(): Boolean = size != 0
public inline fun <T> Array<out T>.all(predicate: (T) -> Boolean): Boolean {
    for (e in this) if (!predicate(e)) return false
    return true
}
public inline fun <T> Array<out T>.none(predicate: (T) -> Boolean): Boolean {
    for (e in this) if (predicate(e)) return false
    return true
}
public fun <T> Array<out T>.none(): Boolean = size == 0
public fun <T> Array<out T>.count(): Int = size
public inline fun <T> Array<out T>.count(predicate: (T) -> Boolean): Int {
    var n = 0
    for (e in this) if (predicate(e)) n++
    return n
}
public inline fun <T, R> Array<out T>.fold(initial: R, operation: (acc: R, T) -> R): R {
    var acc = initial
    for (e in this) acc = operation(acc, e)
    return acc
}
public inline fun <T, R> Array<out T>.foldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): R {
    var acc = initial
    var i = 0
    while (i < size) { acc = operation(i, acc, this[i]); i++ }
    return acc
}
public inline fun <S, T : S> Array<out T>.reduce(operation: (acc: S, T) -> S): S {
    if (isEmpty()) throw UnsupportedOperationException("Empty array can't be reduced.")
    var acc: S = this[0]
    var i = 1
    while (i < size) { acc = operation(acc, this[i]); i++ }
    return acc
}
public inline fun <T, K, V> Array<out T>.associate(transform: (T) -> Pair<K, V>): Map<K, V> {
    val m = LinkedHashMap<K, V>()
    for (e in this) { val p = transform(e); m[p.first] = p.second }
    return m
}
public inline fun <T, K> Array<out T>.associateBy(keySelector: (T) -> K): Map<K, T> {
    val m = LinkedHashMap<K, T>()
    for (e in this) m[keySelector(e)] = e
    return m
}
public inline fun <T, V> Array<out T>.associateWith(valueSelector: (T) -> V): Map<T, V> {
    val m = LinkedHashMap<T, V>()
    for (e in this) m[e] = valueSelector(e)
    return m
}
public inline fun <T, K> Array<out T>.groupBy(keySelector: (T) -> K): Map<K, List<T>> {
    val m = LinkedHashMap<K, MutableList<T>>()
    for (e in this) {
        val k = keySelector(e)
        var l = m[k]
        if (l == null) { l = ArrayList<T>(); m[k] = l }
        l.add(e)
    }
    return m
}
public fun <T> Array<out T>.withIndex(): Iterable<IndexedValue<T>> = toList().withIndex()
public fun <T> Array<out T>.distinct(): List<T> = toList().distinct()
public fun <T> Array<out T>.take(n: Int): List<T> = toList().take(n)
public fun <T> Array<out T>.drop(n: Int): List<T> = toList().drop(n)
public fun <T> Array<out T>.takeLast(n: Int): List<T> = toList().takeLast(n)
public fun <T> Array<out T>.dropLast(n: Int): List<T> = toList().dropLast(n)
public fun <T> Array<out T>.slice(indices: Iterable<Int>): List<T> {
    val r = ArrayList<T>()
    for (i in indices) r.add(this[i])
    return r
}
public infix fun <T, R> Array<out T>.zip(other: Array<out R>): List<Pair<T, R>> {
    val n = if (size < other.size) size else other.size
    val r = ArrayList<Pair<T, R>>(n)
    var i = 0
    while (i < n) { r.add(Pair(this[i], other[i])); i++ }
    return r
}
public operator fun <T> Array<T>.plus(element: T): Array<T> {
    @Suppress("UNCHECKED_CAST")
    val r = arrayOfNulls<T>(size + 1) as Array<T>
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    r[size] = element
    return r
}
public fun <T> Array<out T>.joinToString(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: ((T) -> CharSequence)? = null,
): String = toList().joinToString(separator, prefix, postfix, limit, truncated, transform)

public fun <T : Comparable<T>> Array<out T>.max(): T = maxOrNull() ?: throw NoSuchElementException()
public fun <T : Comparable<T>> Array<out T>.min(): T = minOrNull() ?: throw NoSuchElementException()
public fun <T : Comparable<T>> Array<out T>.maxOrNull(): T? {
    if (isEmpty()) return null
    var m = this[0]
    var i = 1
    while (i < size) { if (this[i] > m) m = this[i]; i++ }
    return m
}
public fun <T : Comparable<T>> Array<out T>.minOrNull(): T? {
    if (isEmpty()) return null
    var m = this[0]
    var i = 1
    while (i < size) { if (this[i] < m) m = this[i]; i++ }
    return m
}
public inline fun <T, R : Comparable<R>> Array<out T>.maxOf(selector: (T) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}
public inline fun <T, R : Comparable<R>> Array<out T>.minOf(selector: (T) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}
public inline fun <T, R : Comparable<R>> Array<out T>.maxByOrNull(selector: (T) -> R): T? = toList().maxByOrNull(selector)
public inline fun <T, R : Comparable<R>> Array<out T>.minByOrNull(selector: (T) -> R): T? = toList().minByOrNull(selector)
@kotlin.jvm.JvmName("sumOfIntArraySelector")
public inline fun <T> Array<out T>.sumOf(selector: (T) -> Int): Int {
    var s = 0
    for (e in this) s += selector(e)
    return s
}
@kotlin.jvm.JvmName("sumOfLongArraySelector")
public inline fun <T> Array<out T>.sumOf(selector: (T) -> Long): Long {
    var s = 0L
    for (e in this) s += selector(e)
    return s
}
@kotlin.jvm.JvmName("sumOfDoubleArraySelector")
public inline fun <T> Array<out T>.sumOf(selector: (T) -> Double): Double {
    var s = 0.0
    for (e in this) s += selector(e)
    return s
}


public fun <T> Array<T>.sortWith(comparator: Comparator<in T>) {
    if (size < 2) return
    val tmp = arrayOfNulls<Any?>(size) as Array<T>
    mergeSortArray(this, tmp, 0, size, comparator)
}

private fun <T> mergeSortArray(a: Array<T>, tmp: Array<T>, from: Int, to: Int, c: Comparator<in T>) {
    if (to - from < 2) return
    val mid = (from + to) ushr 1
    mergeSortArray(a, tmp, from, mid, c)
    mergeSortArray(a, tmp, mid, to, c)
    var i = from
    var j = mid
    var k = from
    while (i < mid && j < to) {
        if (c.compare(a[j], a[i]) < 0) { tmp[k++] = a[j++] } else { tmp[k++] = a[i++] }
    }
    while (i < mid) tmp[k++] = a[i++]
    while (j < to) tmp[k++] = a[j++]
    k = from
    while (k < to) { a[k] = tmp[k]; k++ }
}
public fun <T : Comparable<T>> Array<T>.sort() { sortWith(Comparator<T> { a, b -> a.compareTo(b) }) }
public inline fun <T, R : Comparable<R>> Array<T>.sortBy(crossinline selector: (T) -> R?) { sortWith(Comparator<T> { a, b -> compareValues(selector(a), selector(b)) }) }
public inline fun <T, R : Comparable<R>> Array<T>.sortByDescending(crossinline selector: (T) -> R?) { sortWith(Comparator<T> { a, b -> compareValues(selector(b), selector(a)) }) }
public fun <T : Comparable<T>> Array<T>.sortDescending() { sortWith(Comparator<T> { a, b -> b.compareTo(a) }) }
public fun <T : Comparable<T>> Array<out T>.sorted(): List<T> = toList().sorted()
public fun <T : Comparable<T>> Array<out T>.sortedDescending(): List<T> = toList().sortedDescending()
public inline fun <T, R : Comparable<R>> Array<out T>.sortedBy(crossinline selector: (T) -> R?): List<T> = toList().sortedBy(selector)
public inline fun <T, R : Comparable<R>> Array<out T>.sortedByDescending(crossinline selector: (T) -> R?): List<T> = toList().sortedByDescending(selector)
public fun <T> Array<out T>.sortedWith(comparator: Comparator<in T>): List<T> = toList().sortedWith(comparator)
@Suppress("UNCHECKED_CAST")
public fun <T : Comparable<T>> Array<T>.sortedArray(): Array<T> {
    val r = copyOf()
    r.sort()
    return r
}
