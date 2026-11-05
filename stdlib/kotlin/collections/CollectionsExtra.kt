/*
 * More kotlin.collections: scans, onEach, *To variants, maxWith/minWith, grouping, MutableMap helpers, removeIf, ...
 */
package kotlin.collections

// ---- Iterable ----------------------------------------------------------------------------------------

public inline fun <T> Iterable<T>.onEach(action: (T) -> Unit): Iterable<T> {
    for (e in this) action(e)
    return this
}

public inline fun <T> Iterable<T>.onEachIndexed(action: (index: Int, T) -> Unit): Iterable<T> {
    var i = 0
    for (e in this) action(i++, e)
    return this
}

public inline fun <T> Collection<T>.onEach(action: (T) -> Unit): Collection<T> {
    for (e in this) action(e)
    return this
}

public inline fun <T> Collection<T>.onEachIndexed(action: (index: Int, T) -> Unit): Collection<T> {
    var i = 0
    for (e in this) action(i++, e)
    return this
}

public inline fun <T> List<T>.onEach(action: (T) -> Unit): List<T> {
    for (e in this) action(e)
    return this
}

public inline fun <T> List<T>.onEachIndexed(action: (index: Int, T) -> Unit): List<T> {
    var i = 0
    for (e in this) action(i++, e)
    return this
}

public inline fun <T> Set<T>.onEach(action: (T) -> Unit): Set<T> {
    for (e in this) action(e)
    return this
}

public inline fun <T> Set<T>.onEachIndexed(action: (index: Int, T) -> Unit): Set<T> {
    var i = 0
    for (e in this) action(i++, e)
    return this
}

public inline fun <T, R : Any> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (e in this) {
        val r = transform(e)
        if (r != null) return r
    }
    return null
}

public inline fun <T, R : Any> Iterable<T>.firstNotNullOf(transform: (T) -> R?): R {
    for (e in this) {
        val r = transform(e)
        if (r != null) return r
    }
    throw NoSuchElementException("No element of the collection was transformed to a non-null value.")
}

public inline fun <T> List<T>.elementAtOrElse(index: Int, defaultValue: (Int) -> T): T =
    if (index >= 0 && index < size) this[index] else defaultValue(index)

public inline fun <T, R : Any> Iterable<T>.mapIndexedNotNull(transform: (index: Int, T) -> R?): List<R> {
    val r = ArrayList<R>()
    var i = 0
    for (e in this) {
        val v = transform(i++, e)
        if (v != null) r.add(v)
    }
    return r
}

public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.mapTo(destination: C, transform: (T) -> R): C {
    for (e in this) destination.add(transform(e))
    return destination
}

public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.mapIndexedTo(destination: C, transform: (index: Int, T) -> R): C {
    var i = 0
    for (e in this) destination.add(transform(i++, e))
    return destination
}

public inline fun <T, R : Any, C : MutableCollection<in R>> Iterable<T>.mapNotNullTo(destination: C, transform: (T) -> R?): C {
    for (e in this) {
        val v = transform(e)
        if (v != null) destination.add(v)
    }
    return destination
}

public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.flatMapTo(destination: C, transform: (T) -> Iterable<R>): C {
    for (e in this) destination.addAll(transform(e))
    return destination
}

public inline fun <T, R> Iterable<T>.flatMapIndexed(transform: (index: Int, T) -> Iterable<R>): List<R> {
    val r = ArrayList<R>()
    var i = 0
    for (e in this) r.addAll(transform(i++, e))
    return r
}

public fun <T, C : MutableCollection<in T>> Iterable<Iterable<T>>.flattenTo(destination: C): C {
    for (e in this) destination.addAll(e)
    return destination
}

public fun <T : Any, C : MutableCollection<in T>> Iterable<T?>.filterNotNullTo(destination: C): C {
    for (e in this) if (e != null) destination.add(e)
    return destination
}

public inline fun <T, K, V, M : MutableMap<in K, in V>> Iterable<T>.associateTo(destination: M, transform: (T) -> Pair<K, V>): M {
    for (e in this) {
        val p = transform(e)
        destination.put(p.first, p.second)
    }
    return destination
}

public inline fun <T, K, M : MutableMap<in K, in T>> Iterable<T>.associateByTo(destination: M, keySelector: (T) -> K): M {
    for (e in this) destination.put(keySelector(e), e)
    return destination
}

public inline fun <T, K, M : MutableMap<in K, MutableList<T>>> Iterable<T>.groupByTo(destination: M, keySelector: (T) -> K): M {
    for (e in this) {
        val key = keySelector(e)
        var list = destination.get(key)
        if (list == null) {
            list = ArrayList<T>()
            destination.put(key, list)
        }
        list.add(e)
    }
    return destination
}

public inline fun <T, R> List<T>.foldRight(initial: R, operation: (T, acc: R) -> R): R {
    var acc = initial
    var i = size - 1
    while (i >= 0) {
        acc = operation(this[i], acc)
        i--
    }
    return acc
}

public inline fun <T, R> List<T>.foldRightIndexed(initial: R, operation: (index: Int, T, acc: R) -> R): R {
    var acc = initial
    var i = size - 1
    while (i >= 0) {
        acc = operation(i, this[i], acc)
        i--
    }
    return acc
}

public inline fun <S, T : S> List<T>.reduceRight(operation: (T, acc: S) -> S): S {
    var i = size - 1
    if (i < 0) throw UnsupportedOperationException("Empty list can't be reduced.")
    var acc: S = this[i--]
    while (i >= 0) {
        acc = operation(this[i], acc)
        i--
    }
    return acc
}

public inline fun <S, T : S> List<T>.reduceRightOrNull(operation: (T, acc: S) -> S): S? {
    var i = size - 1
    if (i < 0) return null
    var acc: S = this[i--]
    while (i >= 0) {
        acc = operation(this[i], acc)
        i--
    }
    return acc
}

public inline fun <S, T : S> Iterable<T>.reduceIndexed(operation: (index: Int, acc: S, T) -> S): S {
    val iterator = this.iterator()
    if (!iterator.hasNext()) throw UnsupportedOperationException("Empty collection can't be reduced.")
    var index = 1
    var acc: S = iterator.next()
    while (iterator.hasNext()) acc = operation(index++, acc, iterator.next())
    return acc
}

public inline fun <S, T : S> Iterable<T>.runningReduce(operation: (acc: S, T) -> S): List<S> {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return emptyList()
    var accumulator: S = iterator.next()
    val result = ArrayList<S>()
    result.add(accumulator)
    while (iterator.hasNext()) {
        accumulator = operation(accumulator, iterator.next())
        result.add(accumulator)
    }
    return result
}

public inline fun <T, R> Iterable<T>.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R> {
    val result = ArrayList<R>()
    var accumulator = initial
    result.add(accumulator)
    var index = 0
    for (e in this) {
        accumulator = operation(index++, accumulator, e)
        result.add(accumulator)
    }
    return result
}

public inline fun <T, R> Iterable<T>.scan(initial: R, operation: (acc: R, T) -> R): List<R> = runningFold(initial, operation)

public inline fun <T, R> Iterable<T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): List<R> = runningFoldIndexed(initial, operation)

public fun <T> Iterable<T>.maxWithOrNull(comparator: Comparator<in T>): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var max = iterator.next()
    while (iterator.hasNext()) {
        val e = iterator.next()
        if (comparator.compare(max, e) < 0) max = e
    }
    return max
}

public fun <T> Iterable<T>.minWithOrNull(comparator: Comparator<in T>): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var min = iterator.next()
    while (iterator.hasNext()) {
        val e = iterator.next()
        if (comparator.compare(min, e) > 0) min = e
    }
    return min
}

public fun <T> Iterable<T>.maxWith(comparator: Comparator<in T>): T = maxWithOrNull(comparator) ?: throw NoSuchElementException()

public fun <T> Iterable<T>.minWith(comparator: Comparator<in T>): T = minWithOrNull(comparator) ?: throw NoSuchElementException()

public inline fun <T, R : Comparable<R>> Iterable<T>.maxOf(selector: (T) -> R): R {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException()
    var max = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (max < v) max = v
    }
    return max
}

public inline fun <T, R : Comparable<R>> Iterable<T>.minOf(selector: (T) -> R): R {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException()
    var min = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (min > v) min = v
    }
    return min
}

public inline fun <T, R : Comparable<R>> Iterable<T>.maxOfOrNull(selector: (T) -> R): R? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var max = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (max < v) max = v
    }
    return max
}

public inline fun <T, R : Comparable<R>> Iterable<T>.minOfOrNull(selector: (T) -> R): R? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var min = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (min > v) min = v
    }
    return min
}

public inline fun <T, R> Iterable<T>.maxOfWith(comparator: Comparator<in R>, selector: (T) -> R): R {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException()
    var max = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (comparator.compare(max, v) < 0) max = v
    }
    return max
}

public inline fun <T, R> Iterable<T>.minOfWith(comparator: Comparator<in R>, selector: (T) -> R): R {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException()
    var min = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (comparator.compare(min, v) > 0) min = v
    }
    return min
}

public fun <T> Iterable<T>.minusElement(element: T): List<T> = minus(element)

public fun <T> Collection<T>.plusElement(element: T): List<T> = plus(element)

public fun <T : Any> Iterable<T?>.requireNoNulls(): Iterable<T> {
    for (e in this) if (e == null) throw IllegalArgumentException("null element found in " + this + ".")
    @Suppress("UNCHECKED_CAST")
    return this as Iterable<T>
}

public fun <T : Comparable<T>> Iterable<T>.toSortedSet(): java.util.TreeSet<T> {
    val s = java.util.TreeSet<T>()
    for (e in this) s.add(e)
    return s
}

public inline fun <T, R : Comparable<R>> List<T>.binarySearchBy(key: R?, fromIndex: Int = 0, toIndex: Int = size, crossinline selector: (T) -> R?): Int {
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cmp = compareValues(selector(this[mid]), key)
        if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}

// ---- MutableCollection ------------------------------------------------------------------------------

public fun <T> MutableCollection<T>.removeIf(filter: (T) -> Boolean): Boolean = removeAll(filter)

// ---- Grouping -----------------------------------------------------------------------------------------

public interface Grouping<T, out K> {
    public fun sourceIterator(): Iterator<T>
    public fun keyOf(element: T): K
}

private class KeyGrouping<T, K>(private val source: Iterable<T>, private val keySelector: (T) -> K) : Grouping<T, K> {
    override fun sourceIterator(): Iterator<T> = source.iterator()
    override fun keyOf(element: T): K = keySelector(element)
}

public fun <T, K> Iterable<T>.groupingBy(keySelector: (T) -> K): Grouping<T, K> = KeyGrouping(this, keySelector)

public fun <T, K> Grouping<T, K>.eachCount(): Map<K, Int> {
    val m = LinkedHashMap<K, Int>()
    val it0 = sourceIterator()
    while (it0.hasNext()) {
        val k = keyOf(it0.next())
        m[k] = (m[k] ?: 0) + 1
    }
    return m
}

public inline fun <T, K, R> Grouping<T, K>.fold(initialValue: R, operation: (accumulator: R, element: T) -> R): Map<K, R> {
    val m = LinkedHashMap<K, R>()
    val it0 = sourceIterator()
    while (it0.hasNext()) {
        val e = it0.next()
        val k = keyOf(e)
        val acc = if (m.containsKey(k)) (m[k] as R) else initialValue
        m[k] = operation(acc, e)
    }
    return m
}

public inline fun <S, T : S, K> Grouping<T, K>.reduce(operation: (key: K, accumulator: S, element: T) -> S): Map<K, S> {
    val m = LinkedHashMap<K, S>()
    val it0 = sourceIterator()
    while (it0.hasNext()) {
        val e = it0.next()
        val k = keyOf(e)
        if (m.containsKey(k)) {
            m[k] = operation(k, m[k] as S, e)
        } else {
            m[k] = e
        }
    }
    return m
}

public inline fun <T, K, R> Grouping<T, K>.aggregate(operation: (key: K, accumulator: R?, element: T, first: Boolean) -> R): Map<K, R> {
    val m = LinkedHashMap<K, R>()
    val it0 = sourceIterator()
    while (it0.hasNext()) {
        val e = it0.next()
        val k = keyOf(e)
        val first = !m.containsKey(k)
        m[k] = operation(k, if (first) null else (m[k] as R), e, first)
    }
    return m
}

// ---- Map -----------------------------------------------------------------------------------------------

public inline fun <K, V, R, C : MutableMap<in R, in V>> Map<out K, V>.mapKeysTo(destination: C, transform: (Map.Entry<K, V>) -> R): C {
    for (e in this) destination.put(transform(e), e.value)
    return destination
}

public inline fun <K, V, R, C : MutableMap<in K, in R>> Map<out K, V>.mapValuesTo(destination: C, transform: (Map.Entry<K, V>) -> R): C {
    for (e in this) destination.put(e.key, transform(e))
    return destination
}

public inline fun <K, V> Map<out K, V>.filterNot(predicate: (Map.Entry<K, V>) -> Boolean): Map<K, V> {
    val r = LinkedHashMap<K, V>()
    for (e in this) if (!predicate(e)) r.put(e.key, e.value)
    return r
}

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.maxBy(selector: (Map.Entry<K, V>) -> R): Map.Entry<K, V> =
    maxByOrNull(selector) ?: throw NoSuchElementException()

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.minBy(selector: (Map.Entry<K, V>) -> R): Map.Entry<K, V> =
    minByOrNull(selector) ?: throw NoSuchElementException()

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.maxOf(selector: (Map.Entry<K, V>) -> R): R = entries.maxOf(selector)

public inline fun <K, V, R : Comparable<R>> Map<out K, V>.minOf(selector: (Map.Entry<K, V>) -> R): R = entries.minOf(selector)

@kotlin.jvm.JvmName("sumOfIntMap")
public inline fun <K, V> Map<out K, V>.sumOf(selector: (Map.Entry<K, V>) -> Int): Int {
    var s = 0
    for (e in this) s += selector(e)
    return s
}

@kotlin.jvm.JvmName("sumOfLongMap")
public inline fun <K, V> Map<out K, V>.sumOf(selector: (Map.Entry<K, V>) -> Long): Long {
    var s = 0L
    for (e in this) s += selector(e)
    return s
}

@kotlin.jvm.JvmName("sumOfDoubleMap")
public inline fun <K, V> Map<out K, V>.sumOf(selector: (Map.Entry<K, V>) -> Double): Double {
    var s = 0.0
    for (e in this) s += selector(e)
    return s
}

public inline fun <K, V, R : Any> Map<out K, V>.firstNotNullOfOrNull(transform: (Map.Entry<K, V>) -> R?): R? {
    for (e in this) {
        val r = transform(e)
        if (r != null) return r
    }
    return null
}

public inline fun <K, V, R : Any> Map<out K, V>.firstNotNullOf(transform: (Map.Entry<K, V>) -> R?): R =
    firstNotNullOfOrNull(transform) ?: throw NoSuchElementException("No element of the map was transformed to a non-null value.")

public inline fun <K, V, R> Map<out K, V>.flatMapTo(destination: MutableCollection<in R>, transform: (Map.Entry<K, V>) -> Iterable<R>): MutableCollection<in R> {
    for (e in this) destination.addAll(transform(e))
    return destination
}

public inline fun <K, V> MutableMap<K, V>.computeIfAbsent(key: K, mappingFunction: (K) -> V): V {
    val v = get(key)
    if (v != null) return v
    val n = mappingFunction(key)
    put(key, n)
    return n
}

public inline fun <K, V> MutableMap<K, V>.computeIfPresent(key: K, remappingFunction: (K, V) -> V?): V? {
    val old = get(key)
    if (old == null) return null
    val n = remappingFunction(key, old)
    if (n == null) remove(key) else put(key, n)
    return n
}

public inline fun <K, V> MutableMap<K, V>.compute(key: K, remappingFunction: (K, V?) -> V?): V? {
    val old = get(key)
    val n = remappingFunction(key, old)
    if (n == null) {
        if (old != null || containsKey(key)) remove(key)
        return null
    }
    put(key, n)
    return n
}

public inline fun <K, V> MutableMap<K, V>.merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? {
    val old = get(key)
    val n = if (old == null) value else remappingFunction(old, value)
    if (n == null) remove(key) else put(key, n)
    return n
}

public fun <K, V> MutableMap<K, V>.replace(key: K, value: V): V? = if (containsKey(key)) put(key, value) else null

// ---- Pair / Triple --------------------------------------------------------------------------------------

public fun <T> Pair<T, T>.toList(): List<T> = listOf(first, second)

public fun <T> Triple<T, T, T>.toList(): List<T> = listOf(first, second, third)

// ---- sorted collections (java.util.TreeMap / TreeSet) ---------------------------------------------------

public fun <K : Comparable<K>, V> sortedMapOf(vararg pairs: Pair<K, V>): java.util.TreeMap<K, V> {
    val m = java.util.TreeMap<K, V>()
    for (p in pairs) m.put(p.first, p.second)
    return m
}

public fun <K, V> Map<out K, V>.toSortedMap(): java.util.TreeMap<K, V> {
    val m = java.util.TreeMap<K, V>()
    for (e in this) m.put(e.key, e.value)
    return m
}

public fun <K, V> Map<out K, V>.toSortedMap(comparator: Comparator<in K>): java.util.TreeMap<K, V> {
    val m = java.util.TreeMap<K, V>(comparator)
    for (e in this) m.put(e.key, e.value)
    return m
}

public fun <E : Comparable<E>> sortedSetOf(vararg elements: E): java.util.TreeSet<E> {
    val s = java.util.TreeSet<E>()
    for (e in elements) s.add(e)
    return s
}

public fun <T> Iterable<T>.toSortedSet(comparator: Comparator<in T>): java.util.TreeSet<T> {
    val s = java.util.TreeSet<T>(comparator)
    for (e in this) s.add(e)
    return s
}

public inline fun <E> buildSet(builderAction: MutableSet<E>.() -> Unit): Set<E> {
    val set = LinkedHashSet<E>()
    set.builderAction()
    return set
}
