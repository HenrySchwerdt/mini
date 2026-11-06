/*
 * Mini stdlib: more kotlin.sequences operators. windowed / chunked / zipWithNext / runningFold / runningReduce / scan are lazy
 * (they pull from the source only as far as the consumer asks: they work on infinite sequences), the rest is the long tail of the
 * terminal operations. The semantics, including the exceptions, follow kotlin-stdlib.
 */
package kotlin.sequences

// ---- windows -------------------------------------------------------------------------------------------

private class WindowedIterator<T>(
    private val src: Iterator<T>,
    private val size: Int,
    private val step: Int,
    private val partialWindows: Boolean,
) : Iterator<List<T>> {
    private var buffer = ArrayList<T>(size)
    private var skip = 0
    private var pending: List<T>? = null
    private var finished = false
    private var drained = false

    private fun compute() {
        if (pending != null || finished) return
        if (step >= size) {
            while (src.hasNext()) {
                val e = src.next()
                if (skip > 0) { skip--; continue }
                buffer.add(e)
                if (buffer.size == size) {
                    pending = buffer
                    buffer = ArrayList<T>(size)
                    skip = step - size
                    return
                }
            }
            finished = true
            if (buffer.isNotEmpty() && (partialWindows || buffer.size == size)) pending = buffer
        } else {
            if (!drained) {
                while (src.hasNext()) {
                    buffer.add(src.next())
                    if (buffer.size == size) {
                        pending = ArrayList<T>(buffer)
                        removeFront(step)
                        return
                    }
                }
                drained = true
            }
            if (partialWindows) {
                if (buffer.size > step) {
                    pending = ArrayList<T>(buffer)
                    removeFront(step)
                    return
                }
                finished = true
                if (buffer.isNotEmpty()) pending = buffer
            } else {
                finished = true
            }
        }
    }

    private fun removeFront(n: Int) {
        val rest = ArrayList<T>(size)
        var i = n
        while (i < buffer.size) { rest.add(buffer[i]); i++ }
        buffer = rest
    }

    override fun hasNext(): Boolean {
        compute()
        return pending != null
    }

    override fun next(): List<T> {
        compute()
        val r = pending ?: throw NoSuchElementException()
        pending = null
        return r
    }
}

private class WindowedSequence<T>(private val s: Sequence<T>, private val size: Int, private val step: Int, private val partialWindows: Boolean) : Sequence<List<T>> {
    override fun iterator(): Iterator<List<T>> {
        val it0 = s.iterator()
        if (!it0.hasNext()) return emptyList<List<T>>().iterator()
        return WindowedIterator(it0, size, step, partialWindows)
    }
}

public fun <T> Sequence<T>.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false): Sequence<List<T>> {
    kotlin.collections.checkWindowSizeStep(size, step)
    return WindowedSequence(this, size, step, partialWindows)
}

public fun <T, R> Sequence<T>.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false, transform: (List<T>) -> R): Sequence<R> =
    windowed(size, step, partialWindows).map(transform)

public fun <T> Sequence<T>.chunked(size: Int): Sequence<List<T>> = windowed(size, size, true)

public fun <T, R> Sequence<T>.chunked(size: Int, transform: (List<T>) -> R): Sequence<R> = windowed(size, size, true, transform)

// ---- zipWithNext, running folds --------------------------------------------------------------------------

private class ZipWithNextIterator<T, R>(private val src: Iterator<T>, private val f: (T, T) -> R) : Iterator<R> {
    private var current: Any? = null
    private var state = 0 // 0: not started, 1: current holds an element, 2: empty source

    private fun init() {
        if (state == 0) {
            if (src.hasNext()) { current = src.next(); state = 1 } else state = 2
        }
    }

    override fun hasNext(): Boolean {
        init()
        return state == 1 && src.hasNext()
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): R {
        if (!hasNext()) throw NoSuchElementException()
        val n = src.next()
        val r = f(current as T, n)
        current = n
        return r
    }
}

private class ZipWithNextSequence<T, R>(private val s: Sequence<T>, private val f: (T, T) -> R) : Sequence<R> {
    override fun iterator(): Iterator<R> = ZipWithNextIterator(s.iterator(), f)
}

public fun <T, R> Sequence<T>.zipWithNext(transform: (a: T, b: T) -> R): Sequence<R> = ZipWithNextSequence(this, transform)

public fun <T> Sequence<T>.zipWithNext(): Sequence<Pair<T, T>> = ZipWithNextSequence(this) { a, b -> Pair(a, b) }

private class RunningFoldIterator<T, R>(
    private val src: Iterator<T>,
    private val initial: R,
    private val withInitial: Boolean,
    private val f: (Int, R, T) -> R,
) : Iterator<R> {
    private var acc: Any? = initial
    private var index = 0
    private var state = 0 // 0: nothing yielded yet

    override fun hasNext(): Boolean = (state == 0 && withInitial) || src.hasNext()

    @Suppress("UNCHECKED_CAST")
    override fun next(): R {
        if (state == 0) {
            state = 1
            if (withInitial) return initial
        }
        if (!src.hasNext()) throw NoSuchElementException()
        val e = src.next()
        // runningReduce: the first element is the accumulator (index 0 is not passed to the operation)
        val a = if (!withInitial && index == 0) e else f(index, acc as R, e)
        acc = a
        index++
        return a as R
    }
}

private class RunningFoldSequence<T, R>(
    private val s: Sequence<T>,
    private val initial: R,
    private val withInitial: Boolean,
    private val f: (Int, R, T) -> R,
) : Sequence<R> {
    override fun iterator(): Iterator<R> = RunningFoldIterator(s.iterator(), initial, withInitial, f)
}

public fun <T, R> Sequence<T>.runningFold(initial: R, operation: (acc: R, T) -> R): Sequence<R> =
    RunningFoldSequence<T, R>(this, initial, true) { _, acc, e -> operation(acc, e) }

public fun <T, R> Sequence<T>.scan(initial: R, operation: (acc: R, T) -> R): Sequence<R> = runningFold(initial, operation)

public fun <T, R> Sequence<T>.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): Sequence<R> =
    RunningFoldSequence<T, R>(this, initial, true, operation)

public fun <T, R> Sequence<T>.scanIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): Sequence<R> = runningFoldIndexed(initial, operation)

@Suppress("UNCHECKED_CAST")
public fun <S, T : S> Sequence<T>.runningReduce(operation: (acc: S, T) -> S): Sequence<S> =
    RunningFoldSequence<T, S>(this, null as S, false) { _, acc, e -> operation(acc, e) }

@Suppress("UNCHECKED_CAST")
public fun <S, T : S> Sequence<T>.runningReduceIndexed(operation: (index: Int, acc: S, T) -> S): Sequence<S> =
    RunningFoldSequence<T, S>(this, null as S, false, operation)

// ---- selecting ---------------------------------------------------------------------------------------------

public inline fun <reified R> Sequence<*>.filterIsInstance(): Sequence<R> {
    @Suppress("UNCHECKED_CAST")
    return filter { it is R } as Sequence<R>
}

public inline fun <reified R, C : MutableCollection<in R>> Sequence<*>.filterIsInstanceTo(destination: C): C {
    for (element in this) if (element is R) destination.add(element)
    return destination
}

public inline fun <T> Sequence<T>.last(predicate: (T) -> Boolean): T {
    var last: Any? = null
    var found = false
    for (element in this) {
        if (predicate(element)) {
            last = element
            found = true
        }
    }
    if (!found) throw NoSuchElementException("Sequence contains no element matching the predicate.")
    @Suppress("UNCHECKED_CAST")
    return last as T
}

public inline fun <T> Sequence<T>.findLast(predicate: (T) -> Boolean): T? = lastOrNull(predicate)

public inline fun <T> Sequence<T>.single(predicate: (T) -> Boolean): T {
    var single: Any? = null
    var found = false
    for (element in this) {
        if (predicate(element)) {
            if (found) throw IllegalArgumentException("Sequence contains more than one matching element.")
            single = element
            found = true
        }
    }
    if (!found) throw NoSuchElementException("Sequence contains no element matching the predicate.")
    @Suppress("UNCHECKED_CAST")
    return single as T
}

public inline fun <T> Sequence<T>.singleOrNull(predicate: (T) -> Boolean): T? {
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

public inline fun <T> Sequence<T>.elementAtOrElse(index: Int, defaultValue: (Int) -> T): T {
    if (index < 0) return defaultValue(index)
    val iterator = iterator()
    var count = 0
    while (iterator.hasNext()) {
        val element = iterator.next()
        if (index == count++) return element
    }
    return defaultValue(index)
}

public inline fun <T> Sequence<T>.indexOfFirst(predicate: (T) -> Boolean): Int {
    var index = 0
    for (item in this) {
        if (predicate(item)) return index
        index++
    }
    return -1
}

public inline fun <T> Sequence<T>.indexOfLast(predicate: (T) -> Boolean): Int {
    var lastIndex = -1
    var index = 0
    for (item in this) {
        if (predicate(item)) lastIndex = index
        index++
    }
    return lastIndex
}

public fun <T> Sequence<T>.lastIndexOf(element: T): Int {
    var lastIndex = -1
    var index = 0
    for (item in this) {
        if (element == item) lastIndex = index
        index++
    }
    return lastIndex
}

public inline fun <T, R : Any> Sequence<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
    for (element in this) {
        val result = transform(element)
        if (result != null) return result
    }
    return null
}

public inline fun <T, R : Any> Sequence<T>.firstNotNullOf(transform: (T) -> R?): R =
    firstNotNullOfOrNull(transform) ?: throw NoSuchElementException("No element of the sequence was transformed to a non-null value.")

// ---- folding -----------------------------------------------------------------------------------------------

public inline fun <T, R> Sequence<T>.foldIndexed(initial: R, operation: (index: Int, acc: R, T) -> R): R {
    var index = 0
    var accumulator = initial
    for (element in this) accumulator = operation(index++, accumulator, element)
    return accumulator
}

public inline fun <S, T : S> Sequence<T>.reduceIndexed(operation: (index: Int, acc: S, T) -> S): S {
    val iterator = this.iterator()
    if (!iterator.hasNext()) throw UnsupportedOperationException("Empty sequence can't be reduced.")
    var index = 1
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) accumulator = operation(index++, accumulator, iterator.next())
    return accumulator
}

public inline fun <S, T : S> Sequence<T>.reduceOrNull(operation: (acc: S, T) -> S): S? {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return null
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) accumulator = operation(accumulator, iterator.next())
    return accumulator
}

public inline fun <S, T : S> Sequence<T>.reduceIndexedOrNull(operation: (index: Int, acc: S, T) -> S): S? {
    val iterator = this.iterator()
    if (!iterator.hasNext()) return null
    var index = 1
    var accumulator: S = iterator.next()
    while (iterator.hasNext()) accumulator = operation(index++, accumulator, iterator.next())
    return accumulator
}

public inline fun <T> Sequence<T>.sumBy(selector: (T) -> Int): Int {
    var sum = 0
    for (element in this) sum += selector(element)
    return sum
}

public inline fun <T> Sequence<T>.sumByDouble(selector: (T) -> Double): Double {
    var sum = 0.0
    for (element in this) sum += selector(element)
    return sum
}

public fun Sequence<Float>.sum(): Float {
    var sum = 0.0f
    for (element in this) sum += element
    return sum
}

public fun Sequence<Byte>.sum(): Int {
    var sum = 0
    for (element in this) sum += element
    return sum
}

public fun Sequence<Short>.sum(): Int {
    var sum = 0
    for (element in this) sum += element
    return sum
}

public fun <T> Sequence<T>.minWith(comparator: Comparator<in T>): T = minWithOrNull(comparator) ?: throw NoSuchElementException()

public fun <T> Sequence<T>.maxWith(comparator: Comparator<in T>): T = maxWithOrNull(comparator) ?: throw NoSuchElementException()

public inline fun <T, R : Comparable<R>> Sequence<T>.maxOfOrNull(selector: (T) -> R): R? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var maxValue = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (maxValue < v) maxValue = v
    }
    return maxValue
}

public inline fun <T, R : Comparable<R>> Sequence<T>.minOfOrNull(selector: (T) -> R): R? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var minValue = selector(iterator.next())
    while (iterator.hasNext()) {
        val v = selector(iterator.next())
        if (minValue > v) minValue = v
    }
    return minValue
}

// ---- mapping, collecting -----------------------------------------------------------------------------------

public fun <T, R> Sequence<T>.flatMapIndexed(transform: (index: Int, T) -> Iterable<R>): Sequence<R> {
    val s = this
    return Sequence<R> {
        var i = 0
        FlatMapIteratorIndexed(s.iterator()) { x -> transform(i++, x).iterator() }
    }
}

private class FlatMapIteratorIndexed<T, R>(private val it0: Iterator<T>, private val f: (T) -> Iterator<R>) : Iterator<R> {
    private var item: Iterator<R>? = null
    private fun ensure(): Boolean {
        while (true) {
            val cur = item
            if (cur != null && cur.hasNext()) return true
            if (!it0.hasNext()) return false
            item = f(it0.next())
        }
    }
    override fun hasNext(): Boolean = ensure()
    override fun next(): R {
        if (!ensure()) throw NoSuchElementException()
        return item!!.next()
    }
}

public fun <T, C : MutableCollection<in T>> Sequence<T>.toCollection(destination: C): C {
    for (item in this) destination.add(item)
    return destination
}

public fun <T : Comparable<T>> Sequence<T>.toSortedSet(): java.util.TreeSet<T> {
    val r = java.util.TreeSet<T>()
    for (e in this) r.add(e)
    return r
}

public inline fun <T, R, C : MutableCollection<in R>> Sequence<T>.mapTo(destination: C, transform: (T) -> R): C {
    for (item in this) destination.add(transform(item))
    return destination
}

public inline fun <T, R, C : MutableCollection<in R>> Sequence<T>.mapIndexedTo(destination: C, transform: (index: Int, T) -> R): C {
    var index = 0
    for (item in this) destination.add(transform(index++, item))
    return destination
}

public inline fun <T, R : Any, C : MutableCollection<in R>> Sequence<T>.mapNotNullTo(destination: C, transform: (T) -> R?): C {
    for (element in this) {
        val v = transform(element)
        if (v != null) destination.add(v)
    }
    return destination
}

public inline fun <T, R, C : MutableCollection<in R>> Sequence<T>.flatMapTo(destination: C, transform: (T) -> Sequence<R>): C {
    for (element in this) destination.addAll(transform(element))
    return destination
}

public inline fun <T, C : MutableCollection<in T>> Sequence<T>.filterTo(destination: C, predicate: (T) -> Boolean): C {
    for (element in this) if (predicate(element)) destination.add(element)
    return destination
}

public inline fun <T, C : MutableCollection<in T>> Sequence<T>.filterNotTo(destination: C, predicate: (T) -> Boolean): C {
    for (element in this) if (!predicate(element)) destination.add(element)
    return destination
}

public inline fun <T, C : MutableCollection<in T>> Sequence<T>.filterIndexedTo(destination: C, predicate: (index: Int, T) -> Boolean): C {
    var index = 0
    for (element in this) {
        if (predicate(index++, element)) destination.add(element)
    }
    return destination
}

public inline fun <T, K, V, M : MutableMap<in K, in V>> Sequence<T>.associateTo(destination: M, transform: (T) -> Pair<K, V>): M {
    for (element in this) {
        val p = transform(element)
        destination.put(p.first, p.second)
    }
    return destination
}

public inline fun <T, K, M : MutableMap<in K, in T>> Sequence<T>.associateByTo(destination: M, keySelector: (T) -> K): M {
    for (element in this) destination.put(keySelector(element), element)
    return destination
}

public inline fun <T, V, M : MutableMap<in T, in V>> Sequence<T>.associateWithTo(destination: M, valueSelector: (T) -> V): M {
    for (element in this) destination.put(element, valueSelector(element))
    return destination
}

private class SequenceGrouping<T, K>(private val source: Sequence<T>, private val keySelector: (T) -> K) : Grouping<T, K> {
    override fun sourceIterator(): Iterator<T> = source.iterator()
    override fun keyOf(element: T): K = keySelector(element)
}

public fun <T, K> Sequence<T>.groupingBy(keySelector: (T) -> K): Grouping<T, K> = SequenceGrouping(this, keySelector)

public fun <T, R> Sequence<Pair<T, R>>.unzip(): Pair<List<T>, List<R>> {
    val listT = ArrayList<T>()
    val listR = ArrayList<R>()
    for (pair in this) {
        listT.add(pair.first)
        listR.add(pair.second)
    }
    return Pair(listT, listR)
}

public fun <T> Sequence<T>.minus(elements: Iterable<T>): Sequence<T> {
    val other = elements.toHashSet()
    if (other.isEmpty()) return this
    return filter { !other.contains(it) }
}

public fun <T> Sequence<T>.minus(elements: Sequence<T>): Sequence<T> {
    val s = this
    return Sequence<T> {
        val other = elements.toHashSet()
        if (other.isEmpty()) s.iterator() else s.filter { !other.contains(it) }.iterator()
    }
}

public fun <T> Sequence<T>.minus(elements: Array<out T>): Sequence<T> {
    if (elements.isEmpty()) return this
    val other = elements.toHashSet()
    return filter { !other.contains(it) }
}

public fun <T> Iterator<T>.forEach(operation: (T) -> Unit) {
    while (hasNext()) operation(next())
}
