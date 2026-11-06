/*
 * Mini stdlib: the long tail of the kotlin.collections functions for Iterable / List / MutableList / Map
 * (the semantics, including the exceptions, follow kotlin-stdlib).
 */
package kotlin.collections

// ---- Iterable: selecting -------------------------------------------------------------------------------

public inline fun <T> Iterable<T>.single(predicate: (T) -> Boolean): T {
    var single: Any? = null
    var found = false
    for (element in this) {
        if (predicate(element)) {
            if (found) throw IllegalArgumentException("Collection contains more than one matching element.")
            single = element
            found = true
        }
    }
    if (!found) throw NoSuchElementException("Collection contains no element matching the predicate.")
    @Suppress("UNCHECKED_CAST")
    return single as T
}

public inline fun <T> Iterable<T>.singleOrNull(predicate: (T) -> Boolean): T? {
    var single: T? = null
    var found = false
    for (element in this) {
        if (predicate(element)) {
            if (found) return null
            single = element
            found = true
        }
    }
    return if (!found) null else single
}

public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterTo(destination: C, predicate: (T) -> Boolean): C {
    for (element in this) if (predicate(element)) destination.add(element)
    return destination
}

public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterNotTo(destination: C, predicate: (T) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterIndexedTo(destination: C, predicate: (index: Int, T) -> Boolean): C {
    var index = 0
    for (element in this) {
        if (predicate(index, element)) destination.add(element)
        index++
    }
    return destination
}

public inline fun <T, S : T> Iterable<T>.filterNotNullTo(destination: MutableCollection<in S>): MutableCollection<in S> {
    for (element in this) if (element != null) {
        @Suppress("UNCHECKED_CAST")
        destination.add(element as S)
    }
    return destination
}

// ---- Iterable: folding ---------------------------------------------------------------------------------

public inline fun <S, T : S> Iterable<T>.reduceOrNull(operation: (acc: S, T) -> S): S? {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return null
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) accumulator = operation(accumulator, iterator.next())
    return accumulator
}

public inline fun <S, T : S> Iterable<T>.reduceIndexedOrNull(operation: (index: Int, acc: S, T) -> S): S? {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return null
    var index = 1
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) {
        accumulator = operation(index, accumulator, iterator.next())
        index++
    }
    return accumulator
}

public inline fun <T> Iterable<T>.sumBy(selector: (T) -> Int): Int {
    var sum = 0
    for (element in this) sum += selector(element)
    return sum
}

public inline fun <T> Iterable<T>.sumByDouble(selector: (T) -> Double): Double {
    var sum = 0.0
    for (element in this) sum += selector(element)
    return sum
}

public fun Iterable<Float>.sum(): Float {
    var sum = 0.0f
    for (element in this) sum += element
    return sum
}

public fun Iterable<Byte>.sum(): Int {
    var sum = 0
    for (element in this) sum += element
    return sum
}

public fun Iterable<Short>.sum(): Int {
    var sum = 0
    for (element in this) sum += element
    return sum
}

// ---- Iterable: windows ---------------------------------------------------------------------------------

public fun <T, R> Iterable<T>.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false, transform: (List<T>) -> R): List<R> {
    checkWindowSizeStep(size, step)
    val list = toList()
    val result = ArrayList<R>()
    var index = 0
    while (index < list.size) {
        val end = index + size
        if (end > list.size) {
            if (!partialWindows) break
            result.add(transform(ArrayList<T>(list.subList(index, list.size))))
        } else {
            result.add(transform(ArrayList<T>(list.subList(index, end))))
        }
        index += step
    }
    return result
}

public fun <T, R> Iterable<T>.chunked(size: Int, transform: (List<T>) -> R): List<R> = windowed(size, size, true, transform)

public inline fun <T, R> Iterable<T>.zipWithNext(transform: (a: T, b: T) -> R): List<R> {
    val iterator = iterator()
    if (!iterator.hasNext()) return emptyList()
    val result = ArrayList<R>()
    var current = iterator.next()
    while (iterator.hasNext()) {
        val next = iterator.next()
        result.add(transform(current, next))
        current = next
    }
    return result
}

internal fun checkWindowSizeStep(size: Int, step: Int) {
    if (size <= 0 || step <= 0) {
        throw IllegalArgumentException(if (size != step) "Both size " + size + " and step " + step + " must be greater than zero." else "size " + size + " must be greater than zero.")
    }
}

// ---- Iterable: zip with arrays -------------------------------------------------------------------------

public infix fun <T, R> Iterable<T>.zip(other: Array<out R>): List<Pair<T, R>> {
    val arraySize = other.size
    val list = ArrayList<Pair<T, R>>()
    var i = 0
    for (element in this) {
        if (i >= arraySize) break
        list.add(Pair(element, other[i]))
        i++
    }
    return list
}

public inline fun <T, R, V> Iterable<T>.zip(other: Array<out R>, transform: (a: T, b: R) -> V): List<V> {
    val arraySize = other.size
    val list = ArrayList<V>()
    var i = 0
    for (element in this) {
        if (i >= arraySize) break
        list.add(transform(element, other[i]))
        i++
    }
    return list
}

// ---- List ----------------------------------------------------------------------------------------------

public operator fun <T> List<T>.component1(): T = get(0)
public operator fun <T> List<T>.component2(): T = get(1)
public operator fun <T> List<T>.component3(): T = get(2)
public operator fun <T> List<T>.component4(): T = get(3)
public operator fun <T> List<T>.component5(): T = get(4)

private fun checkListRange(size: Int, fromIndex: Int, toIndex: Int) {
    if (fromIndex > toIndex) {
        throw IllegalArgumentException("fromIndex (" + fromIndex + ") is greater than toIndex (" + toIndex + ").")
    } else if (fromIndex < 0) {
        throw IndexOutOfBoundsException("fromIndex (" + fromIndex + ") is less than zero.")
    } else if (toIndex > size) {
        throw IndexOutOfBoundsException("toIndex (" + toIndex + ") is greater than size (" + size + ").")
    }
}

public fun <T : Comparable<T>> List<T?>.binarySearch(element: T?, fromIndex: Int = 0, toIndex: Int = size): Int {
    checkListRange(size, fromIndex, toIndex)
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cmp = compareValues(get(mid), element)
        if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}

public fun <T> List<T>.binarySearch(element: T, comparator: Comparator<in T>, fromIndex: Int = 0, toIndex: Int = size): Int {
    checkListRange(size, fromIndex, toIndex)
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cmp = comparator.compare(get(mid), element)
        if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}

public fun <T> List<T>.binarySearch(fromIndex: Int = 0, toIndex: Int = size, comparison: (T) -> Int): Int {
    checkListRange(size, fromIndex, toIndex)
    var low = fromIndex
    var high = toIndex - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cmp = comparison(get(mid))
        if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid
    }
    return -(low + 1)
}

// ---- MutableList / MutableCollection -------------------------------------------------------------------

public fun <T> MutableList<T>.fill(value: T) {
    var i = 0
    while (i < size) { this[i] = value; i++ }
}

public fun <T : Comparable<T>> MutableList<T>.sortDescending() {
    sortWith(Comparator<T> { a, b -> b.compareTo(a) })
}

public inline fun <T, R : Comparable<R>> MutableList<T>.sortByDescending(crossinline selector: (T) -> R?) {
    sortWith(Comparator<T> { a, b -> compareValues(selector(b), selector(a)) })
}

public fun <T> MutableList<T>.addFirst(element: T) {
    add(0, element)
}

public fun <T> MutableList<T>.addLast(element: T) {
    add(element)
}

public fun <T> MutableCollection<in T>.addAll(elements: Array<out T>): Boolean {
    var changed = false
    for (e in elements) if (add(e)) changed = true
    return changed
}

public fun <T> MutableCollection<in T>.addAll(elements: Sequence<T>): Boolean {
    var changed = false
    for (e in elements) if (add(e)) changed = true
    return changed
}

public fun <T> MutableCollection<in T>.removeAll(elements: Array<out T>): Boolean {
    if (elements.isEmpty()) return false
    var changed = false
    for (e in elements) if (remove(e)) changed = true
    return changed
}

public fun <T> MutableCollection<in T>.removeAll(elements: Sequence<T>): Boolean {
    var changed = false
    for (e in elements) if (remove(e)) changed = true
    return changed
}

public fun <T> MutableCollection<in T>.retainAll(elements: Array<out T>): Boolean {
    if (elements.isEmpty()) return removeAll { true }
    val set = HashSet<T>()
    for (e in elements) set.add(e)
    return removeAll { !set.contains(it) }
}

public fun <T> MutableCollection<in T>.retainAll(elements: Iterable<T>): Boolean {
    val set = HashSet<T>()
    for (e in elements) set.add(e)
    return removeAll { !set.contains(it) }
}

public fun <T> MutableCollection<in T>.retainAll(elements: Sequence<T>): Boolean {
    val set = HashSet<T>()
    for (e in elements) set.add(e)
    return removeAll { !set.contains(it) }
}

// ---- builders ------------------------------------------------------------------------------------------

public inline fun <T> buildList(capacity: Int, builderAction: MutableList<T>.() -> Unit): List<T> {
    require(capacity >= 0) { "capacity must be non-negative." }
    val list = ArrayList<T>(capacity)
    list.builderAction()
    return list
}

public inline fun <T> buildSet(capacity: Int, builderAction: MutableSet<T>.() -> Unit): Set<T> {
    require(capacity >= 0) { "capacity must be non-negative." }
    val set = LinkedHashSet<T>()
    set.builderAction()
    return set
}

public inline fun <K, V> buildMap(capacity: Int, builderAction: MutableMap<K, V>.() -> Unit): Map<K, V> {
    require(capacity >= 0) { "capacity must be non-negative." }
    val map = LinkedHashMap<K, V>()
    map.builderAction()
    return map
}

public fun <T : Any> setOfNotNull(element: T?): Set<T> = if (element != null) setOf(element) else emptySet()

public fun <T : Any> setOfNotNull(vararg elements: T?): Set<T> {
    val set = LinkedHashSet<T>()
    for (e in elements) if (e != null) set.add(e)
    return set
}

// ---- Map -----------------------------------------------------------------------------------------------

public inline fun <K, V, R, C : MutableCollection<in R>> Map<out K, V>.mapTo(destination: C, transform: (Map.Entry<K, V>) -> R): C {
    for (item in this) destination.add(transform(item))
    return destination
}

public inline fun <K, V, R : Any, C : MutableCollection<in R>> Map<out K, V>.mapNotNullTo(destination: C, transform: (Map.Entry<K, V>) -> R?): C {
    for (item in this) {
        val v = transform(item)
        if (v != null) destination.add(v)
    }
    return destination
}

// (kotlin-stdlib: <K, V, M : Map<out K, V>> M.onEach(...): M; the type arguments of the lambda are not inferred from the bound here)
public inline fun <K, V> Map<out K, V>.onEach(action: (Map.Entry<K, V>) -> Unit): Map<K, V> {
    for (element in this) action(element)
    @Suppress("UNCHECKED_CAST")
    return this as Map<K, V>
}

public inline fun <K, V> Map<out K, V>.onEachIndexed(action: (index: Int, Map.Entry<K, V>) -> Unit): Map<K, V> {
    var index = 0
    for (element in this) action(index++, element)
    @Suppress("UNCHECKED_CAST")
    return this as Map<K, V>
}

public fun <K, V> Map<out K, V>.maxWithOrNull(comparator: Comparator<in Map.Entry<K, V>>): Map.Entry<K, V>? = entries.maxWithOrNull(comparator)
public fun <K, V> Map<out K, V>.minWithOrNull(comparator: Comparator<in Map.Entry<K, V>>): Map.Entry<K, V>? = entries.minWithOrNull(comparator)
public fun <K, V> Map<out K, V>.maxWith(comparator: Comparator<in Map.Entry<K, V>>): Map.Entry<K, V> = entries.maxWith(comparator)
public fun <K, V> Map<out K, V>.minWith(comparator: Comparator<in Map.Entry<K, V>>): Map.Entry<K, V> = entries.minWith(comparator)

public fun <K, V, M : MutableMap<in K, in V>> Map<out K, V>.toMap(destination: M): M {
    destination.putAll(this)
    return destination
}

public fun <K, V, M : MutableMap<in K, in V>> Iterable<Pair<K, V>>.toMap(destination: M): M {
    for (p in this) destination.put(p.first, p.second)
    return destination
}

public fun <K, V> MutableMap<in K, in V>.putAll(pairs: Array<out Pair<K, V>>) {
    for ((key, value) in pairs) put(key, value)
}

public fun <K, V> MutableMap<in K, in V>.putAll(pairs: Sequence<Pair<K, V>>) {
    for ((key, value) in pairs) put(key, value)
}

// ---- Map.withDefault -----------------------------------------------------------------------------------

internal interface MapWithDefault<K, V> : Map<K, V> {
    val map: Map<K, V>
    fun getOrImplicitDefault(key: K): V
}

private class MapWithDefaultImpl<K, V>(override val map: Map<K, V>, private val default: (key: K) -> V) : MapWithDefault<K, V> {
    override fun equals(other: Any?): Boolean = map == other
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = map.toString()
    override val size: Int get() = map.size
    override fun isEmpty(): Boolean = map.isEmpty()
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun get(key: K): V? = map.get(key)
    override val keys: Set<K> get() = map.keys
    override val values: Collection<V> get() = map.values
    override val entries: Set<Map.Entry<K, V>> get() = map.entries

    override fun getOrImplicitDefault(key: K): V {
        val v = map.get(key)
        if (v == null && !map.containsKey(key)) return default(key)
        @Suppress("UNCHECKED_CAST")
        return v as V
    }
}

private class MutableMapWithDefaultImpl<K, V>(override val map: MutableMap<K, V>, private val default: (key: K) -> V) : MapWithDefault<K, V>, MutableMap<K, V> {
    override fun equals(other: Any?): Boolean = map == other
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = map.toString()
    override val size: Int get() = map.size
    override fun isEmpty(): Boolean = map.isEmpty()
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun get(key: K): V? = map.get(key)
    override val keys: MutableSet<K> get() = map.keys
    override val values: MutableCollection<V> get() = map.values
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries
    override fun put(key: K, value: V): V? = map.put(key, value)
    override fun remove(key: K): V? = map.remove(key)
    override fun putAll(from: Map<out K, V>) { map.putAll(from) }
    override fun clear() { map.clear() }

    override fun getOrImplicitDefault(key: K): V {
        val v = map.get(key)
        if (v == null && !map.containsKey(key)) return default(key)
        @Suppress("UNCHECKED_CAST")
        return v as V
    }
}

public fun <K, V> Map<K, V>.withDefault(defaultValue: (key: K) -> V): Map<K, V> =
    if (this is MapWithDefault<K, V>) this.map.withDefault(defaultValue) else MapWithDefaultImpl<K, V>(this, defaultValue)

@kotlin.jvm.JvmName("withDefaultMutable")
public fun <K, V> MutableMap<K, V>.withDefault(defaultValue: (key: K) -> V): MutableMap<K, V> =
    if (this is MutableMapWithDefaultImpl<K, V>) this.map.withDefault(defaultValue) else MutableMapWithDefaultImpl<K, V>(this, defaultValue)
