/*
 * kotlin.sequences: lazy Sequence<T> with the usual operators, and the `sequence { }` / `iterator { }` builders, which run
 * on the suspend-function state machines of the compiler (docs/coroutines.md).
 */
package kotlin.sequences

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.coroutines.intrinsics.SuspendMarker

public interface Sequence<out T> {
    public operator fun iterator(): Iterator<T>
}

private class IterableSequence<T>(private val src: Iterable<T>) : Sequence<T> {
    override fun iterator(): Iterator<T> = src.iterator()
}

private class IteratorSequence<T>(private val it0: Iterator<T>) : Sequence<T> {
    override fun iterator(): Iterator<T> = it0
}

private object EmptyIterator : Iterator<Nothing> {
    override fun hasNext(): Boolean = false
    override fun next(): Nothing = throw NoSuchElementException()
}

public fun <T> Iterable<T>.asSequence(): Sequence<T> = IterableSequence(this)
public fun <T> Iterator<T>.asSequence(): Sequence<T> = IteratorSequence(this)
public fun <T> Sequence<T>.asIterable(): Iterable<T> = SequenceIterable(this)

private class SequenceIterable<T>(private val s: Sequence<T>) : Iterable<T> {
    override fun iterator(): Iterator<T> = s.iterator()
}

public fun <T> emptySequence(): Sequence<T> = IterableSequence(emptyList<T>())
public fun <T> sequenceOf(vararg elements: T): Sequence<T> = IterableSequence(elements.asList())
public fun <T> Sequence<T>.constrainOnce(): Sequence<T> = this
public fun <T> Sequence<T>?.orEmpty(): Sequence<T> = this ?: emptySequence()

// ---- generators ------------------------------------------------------------------------------

private class GeneratorIterator<T : Any>(private val getNext: () -> T?, private var nextItem: T?) : Iterator<T> {
    private var computed = nextItem != null
    private var done = false
    override fun hasNext(): Boolean {
        if (!computed && !done) {
            nextItem = getNext()
            if (nextItem == null) done = true else computed = true
        }
        return computed
    }
    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        val r = nextItem as T
        nextItem = null
        computed = false
        return r
    }
}

private class GeneratorSequence<T : Any>(private val getInitial: (() -> T?)?, private val getNext: (T) -> T?) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val init = if (getInitial == null) null else getInitial.invoke()
        var cur: T? = init
        var started = false
        return GeneratorIterator<T>({
            if (!started) { started = true; cur } else {
                val c = cur
                if (c == null) null else { cur = getNext(c); cur }
            }
        }, null)
    }
}

public fun <T : Any> generateSequence(nextFunction: () -> T?): Sequence<T> {
    return IteratorSequence(GeneratorIterator<T>(nextFunction, null))
}

public fun <T : Any> generateSequence(seed: T?, nextFunction: (T) -> T?): Sequence<T> {
    return if (seed == null) emptySequence() else GeneratorSequence<T>({ seed }, nextFunction)
}

public fun <T : Any> generateSequence(seedFunction: () -> T?, nextFunction: (T) -> T?): Sequence<T> =
    GeneratorSequence<T>(seedFunction, nextFunction)

// ---- intermediate operations ------------------------------------------------------------------

private class MapSequence<T, R>(private val s: Sequence<T>, private val f: (T) -> R) : Sequence<R> {
    override fun iterator(): Iterator<R> = MapIterator(s.iterator(), f)
}
private class MapIterator<T, R>(private val it0: Iterator<T>, private val f: (T) -> R) : Iterator<R> {
    override fun hasNext(): Boolean = it0.hasNext()
    override fun next(): R = f(it0.next())
}

private class MapIndexedSequence<T, R>(private val s: Sequence<T>, private val f: (Int, T) -> R) : Sequence<R> {
    override fun iterator(): Iterator<R> = MapIndexedIterator(s.iterator(), f)
}
private class MapIndexedIterator<T, R>(private val it0: Iterator<T>, private val f: (Int, T) -> R) : Iterator<R> {
    private var index = 0
    override fun hasNext(): Boolean = it0.hasNext()
    override fun next(): R = f(index++, it0.next())
}

/** filter / filterNot / filterIndexed in one class: keeps elements where [pred] (index, element) equals [sendWhen] */
private class FilterSequence<T>(private val s: Sequence<T>, private val sendWhen: Boolean, private val pred: (Int, T) -> Boolean) : Sequence<T> {
    override fun iterator(): Iterator<T> = FilterIterator(s.iterator(), sendWhen, pred)
}
private class FilterIterator<T>(private val it0: Iterator<T>, private val sendWhen: Boolean, private val pred: (Int, T) -> Boolean) : Iterator<T> {
    private var state = -1 // -1 unknown, 0 done, 1 has next
    private var nextItem: T? = null
    private var index = 0
    private fun calc() {
        while (it0.hasNext()) {
            val item = it0.next()
            if (pred(index++, item) == sendWhen) {
                nextItem = item
                state = 1
                return
            }
        }
        state = 0
    }
    override fun hasNext(): Boolean {
        if (state == -1) calc()
        return state == 1
    }
    override fun next(): T {
        if (state == -1) calc()
        if (state == 0) throw NoSuchElementException()
        val r = nextItem as T
        nextItem = null
        state = -1
        return r
    }
}

public fun <T, R> Sequence<T>.map(transform: (T) -> R): Sequence<R> = MapSequence(this, transform)
public fun <T, R> Sequence<T>.mapIndexed(transform: (index: Int, T) -> R): Sequence<R> = MapIndexedSequence(this, transform)
public fun <T> Sequence<T>.filter(predicate: (T) -> Boolean): Sequence<T> = FilterSequence(this, true) { _, x -> predicate(x) }
public fun <T> Sequence<T>.filterNot(predicate: (T) -> Boolean): Sequence<T> = FilterSequence(this, false) { _, x -> predicate(x) }
public fun <T> Sequence<T>.filterIndexed(predicate: (index: Int, T) -> Boolean): Sequence<T> = FilterSequence(this, true, predicate)
public fun <T : Any> Sequence<T?>.filterNotNull(): Sequence<T> {
    @Suppress("UNCHECKED_CAST")
    return FilterSequence(this, true) { _, x -> x != null } as Sequence<T>
}
public fun <T, R : Any> Sequence<T>.mapNotNull(transform: (T) -> R?): Sequence<R> = MapSequence(this, transform).filterNotNull()
public fun <T, R : Any> Sequence<T>.mapIndexedNotNull(transform: (index: Int, T) -> R?): Sequence<R> = MapIndexedSequence(this, transform).filterNotNull()
public fun <T> Sequence<T>.withIndex(): Sequence<IndexedValue<T>> = MapIndexedSequence(this) { i, x -> IndexedValue(i, x) }
public fun <T> Sequence<T>.onEach(action: (T) -> Unit): Sequence<T> = MapSequence(this) { x -> action(x); x }
public fun <T> Sequence<T>.onEachIndexed(action: (index: Int, T) -> Unit): Sequence<T> = MapIndexedSequence(this) { i, x -> action(i, x); x }

private class TakeSequence<T>(private val s: Sequence<T>, private val n: Int) : Sequence<T> {
    override fun iterator(): Iterator<T> = TakeIterator(s.iterator(), n)
}
private class TakeIterator<T>(private val it0: Iterator<T>, private var left: Int) : Iterator<T> {
    override fun hasNext(): Boolean = left > 0 && it0.hasNext()
    override fun next(): T {
        if (left == 0) throw NoSuchElementException()
        left--
        return it0.next()
    }
}

private class DropSequence<T>(private val s: Sequence<T>, private val n: Int) : Sequence<T> {
    override fun iterator(): Iterator<T> = DropIterator(s.iterator(), n)
}
private class DropIterator<T>(private val it0: Iterator<T>, private var left: Int) : Iterator<T> {
    private fun drop() {
        while (left > 0 && it0.hasNext()) { it0.next(); left-- }
        left = 0
    }
    override fun hasNext(): Boolean { drop(); return it0.hasNext() }
    override fun next(): T { drop(); return it0.next() }
}

public fun <T> Sequence<T>.take(n: Int): Sequence<T> {
    require(n >= 0) { "Requested element count " + n + " is less than zero." }
    return TakeSequence(this, n)
}
public fun <T> Sequence<T>.drop(n: Int): Sequence<T> {
    require(n >= 0) { "Requested element count " + n + " is less than zero." }
    return DropSequence(this, n)
}

private class TakeWhileSequence<T>(private val s: Sequence<T>, private val pred: (T) -> Boolean, private val dropping: Boolean) : Sequence<T> {
    override fun iterator(): Iterator<T> = TakeWhileIterator(s.iterator(), pred, dropping)
}
private class TakeWhileIterator<T>(private val it0: Iterator<T>, private val pred: (T) -> Boolean, private val dropping: Boolean) : Iterator<T> {
    private var state = -1 // -1 unknown, 0 stop, 1 has next
    private var nextItem: T? = null
    private var dropped = false
    private fun calc() {
        if (dropping) {
            if (!dropped) {
                dropped = true
                while (it0.hasNext()) {
                    val x = it0.next()
                    if (!pred(x)) { nextItem = x; state = 1; return }
                }
                state = 0
                return
            }
            if (it0.hasNext()) { nextItem = it0.next(); state = 1 } else state = 0
            return
        }
        if (!it0.hasNext()) { state = 0; return }
        val x = it0.next()
        if (pred(x)) { nextItem = x; state = 1 } else state = 0
    }
    override fun hasNext(): Boolean { if (state == -1) calc(); return state == 1 }
    override fun next(): T {
        if (state == -1) calc()
        if (state == 0) throw NoSuchElementException()
        val r = nextItem as T
        nextItem = null
        state = if (dropping) -1 else -1
        return r
    }
}
public fun <T> Sequence<T>.takeWhile(predicate: (T) -> Boolean): Sequence<T> = TakeWhileSequence(this, predicate, false)
public fun <T> Sequence<T>.dropWhile(predicate: (T) -> Boolean): Sequence<T> = TakeWhileSequence(this, predicate, true)

private class FlatMapSequence<T, R>(private val s: Sequence<T>, private val f: (T) -> Iterator<R>) : Sequence<R> {
    override fun iterator(): Iterator<R> = FlatMapIterator(s.iterator(), f)
}
private class FlatMapIterator<T, R>(private val it0: Iterator<T>, private val f: (T) -> Iterator<R>) : Iterator<R> {
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
@kotlin.jvm.JvmName("flatMapSequence")
public fun <T, R> Sequence<T>.flatMap(transform: (T) -> Sequence<R>): Sequence<R> = FlatMapSequence(this) { x -> transform(x).iterator() }
public fun <T> Sequence<Sequence<T>>.flatten(): Sequence<T> = FlatMapSequence(this) { x -> x.iterator() }
@kotlin.jvm.JvmName("flattenIterable")
public fun <T> Sequence<Iterable<T>>.flatten(): Sequence<T> = FlatMapSequence(this) { x -> x.iterator() }

private class DistinctSequence<T, K>(private val s: Sequence<T>, private val key: (T) -> K) : Sequence<T> {
    override fun iterator(): Iterator<T> {
        val seen = HashSet<K>()
        return FilterIterator(s.iterator(), true) { _, x -> seen.add(key(x)) }
    }
}
public fun <T> Sequence<T>.distinct(): Sequence<T> = DistinctSequence(this) { x -> x }
public fun <T, K> Sequence<T>.distinctBy(selector: (T) -> K): Sequence<T> = DistinctSequence(this, selector)

public fun <T> Sequence<T>.plus(element: T): Sequence<T> = sequenceOf(this, sequenceOf(element)).flatten()
@kotlin.jvm.JvmName("plusSequence")
public fun <T> Sequence<T>.plus(elements: Sequence<T>): Sequence<T> = sequenceOf(this, elements).flatten()
@kotlin.jvm.JvmName("plusIterable")
public fun <T> Sequence<T>.plus(elements: Iterable<T>): Sequence<T> = sequenceOf(this, elements.asSequence()).flatten()
public fun <T> Sequence<T>.minus(element: T): Sequence<T> {
    val source = this
    return Sequence<T> {
        var removed = false
        source.filter { if (!removed && it == element) { removed = true; false } else true }.iterator()
    }
}

private class LazyListSequence<T>(private val make: () -> List<T>) : Sequence<T> {
    override fun iterator(): Iterator<T> = make().iterator()
}
public fun <T : Comparable<T>> Sequence<T>.sorted(): Sequence<T> = LazyListSequence { this.toList().sorted() }
public fun <T : Comparable<T>> Sequence<T>.sortedDescending(): Sequence<T> = LazyListSequence { this.toList().sortedDescending() }
public fun <T> Sequence<T>.sortedWith(comparator: Comparator<in T>): Sequence<T> = LazyListSequence { this.toList().sortedWith(comparator) }
public fun <T, R : Comparable<R>> Sequence<T>.sortedBy(selector: (T) -> R?): Sequence<T> = LazyListSequence { this.toList().sortedBy(selector) }
public fun <T, R : Comparable<R>> Sequence<T>.sortedByDescending(selector: (T) -> R?): Sequence<T> = LazyListSequence { this.toList().sortedByDescending(selector) }
public fun <T> Sequence<T>.shuffled(): Sequence<T> = LazyListSequence { this.toList().shuffled() }

private class ZipSequence<T, R, V>(private val a: Sequence<T>, private val b: Sequence<R>, private val f: (T, R) -> V) : Sequence<V> {
    override fun iterator(): Iterator<V> = ZipIterator(a.iterator(), b.iterator(), f)
}
private class ZipIterator<T, R, V>(private val a: Iterator<T>, private val b: Iterator<R>, private val f: (T, R) -> V) : Iterator<V> {
    override fun hasNext(): Boolean = a.hasNext() && b.hasNext()
    override fun next(): V = f(a.next(), b.next())
}
public infix fun <T, R> Sequence<T>.zip(other: Sequence<R>): Sequence<Pair<T, R>> = ZipSequence(this, other) { x, y -> Pair(x, y) }
public fun <T, R, V> Sequence<T>.zip(other: Sequence<R>, transform: (a: T, b: R) -> V): Sequence<V> = ZipSequence(this, other, transform)

// ---- terminal operations -----------------------------------------------------------------------

public fun <T> Sequence<T>.toList(): List<T> {
    val r = ArrayList<T>()
    for (x in this) r.add(x)
    return r
}
public fun <T> Sequence<T>.toMutableList(): MutableList<T> {
    val r = ArrayList<T>()
    for (x in this) r.add(x)
    return r
}
public fun <T> Sequence<T>.toSet(): Set<T> {
    val r = LinkedHashSet<T>()
    for (x in this) r.add(x)
    return r
}
public fun <T> Sequence<T>.toMutableSet(): MutableSet<T> {
    val r = LinkedHashSet<T>()
    for (x in this) r.add(x)
    return r
}
public fun <T> Sequence<T>.toHashSet(): HashSet<T> {
    val r = HashSet<T>()
    for (x in this) r.add(x)
    return r
}
public fun <K, V> Sequence<Pair<K, V>>.toMap(): Map<K, V> {
    val r = LinkedHashMap<K, V>()
    for (p in this) r[p.first] = p.second
    return r
}
public inline fun <T> Sequence<T>.forEach(action: (T) -> Unit) { for (x in this) action(x) }
public inline fun <T> Sequence<T>.forEachIndexed(action: (index: Int, T) -> Unit) {
    var i = 0
    for (x in this) action(i++, x)
}
public fun <T> Sequence<T>.count(): Int {
    var n = 0
    for (x in this) n++
    return n
}
public inline fun <T> Sequence<T>.count(predicate: (T) -> Boolean): Int {
    var n = 0
    for (x in this) if (predicate(x)) n++
    return n
}
public fun <T> Sequence<T>.first(): T {
    val it0 = iterator()
    if (!it0.hasNext()) throw NoSuchElementException("Sequence is empty.")
    return it0.next()
}
public inline fun <T> Sequence<T>.first(predicate: (T) -> Boolean): T {
    for (x in this) if (predicate(x)) return x
    throw NoSuchElementException("Sequence contains no element matching the predicate.")
}
public fun <T> Sequence<T>.firstOrNull(): T? {
    val it0 = iterator()
    return if (it0.hasNext()) it0.next() else null
}
public inline fun <T> Sequence<T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (x in this) if (predicate(x)) return x
    return null
}
public inline fun <T> Sequence<T>.find(predicate: (T) -> Boolean): T? = firstOrNull(predicate)
public fun <T> Sequence<T>.last(): T {
    val it0 = iterator()
    if (!it0.hasNext()) throw NoSuchElementException("Sequence is empty.")
    var last = it0.next()
    while (it0.hasNext()) last = it0.next()
    return last
}
public fun <T> Sequence<T>.lastOrNull(): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var last = it0.next()
    while (it0.hasNext()) last = it0.next()
    return last
}
public inline fun <T> Sequence<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var last: T? = null
    for (x in this) if (predicate(x)) last = x
    return last
}
public fun <T> Sequence<T>.single(): T {
    val it0 = iterator()
    if (!it0.hasNext()) throw NoSuchElementException("Sequence is empty.")
    val r = it0.next()
    if (it0.hasNext()) throw IllegalArgumentException("Sequence has more than one element.")
    return r
}
public fun <T> Sequence<T>.singleOrNull(): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    val r = it0.next()
    if (it0.hasNext()) return null
    return r
}
public fun <T> Sequence<T>.elementAt(index: Int): T = elementAtOrNull(index) ?: throw IndexOutOfBoundsException("Sequence doesn't contain element at index " + index + ".")
public fun <T> Sequence<T>.elementAtOrNull(index: Int): T? {
    if (index < 0) return null
    var i = 0
    for (x in this) if (i++ == index) return x
    return null
}
public fun <T> Sequence<T>.indexOf(element: T): Int {
    var i = 0
    for (x in this) { if (x == element) return i; i++ }
    return -1
}
public operator fun <T> Sequence<T>.contains(element: T): Boolean = indexOf(element) >= 0
public inline fun <T> Sequence<T>.any(predicate: (T) -> Boolean): Boolean {
    for (x in this) if (predicate(x)) return true
    return false
}
public fun <T> Sequence<T>.any(): Boolean = iterator().hasNext()
public inline fun <T> Sequence<T>.all(predicate: (T) -> Boolean): Boolean {
    for (x in this) if (!predicate(x)) return false
    return true
}
public inline fun <T> Sequence<T>.none(predicate: (T) -> Boolean): Boolean {
    for (x in this) if (predicate(x)) return false
    return true
}
public fun <T> Sequence<T>.none(): Boolean = !iterator().hasNext()
public inline fun <T, R> Sequence<T>.fold(initial: R, operation: (acc: R, T) -> R): R {
    var acc = initial
    for (x in this) acc = operation(acc, x)
    return acc
}
public inline fun <S, T : S> Sequence<T>.reduce(operation: (acc: S, T) -> S): S {
    val it0 = iterator()
    if (!it0.hasNext()) throw UnsupportedOperationException("Empty sequence can't be reduced.")
    var acc: S = it0.next()
    while (it0.hasNext()) acc = operation(acc, it0.next())
    return acc
}
@kotlin.jvm.JvmName("sumOfIntSequence")
public fun Sequence<Int>.sum(): Int {
    var s = 0
    for (x in this) s += x
    return s
}
@kotlin.jvm.JvmName("sumOfLongSequence")
public fun Sequence<Long>.sum(): Long {
    var s = 0L
    for (x in this) s += x
    return s
}
@kotlin.jvm.JvmName("sumOfDoubleSequence")
public fun Sequence<Double>.sum(): Double {
    var s = 0.0
    for (x in this) s += x
    return s
}
@kotlin.jvm.JvmName("sumOfIntSeqSelector")
public inline fun <T> Sequence<T>.sumOf(selector: (T) -> Int): Int {
    var s = 0
    for (x in this) s += selector(x)
    return s
}
@kotlin.jvm.JvmName("sumOfLongSeqSelector")
public inline fun <T> Sequence<T>.sumOf(selector: (T) -> Long): Long {
    var s = 0L
    for (x in this) s += selector(x)
    return s
}
@kotlin.jvm.JvmName("sumOfDoubleSeqSelector")
public inline fun <T> Sequence<T>.sumOf(selector: (T) -> Double): Double {
    var s = 0.0
    for (x in this) s += selector(x)
    return s
}
@kotlin.jvm.JvmName("averageOfIntSequence")
public fun Sequence<Int>.average(): Double {
    var s = 0.0
    var n = 0
    for (x in this) { s += x; n++ }
    return if (n == 0) Double.NaN else s / n
}
@kotlin.jvm.JvmName("averageOfDoubleSequence")
public fun Sequence<Double>.average(): Double {
    var s = 0.0
    var n = 0
    for (x in this) { s += x; n++ }
    return if (n == 0) Double.NaN else s / n
}
public fun <T : Comparable<T>> Sequence<T>.maxOrNull(): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var m = it0.next()
    while (it0.hasNext()) { val x = it0.next(); if (x > m) m = x }
    return m
}
public fun <T : Comparable<T>> Sequence<T>.minOrNull(): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var m = it0.next()
    while (it0.hasNext()) { val x = it0.next(); if (x < m) m = x }
    return m
}
public fun <T : Comparable<T>> Sequence<T>.max(): T = maxOrNull() ?: throw NoSuchElementException()
public fun <T : Comparable<T>> Sequence<T>.min(): T = minOrNull() ?: throw NoSuchElementException()
public inline fun <T, R : Comparable<R>> Sequence<T>.maxByOrNull(selector: (T) -> R): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var best = it0.next()
    var bv = selector(best)
    while (it0.hasNext()) { val x = it0.next(); val v = selector(x); if (v > bv) { best = x; bv = v } }
    return best
}
public inline fun <T, R : Comparable<R>> Sequence<T>.minByOrNull(selector: (T) -> R): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var best = it0.next()
    var bv = selector(best)
    while (it0.hasNext()) { val x = it0.next(); val v = selector(x); if (v < bv) { best = x; bv = v } }
    return best
}
public inline fun <T, R : Comparable<R>> Sequence<T>.maxBy(selector: (T) -> R): T = maxByOrNull(selector) ?: throw NoSuchElementException()
public inline fun <T, R : Comparable<R>> Sequence<T>.minBy(selector: (T) -> R): T = minByOrNull(selector) ?: throw NoSuchElementException()
public fun <T, R : Comparable<R>> Sequence<T>.maxOf(selector: (T) -> R): R = map(selector).max()
public fun <T, R : Comparable<R>> Sequence<T>.minOf(selector: (T) -> R): R = map(selector).min()
public fun <T> Sequence<T>.maxWithOrNull(comparator: Comparator<in T>): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var m = it0.next()
    while (it0.hasNext()) { val x = it0.next(); if (comparator.compare(x, m) > 0) m = x }
    return m
}
public fun <T> Sequence<T>.minWithOrNull(comparator: Comparator<in T>): T? {
    val it0 = iterator()
    if (!it0.hasNext()) return null
    var m = it0.next()
    while (it0.hasNext()) { val x = it0.next(); if (comparator.compare(x, m) < 0) m = x }
    return m
}
public fun <T> Sequence<T>.joinTo(
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
            if (transform != null) buffer.append(transform(element)) else buffer.append(element.toString())
        } else {
            break
        }
    }
    if (limit >= 0 && count > limit) buffer.append(truncated)
    buffer.append(postfix)
    return buffer
}
public fun <T> Sequence<T>.joinToString(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    limit: Int = -1,
    truncated: CharSequence = "...",
    transform: ((T) -> CharSequence)? = null,
): String = joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString()
public inline fun <T, K, V> Sequence<T>.associate(transform: (T) -> Pair<K, V>): Map<K, V> {
    val m = LinkedHashMap<K, V>()
    for (x in this) { val p = transform(x); m[p.first] = p.second }
    return m
}
public inline fun <T, K> Sequence<T>.associateBy(keySelector: (T) -> K): Map<K, T> {
    val m = LinkedHashMap<K, T>()
    for (x in this) m[keySelector(x)] = x
    return m
}
public inline fun <T, V> Sequence<T>.associateWith(valueSelector: (T) -> V): Map<T, V> {
    val m = LinkedHashMap<T, V>()
    for (x in this) m[x] = valueSelector(x)
    return m
}
public inline fun <T, K> Sequence<T>.groupBy(keySelector: (T) -> K): Map<K, List<T>> {
    val m = LinkedHashMap<K, MutableList<T>>()
    for (x in this) {
        val k = keySelector(x)
        var l = m[k]
        if (l == null) { l = ArrayList<T>(); m[k] = l }
        l.add(x)
    }
    @Suppress("UNCHECKED_CAST")
    return m as Map<K, List<T>>
}
public inline fun <T> Sequence<T>.partition(predicate: (T) -> Boolean): Pair<List<T>, List<T>> {
    val a = ArrayList<T>()
    val b = ArrayList<T>()
    for (x in this) if (predicate(x)) a.add(x) else b.add(x)
    return Pair(a, b)
}
public fun <T> Sequence<T>.ifEmpty(defaultValue: () -> Sequence<T>): Sequence<T> =
    LazyListSequence { val l = this.toList(); if (l.isEmpty()) defaultValue().toList() else l }

public fun <K, V> Map<K, V>.asSequence(): Sequence<Map.Entry<K, V>> = entries.asSequence()

// ---- builders ---------------------------------------------------------------------------------------------------

public abstract class SequenceScope<in T> internal constructor() {
    public abstract suspend fun yield(value: T)
    public abstract suspend fun yieldAll(iterator: Iterator<T>)
    public suspend fun yieldAll(elements: Iterable<T>) {
        yieldAll(elements.iterator())
    }
    public suspend fun yieldAll(sequence: Sequence<T>) {
        yieldAll(sequence.iterator())
    }
}

public fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence { iterator(block) }

public fun <T> Sequence(iterator: () -> Iterator<T>): Sequence<T> = object : Sequence<T> {
    override fun iterator(): Iterator<T> = iterator()
}

public fun <T> iterator(block: suspend SequenceScope<T>.() -> Unit): Iterator<T> {
    val sink = SequenceSink()
    val it = SequenceBuilderIterator<T>(sink)
    it.nextStep = block.createCoroutineUnintercepted(BuilderScope<T>(), sink)
    return it
}

/**
 * What a `yield` hands up to the iterator: the value (or an iterator of values) and the continuation to resume. It is
 * returned by the suspend function `yield` and travels up the chain of state machines like COROUTINE_SUSPENDED; the
 * coroutine never refers to the iterator, so an abandoned sequence frees its state (reference counting has no cycle collector).
 */
internal class YieldRequest(val value: Any?, val values: Iterator<Any?>?, val next: Continuation<Unit>) : SuspendMarker()

/** The completion of the builder coroutine: remembers a failure for the iterator. */
private class SequenceSink : Continuation<Unit> {
    var failure: Throwable? = null
    override val context: CoroutineContext get() = EmptyCoroutineContext
    override fun resumeWith(result: Result<Unit>) {
        failure = result.exceptionOrNull()
    }
}

private class BuilderScope<T> : SequenceScope<T>() {
    override suspend fun yield(value: T) {
        suspendCoroutineUninterceptedOrReturn<Unit> { c -> YieldRequest(value, null, c) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun yieldAll(iterator: Iterator<T>) {
        if (!iterator.hasNext()) return
        suspendCoroutineUninterceptedOrReturn<Unit> { c -> YieldRequest(null, iterator as Iterator<Any?>, c) }
    }
}

private class SequenceBuilderIterator<T>(private val sink: SequenceSink) : Iterator<T> {
    // 0: not ready, 1: has a value in nextValue, 2: has an iterator in nextIterator, 3: done
    private var state = 0
    private var nextValue: Any? = null
    private var nextIterator: Iterator<T>? = null
    var nextStep: Continuation<Unit>? = null

    @Suppress("UNCHECKED_CAST")
    override fun hasNext(): Boolean {
        while (true) {
            when (state) {
                0 -> {}
                1 -> return true
                2 -> {
                    val itr = nextIterator!!
                    if (itr.hasNext()) return true
                    nextIterator = null
                    state = 0
                }
                else -> return false
            }
            // run the builder to its next yield
            val step = nextStep!!
            nextStep = null
            val out = (step as BaseContinuation).resumeRaw(Result.success(Unit))
            if (out is YieldRequest) {
                nextStep = out.next
                val vs = out.values
                if (vs != null) {
                    nextIterator = vs as Iterator<T>
                    state = 2
                } else {
                    nextValue = out.value
                    state = 1
                }
            } else {
                state = 3
                val f = sink.failure
                if (f != null) throw f
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun next(): T {
        if (state == 0 || state == 2) {
            if (!hasNext()) throw NoSuchElementException()
        }
        if (state == 1) {
            state = 0
            val v = nextValue as T
            nextValue = null
            return v
        }
        if (state == 2) return nextIterator!!.next()
        throw NoSuchElementException()
    }
}
