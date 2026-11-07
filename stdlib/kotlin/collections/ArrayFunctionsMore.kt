/*
 * Mini stdlib: the long tail of the kotlin.collections functions of Array<T> (the semantics follow kotlin-stdlib).
 * ArrayFunctions.kt has the common part, PrimitiveArrayFunctionsMore.kt the primitive arrays.
 */
package kotlin.collections

import kotlin.random.Random

// ---- numbers -------------------------------------------------------------------------------------------

public fun Array<out Int>.sum(): Int {
    var s = 0
    for (e in this) s += e
    return s
}
public fun Array<out Long>.sum(): Long {
    var s = 0L
    for (e in this) s += e
    return s
}
public fun Array<out Double>.sum(): Double {
    var s = 0.0
    for (e in this) s += e
    return s
}
public fun Array<out Float>.sum(): Float {
    var s = 0.0f
    for (e in this) s += e
    return s
}
public fun Array<out Byte>.sum(): Int {
    var s = 0
    for (e in this) s += e
    return s
}
public fun Array<out Short>.sum(): Int {
    var s = 0
    for (e in this) s += e
    return s
}
public fun Array<out Int>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public fun Array<out Long>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public fun Array<out Double>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public fun Array<out Float>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public fun Array<out Byte>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public fun Array<out Short>.average(): Double {
    if (isEmpty()) return Double.NaN
    var s = 0.0
    for (e in this) s += e
    return s / size
}
public inline fun <T> Array<out T>.sumBy(selector: (T) -> Int): Int {
    var s = 0
    for (e in this) s += selector(e)
    return s
}
public inline fun <T> Array<out T>.sumByDouble(selector: (T) -> Double): Double {
    var s = 0.0
    for (e in this) s += selector(e)
    return s
}

// ---- conversions ---------------------------------------------------------------------------------------

public fun Array<out Int>.toIntArray(): IntArray {
    val r = IntArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Long>.toLongArray(): LongArray {
    val r = LongArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Double>.toDoubleArray(): DoubleArray {
    val r = DoubleArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Float>.toFloatArray(): FloatArray {
    val r = FloatArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Char>.toCharArray(): CharArray {
    val r = CharArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Boolean>.toBooleanArray(): BooleanArray {
    val r = BooleanArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Byte>.toByteArray(): ByteArray {
    val r = ByteArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}
public fun Array<out Short>.toShortArray(): ShortArray {
    val r = ShortArray(size)
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    return r
}

public fun <T, C : MutableCollection<in T>> Array<out T>.toCollection(destination: C): C {
    for (e in this) destination.add(e)
    return destination
}
public fun <T : Comparable<T>> Array<out T>.toSortedSet(): java.util.TreeSet<T> {
    val r = java.util.TreeSet<T>()
    for (e in this) r.add(e)
    return r
}
public fun <T> Array<out T>.asSequence(): Sequence<T> {
    if (isEmpty()) return emptySequence()
    val a = this
    return Sequence<T> { a.iterator() }
}
public fun <T, R> Array<out Pair<T, R>>.unzip(): Pair<List<T>, List<R>> {
    val l1 = ArrayList<T>(size)
    val l2 = ArrayList<R>(size)
    for (p in this) { l1.add(p.first); l2.add(p.second) }
    return Pair(l1, l2)
}
public fun <T> Array<out Array<out T>>.flatten(): List<T> {
    val r = ArrayList<T>()
    for (a in this) r.addAll(a.asList())
    return r
}

// ---- plus, slices --------------------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
public operator fun <T> Array<T>.plus(elements: Array<out T>): Array<T> {
    val r = arrayOfNulls<T>(size + elements.size) as Array<T>
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    var j = 0
    while (j < elements.size) { r[size + j] = elements[j]; j++ }
    return r
}
@Suppress("UNCHECKED_CAST")
public operator fun <T> Array<T>.plus(elements: Collection<T>): Array<T> {
    val r = arrayOfNulls<T>(size + elements.size) as Array<T>
    var i = 0
    while (i < size) { r[i] = this[i]; i++ }
    for (e in elements) { r[i] = e; i++ }
    return r
}
@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.sliceArray(indices: IntRange): Array<T> {
    if (indices.isEmpty()) return arrayOfNulls<T>(0) as Array<T>
    return copyOfRange(indices.start, indices.endInclusive + 1)
}
@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.sliceArray(indices: Collection<Int>): Array<T> {
    val r = arrayOfNulls<T>(indices.size) as Array<T>
    var i = 0
    for (index in indices) { r[i] = this[index]; i++ }
    return r
}
public fun <T> Array<out T>.slice(indices: IntRange): List<T> {
    if (indices.isEmpty()) return emptyList()
    val r = ArrayList<T>()
    var i = indices.start
    while (i <= indices.endInclusive) { r.add(this[i]); i++ }
    return r
}

// ---- searching, ordering -------------------------------------------------------------------------------

public fun <T : Comparable<T>> Array<out T?>.binarySearch(element: T?, fromIndex: Int = 0, toIndex: Int = size): Int {
    checkArrayRangeIndexes(fromIndex, toIndex, size)
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val c = compareValues(this[mid], element)
        if (c < 0) low = mid + 1 else if (c > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}
public fun <T> Array<out T>.binarySearch(element: T, comparator: Comparator<in T>, fromIndex: Int = 0, toIndex: Int = size): Int {
    checkArrayRangeIndexes(fromIndex, toIndex, size)
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val c = comparator.compare(this[mid], element)
        if (c < 0) low = mid + 1 else if (c > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}

public fun <T> Array<T>.reverse(fromIndex: Int, toIndex: Int) {
    checkArrayRangeIndexes(fromIndex, toIndex, size)
    val midPoint = (fromIndex + toIndex) / 2
    if (fromIndex == midPoint) return
    var reverseIndex = toIndex - 1
    var index = fromIndex
    while (index < midPoint) {
        val tmp = this[index]
        this[index] = this[reverseIndex]
        this[reverseIndex] = tmp
        reverseIndex--
        index++
    }
}
public fun <T> Array<T>.shuffle() { shuffle(Random.Default) }
public fun <T> Array<T>.shuffle(random: Random) {
    var i = size - 1
    while (i >= 1) {
        val j = random.nextInt(i + 1)
        val t = this[i]
        this[i] = this[j]
        this[j] = t
        i--
    }
}
public fun <T> Array<out T>.random(): T = random(Random.Default)
public fun <T> Array<out T>.random(random: Random): T {
    if (isEmpty()) throw NoSuchElementException("Array is empty.")
    return this[random.nextInt(size)]
}
public fun <T> Array<out T>.randomOrNull(): T? = randomOrNull(Random.Default)
public fun <T> Array<out T>.randomOrNull(random: Random): T? {
    if (isEmpty()) return null
    return this[random.nextInt(size)]
}
public fun <T> Array<T>.sortWith(comparator: Comparator<in T>, fromIndex: Int, toIndex: Int) {
    checkArrayRangeIndexes(fromIndex, toIndex, size)
    val part = ArrayList<T>(toIndex - fromIndex)
    var i = fromIndex
    while (i < toIndex) { part.add(this[i]); i++ }
    part.sortWith(comparator)
    i = fromIndex
    for (e in part) { this[i] = e; i++ }
}
public fun <T : Comparable<T>> Array<out T>.sort(fromIndex: Int, toIndex: Int) {
    @Suppress("UNCHECKED_CAST")
    (this as Array<T>).sortWith(Comparator<T> { a, b -> a.compareTo(b) }, fromIndex, toIndex)
}
public fun <T : Comparable<T>> Array<T>.sortDescending(fromIndex: Int, toIndex: Int) {
    sortWith(Comparator<T> { a, b -> b.compareTo(a) }, fromIndex, toIndex)
}
@Suppress("UNCHECKED_CAST")
public fun <T : Comparable<T>> Array<T>.sortedArrayDescending(): Array<T> {
    val r = copyOf()
    r.sortDescending()
    return r
}
@Suppress("UNCHECKED_CAST")
public fun <T> Array<T>.sortedArrayWith(comparator: Comparator<in T>): Array<T> {
    val r = copyOf()
    r.sortWith(comparator)
    return r
}

// ---- selecting, folding --------------------------------------------------------------------------------

public inline fun <reified R> Array<*>.filterIsInstance(): List<R> {
    val r = ArrayList<R>()
    for (e in this) if (e is R) r.add(e)
    return r
}
public inline fun <T> Array<out T>.takeWhile(predicate: (T) -> Boolean): List<T> {
    val r = ArrayList<T>()
    for (e in this) { if (!predicate(e)) break; r.add(e) }
    return r
}
public inline fun <T> Array<out T>.dropWhile(predicate: (T) -> Boolean): List<T> {
    var yielding = false
    val r = ArrayList<T>()
    for (e in this) {
        if (yielding) r.add(e)
        else if (!predicate(e)) { r.add(e); yielding = true }
    }
    return r
}
public inline fun <T> Array<out T>.takeLastWhile(predicate: (T) -> Boolean): List<T> {
    var i = size - 1
    while (i >= 0) { if (!predicate(this[i])) return drop(i + 1); i-- }
    return toList()
}
public inline fun <T> Array<out T>.dropLastWhile(predicate: (T) -> Boolean): List<T> {
    var i = size - 1
    while (i >= 0) { if (!predicate(this[i])) return take(i + 1); i-- }
    return emptyList()
}
public fun <T> Array<out T>.elementAt(index: Int): T = this[index]
public fun <T> Array<out T>.elementAtOrNull(index: Int): T? = if (index >= 0 && index < size) this[index] else null
public inline fun <T> Array<out T>.elementAtOrElse(index: Int, defaultValue: (Int) -> T): T = if (index >= 0 && index < size) this[index] else defaultValue(index)
public inline fun <T> Array<out T>.last(predicate: (T) -> Boolean): T {
    var i = size - 1
    while (i >= 0) { val e = this[i]; if (predicate(e)) return e; i-- }
    throw NoSuchElementException("Array contains no element matching the predicate.")
}
public inline fun <T> Array<out T>.single(predicate: (T) -> Boolean): T {
    var single: Any? = null
    var found = false
    for (e in this) {
        if (predicate(e)) {
            if (found) throw IllegalArgumentException("Array contains more than one matching element.")
            single = e
            found = true
        }
    }
    if (!found) throw NoSuchElementException("Array contains no element matching the predicate.")
    @Suppress("UNCHECKED_CAST")
    return single as T
}
public inline fun <T> Array<out T>.singleOrNull(predicate: (T) -> Boolean): T? {
    var single: T? = null
    var found = false
    for (e in this) {
        if (predicate(e)) {
            if (found) return null
            single = e
            found = true
        }
    }
    return if (!found) null else single
}
public inline fun <T, R : Any> Array<out T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (e in this) { val v = transform(e); if (v != null) return v }
    return null
}
public inline fun <T, R : Any> Array<out T>.firstNotNullOf(transform: (T) -> R?): R =
    firstNotNullOfOrNull(transform) ?: throw NoSuchElementException("No element of the array was transformed to a non-null value.")
public inline fun <T> Array<out T>.onEach(action: (T) -> Unit): Array<out T> {
    for (e in this) action(e)
    return this
}
public inline fun <T> Array<out T>.onEachIndexed(action: (index: Int, T) -> Unit): Array<out T> {
    var i = 0
    while (i < size) { action(i, this[i]); i++ }
    return this
}
public inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : Array<*>, C : R =
    if (isEmpty()) defaultValue() else this

public inline fun <T, R> Array<out T>.runningFold(initial: R, operation: (acc: R, T) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1)
    result.add(initial)
    var acc = initial
    for (e in this) { acc = operation(acc, e); result.add(acc) }
    return result
}
public inline fun <T, R> Array<out T>.scan(initial: R, operation: (acc: R, T) -> R): List<R> = runningFold(initial, operation)
public inline fun <T, R> Array<out T>.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(size + 1)
    result.add(initial)
    var acc = initial
    var i = 0
    while (i < size) { acc = operation(i, acc, this[i]); result.add(acc); i++ }
    return result
}
public inline fun <T, R> Array<out T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R> = runningFoldIndexed(initial, operation)
public inline fun <S, T : S> Array<out T>.runningReduce(operation: (acc: S, T) -> S): List<S> {
    if (isEmpty()) return emptyList()
    var acc: S = this[0]
    val result = ArrayList<S>(size)
    result.add(acc)
    var i = 1
    while (i < size) { acc = operation(acc, this[i]); result.add(acc); i++ }
    return result
}
public inline fun <S, T : S> Array<out T>.reduceIndexed(operation: (index: Int, acc: S, T) -> S): S {
    if (isEmpty()) throw UnsupportedOperationException("Empty array can't be reduced.")
    var acc: S = this[0]
    var i = 1
    while (i < size) { acc = operation(i, acc, this[i]); i++ }
    return acc
}
public inline fun <S, T : S> Array<out T>.reduceOrNull(operation: (acc: S, T) -> S): S? {
    if (isEmpty()) return null
    var acc: S = this[0]
    var i = 1
    while (i < size) { acc = operation(acc, this[i]); i++ }
    return acc
}
public inline fun <S, T : S> Array<out T>.reduceRight(operation: (T, acc: S) -> S): S {
    var index = size - 1
    if (index < 0) throw UnsupportedOperationException("Empty array can't be reduced.")
    var acc: S = this[index--]
    while (index >= 0) { acc = operation(this[index], acc); index-- }
    return acc
}
public inline fun <T, R> Array<out T>.foldRight(initial: R, operation: (T, acc: R) -> R): R {
    var index = size - 1
    var acc = initial
    while (index >= 0) { acc = operation(this[index], acc); index-- }
    return acc
}
public inline fun <T, R> Array<out T>.foldRightIndexed(initial: R, operation: (index: Int, T, acc: R) -> R): R {
    var index = size - 1
    var acc = initial
    while (index >= 0) { acc = operation(index, this[index], acc); index-- }
    return acc
}

public inline fun <T, R : Comparable<R>> Array<out T>.maxBy(selector: (T) -> R): T = maxByOrNull(selector) ?: throw NoSuchElementException()
public inline fun <T, R : Comparable<R>> Array<out T>.minBy(selector: (T) -> R): T = minByOrNull(selector) ?: throw NoSuchElementException()
public inline fun <T, R : Comparable<R>> Array<out T>.maxOfOrNull(selector: (T) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v > m) m = v; i++ }
    return m
}
public inline fun <T, R : Comparable<R>> Array<out T>.minOfOrNull(selector: (T) -> R): R? {
    if (isEmpty()) return null
    var m = selector(this[0])
    var i = 1
    while (i < size) { val v = selector(this[i]); if (v < m) m = v; i++ }
    return m
}
public fun <T> Array<out T>.maxWithOrNull(comparator: Comparator<in T>): T? {
    if (isEmpty()) return null
    var m = this[0]
    var i = 1
    while (i < size) { if (comparator.compare(m, this[i]) < 0) m = this[i]; i++ }
    return m
}
public fun <T> Array<out T>.minWithOrNull(comparator: Comparator<in T>): T? {
    if (isEmpty()) return null
    var m = this[0]
    var i = 1
    while (i < size) { if (comparator.compare(m, this[i]) > 0) m = this[i]; i++ }
    return m
}
public fun <T> Array<out T>.maxWith(comparator: Comparator<in T>): T = maxWithOrNull(comparator) ?: throw NoSuchElementException()
public fun <T> Array<out T>.minWith(comparator: Comparator<in T>): T = minWithOrNull(comparator) ?: throw NoSuchElementException()
public inline fun <T, K> Array<out T>.distinctBy(selector: (T) -> K): List<T> {
    val seen = HashSet<K>()
    val list = ArrayList<T>()
    for (e in this) { if (seen.add(selector(e))) list.add(e) }
    return list
}

// ---- mapping, grouping ---------------------------------------------------------------------------------

public inline fun <T, R : Any> Array<out T>.mapIndexedNotNull(transform: (index: Int, T) -> R?): List<R> {
    val r = ArrayList<R>()
    var i = 0
    while (i < size) { val v = transform(i, this[i]); if (v != null) r.add(v); i++ }
    return r
}
public inline fun <T, R> Array<out T>.flatMapIndexed(transform: (index: Int, T) -> Iterable<R>): List<R> {
    val r = ArrayList<R>()
    var i = 0
    while (i < size) { r.addAll(transform(i, this[i])); i++ }
    return r
}
public inline fun <T, R, C : MutableCollection<in R>> Array<out T>.mapTo(destination: C, transform: (T) -> R): C {
    for (e in this) destination.add(transform(e))
    return destination
}
public inline fun <T, R, C : MutableCollection<in R>> Array<out T>.mapIndexedTo(destination: C, transform: (index: Int, T) -> R): C {
    var i = 0
    while (i < size) { destination.add(transform(i, this[i])); i++ }
    return destination
}
public inline fun <T, R : Any, C : MutableCollection<in R>> Array<out T>.mapNotNullTo(destination: C, transform: (T) -> R?): C {
    for (e in this) { val v = transform(e); if (v != null) destination.add(v) }
    return destination
}
public inline fun <T, R, C : MutableCollection<in R>> Array<out T>.flatMapTo(destination: C, transform: (T) -> Iterable<R>): C {
    for (e in this) destination.addAll(transform(e))
    return destination
}
public inline fun <T, C : MutableCollection<in T>> Array<out T>.filterTo(destination: C, predicate: (T) -> Boolean): C {
    for (e in this) if (predicate(e)) destination.add(e)
    return destination
}
public inline fun <T, C : MutableCollection<in T>> Array<out T>.filterNotTo(destination: C, predicate: (T) -> Boolean): C {
    for (e in this) if (!predicate(e)) destination.add(e)
    return destination
}
public inline fun <T, C : MutableCollection<in T>> Array<out T>.filterIndexedTo(destination: C, predicate: (index: Int, T) -> Boolean): C {
    var i = 0
    while (i < size) { if (predicate(i, this[i])) destination.add(this[i]); i++ }
    return destination
}
public inline fun <reified R, C : MutableCollection<in R>> Array<*>.filterIsInstanceTo(destination: C): C {
    for (e in this) if (e is R) destination.add(e)
    return destination
}
public inline fun <T, K, V, M : MutableMap<in K, in V>> Array<out T>.associateTo(destination: M, transform: (T) -> Pair<K, V>): M {
    for (e in this) { val p = transform(e); destination.put(p.first, p.second) }
    return destination
}
public inline fun <T, K, M : MutableMap<in K, in T>> Array<out T>.associateByTo(destination: M, keySelector: (T) -> K): M {
    for (e in this) destination.put(keySelector(e), e)
    return destination
}
public inline fun <T, K, V> Array<out T>.associateBy(keySelector: (T) -> K, valueTransform: (T) -> V): Map<K, V> {
    val m = LinkedHashMap<K, V>()
    for (e in this) m.put(keySelector(e), valueTransform(e))
    return m
}
public inline fun <T, V, M : MutableMap<in T, in V>> Array<out T>.associateWithTo(destination: M, valueSelector: (T) -> V): M {
    for (e in this) destination.put(e, valueSelector(e))
    return destination
}
public inline fun <T, K, V> Array<out T>.groupBy(keySelector: (T) -> K, valueTransform: (T) -> V): Map<K, List<V>> {
    val m = LinkedHashMap<K, MutableList<V>>()
    for (e in this) {
        val k = keySelector(e)
        var l = m[k]
        if (l == null) { l = ArrayList<V>(); m[k] = l }
        l.add(valueTransform(e))
    }
    return m
}
public inline fun <T, K, M : MutableMap<in K, MutableList<T>>> Array<out T>.groupByTo(destination: M, keySelector: (T) -> K): M {
    for (e in this) {
        val k = keySelector(e)
        var l = destination[k]
        if (l == null) { l = ArrayList<T>(); destination.put(k, l) }
        l.add(e)
    }
    return destination
}
public inline fun <T, K> Array<out T>.groupingBy(crossinline keySelector: (T) -> K): Grouping<T, K> = toList().groupingBy { keySelector(it) }

public fun <T> Array<out T>.joinTo(
    buffer: StringBuilder,
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: ((T) -> CharSequence)? = null,
): StringBuilder = toList().joinTo(buffer, separator, prefix, postfix, limit, truncated, transform)

public operator fun <T> Array<out T>.component1(): T = this[0]
public operator fun <T> Array<out T>.component2(): T = this[1]
public operator fun <T> Array<out T>.component3(): T = this[2]
public operator fun <T> Array<out T>.component4(): T = this[3]
public operator fun <T> Array<out T>.component5(): T = this[4]

public infix fun <T, R> Array<out T>.zip(other: Iterable<R>): List<Pair<T, R>> {
    val arraySize = size
    val list = ArrayList<Pair<T, R>>()
    var i = 0
    for (element in other) {
        if (i >= arraySize) break
        list.add(Pair(this[i], element))
        i++
    }
    return list
}
public inline fun <T, R, V> Array<out T>.zip(other: Array<out R>, transform: (a: T, b: R) -> V): List<V> {
    val n = if (size < other.size) size else other.size
    val list = ArrayList<V>(n)
    var i = 0
    while (i < n) { list.add(transform(this[i], other[i])); i++ }
    return list
}
public inline fun <T, R, V> Array<out T>.zip(other: Iterable<R>, transform: (a: T, b: R) -> V): List<V> {
    val arraySize = size
    val list = ArrayList<V>()
    var i = 0
    for (element in other) {
        if (i >= arraySize) break
        list.add(transform(this[i], element))
        i++
    }
    return list
}
