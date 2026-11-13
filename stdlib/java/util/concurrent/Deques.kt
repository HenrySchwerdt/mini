/*
 * java.util.concurrent: BlockingDeque / LinkedBlockingDeque, ConcurrentLinkedDeque, PriorityBlockingQueue, SynchronousQueue, DelayQueue,
 * LinkedTransferQueue, CopyOnWriteArraySet, ConcurrentSkipListMap / ConcurrentSkipListSet. Like the other collections of this package they take
 * one mutex per object and iterate over a snapshot (docs/threads.md).
 */
package java.util.concurrent

import java.util.function.BiFunction
import java.util.function.Function
import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

public interface BlockingDeque<E> : BlockingQueue<E> {
    public fun addFirst(e: E)
    public fun addLast(e: E)
    public fun offerFirst(e: E): Boolean
    public fun offerLast(e: E): Boolean
    public fun putFirst(e: E)
    public fun putLast(e: E)
    public fun takeFirst(): E
    public fun takeLast(): E
    public fun pollFirst(): E?
    public fun pollLast(): E?
    public fun peekFirst(): E?
    public fun peekLast(): E?
    public fun push(e: E)
    public fun pop(): E
}

/** A deque of at most [capacity] elements behind one mutex; the blocking operations wait on two conditions. */
public open class LinkedBlockingDeque<E>(private val capacity: Int) : AbstractMutableCollection<E>(), BlockingDeque<E> {
    public constructor() : this(Int.MAX_VALUE)
    public constructor(c: Collection<E>) : this(Int.MAX_VALUE) {
        for (e in c) add(e)
    }

    private val lock = Lock()
    private val notEmpty: Condition = lock.newCondition()
    private val notFull: Condition = lock.newCondition()
    private val items = ArrayDeque<E>()

    override val size: Int
        get() {
            lock.lock()
            val r = items.size
            lock.unlock()
            return r
        }

    // the caller holds the lock
    private fun insert(e: E, first: Boolean): Boolean {
        if (items.size >= capacity) return false
        if (first) items.addFirst(e) else items.addLast(e)
        notEmpty.signal()
        return true
    }

    private fun extract(first: Boolean): E? {
        if (items.isEmpty()) return null
        val r = if (first) items.removeFirst() else items.removeLast()
        notFull.signal()
        return r
    }

    private fun offerTimed(e: E, first: Boolean, timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (items.size >= capacity) {
            if (awaitDeadline(notFull, end)) break
        }
        val ok = insert(e, first)
        lock.unlock()
        return ok
    }

    private fun putAt(e: E, first: Boolean) {
        lock.lock()
        while (items.size >= capacity) notFull.await()
        insert(e, first)
        lock.unlock()
    }

    private fun pollAt(first: Boolean): E? {
        lock.lock()
        val r = extract(first)
        lock.unlock()
        return r
    }

    private fun pollTimed(first: Boolean, timeout: Long, unit: TimeUnit): E? {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (items.isEmpty()) {
            if (awaitDeadline(notEmpty, end)) break
        }
        val r = extract(first)
        lock.unlock()
        return r
    }

    private fun takeAt(first: Boolean): E {
        lock.lock()
        while (items.isEmpty()) notEmpty.await()
        val r = extract(first)!!
        lock.unlock()
        return r
    }

    private fun peekAt(first: Boolean): E? {
        lock.lock()
        val r = if (items.isEmpty()) null else if (first) items.first() else items.last()
        lock.unlock()
        return r
    }

    public override fun addFirst(e: E) {
        if (!offerFirst(e)) throw IllegalStateException("Deque full")
    }

    public override fun addLast(e: E) {
        if (!offerLast(e)) throw IllegalStateException("Deque full")
    }

    override fun add(element: E): Boolean {
        addLast(element)
        return true
    }

    public override fun offerFirst(e: E): Boolean {
        lock.lock()
        val ok = insert(e, true)
        lock.unlock()
        return ok
    }

    public override fun offerLast(e: E): Boolean {
        lock.lock()
        val ok = insert(e, false)
        lock.unlock()
        return ok
    }

    public fun offerFirst(e: E, timeout: Long, unit: TimeUnit): Boolean = offerTimed(e, true, timeout, unit)
    public fun offerLast(e: E, timeout: Long, unit: TimeUnit): Boolean = offerTimed(e, false, timeout, unit)
    public override fun offer(e: E): Boolean = offerLast(e)
    public override fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean = offerTimed(e, false, timeout, unit)
    public override fun putFirst(e: E) { putAt(e, true) }
    public override fun putLast(e: E) { putAt(e, false) }
    public override fun put(e: E) { putAt(e, false) }
    public override fun push(e: E) { addFirst(e) }
    public override fun pollFirst(): E? = pollAt(true)
    public override fun pollLast(): E? = pollAt(false)
    public override fun poll(): E? = pollAt(true)
    public fun pollFirst(timeout: Long, unit: TimeUnit): E? = pollTimed(true, timeout, unit)
    public fun pollLast(timeout: Long, unit: TimeUnit): E? = pollTimed(false, timeout, unit)
    public override fun poll(timeout: Long, unit: TimeUnit): E? = pollTimed(true, timeout, unit)
    public override fun takeFirst(): E = takeAt(true)
    public override fun takeLast(): E = takeAt(false)
    public override fun take(): E = takeAt(true)
    public override fun peekFirst(): E? = peekAt(true)
    public override fun peekLast(): E? = peekAt(false)
    public override fun peek(): E? = peekAt(true)
    public fun getFirst(): E = peekFirst() ?: throw NoSuchElementException()
    public fun getLast(): E = peekLast() ?: throw NoSuchElementException()
    public fun element(): E = getFirst()
    public fun removeFirst(): E = pollFirst() ?: throw NoSuchElementException()
    public fun removeLast(): E = pollLast() ?: throw NoSuchElementException()
    public fun remove(): E = removeFirst()
    public override fun pop(): E = removeFirst()

    public override fun remainingCapacity(): Int {
        lock.lock()
        val r = capacity - items.size
        lock.unlock()
        return r
    }

    public override fun drainTo(c: MutableCollection<in E>): Int = drainTo(c, Int.MAX_VALUE)

    public override fun drainTo(c: MutableCollection<in E>, maxElements: Int): Int {
        lock.lock()
        var n = 0
        while (n < maxElements && !items.isEmpty()) {
            c.add(items.removeFirst())
            n++
        }
        if (n > 0) notFull.signalAll()
        lock.unlock()
        return n
    }

    override fun remove(element: E): Boolean {
        lock.lock()
        val r = items.remove(element)
        if (r) notFull.signal()
        lock.unlock()
        return r
    }

    public fun removeFirstOccurrence(o: E): Boolean = remove(o)

    public fun removeLastOccurrence(o: E): Boolean {
        lock.lock()
        val i = items.lastIndexOf(o)
        if (i >= 0) {
            items.removeAt(i)
            notFull.signal()
        }
        lock.unlock()
        return i >= 0
    }

    override fun clear() {
        lock.lock()
        items.clear()
        notFull.signalAll()
        lock.unlock()
    }

    override fun contains(element: E): Boolean {
        lock.lock()
        val r = items.contains(element)
        lock.unlock()
        return r
    }

    override fun iterator(): MutableIterator<E> {
        lock.lock()
        val snapshot = ArrayList<E>(items)
        lock.unlock()
        return snapshot.iterator()
    }

    public fun descendingIterator(): Iterator<E> {
        lock.lock()
        val snapshot = ArrayList<E>(items)
        lock.unlock()
        snapshot.reverse()
        return snapshot.iterator()
    }
}

public class ConcurrentLinkedDeque<E> : LinkedBlockingDeque<E> {
    public constructor() : super()
    public constructor(c: Collection<E>) : super(c)
}

public class LinkedTransferQueue<E> : LinkedBlockingQueue<E> {
    public constructor() : super()
    public constructor(c: Collection<E>) : super(c)

    public fun tryTransfer(e: E): Boolean = false
}

/** An unbounded queue ordered by a comparator (or the natural order); `take` waits for an element. */
public class PriorityBlockingQueue<E> : AbstractMutableCollection<E>, BlockingQueue<E> {
    private val lock = Lock()
    private val notEmpty: Condition = lock.newCondition()
    private val q: java.util.PriorityQueue<E>

    public constructor() {
        q = java.util.PriorityQueue<E>()
    }

    public constructor(initialCapacity: Int) {
        q = java.util.PriorityQueue<E>(initialCapacity)
    }

    public constructor(initialCapacity: Int, comparator: Comparator<in E>?) {
        q = java.util.PriorityQueue<E>(initialCapacity, comparator)
    }

    public constructor(c: Collection<E>) {
        q = java.util.PriorityQueue<E>(c)
    }

    public fun comparator(): Comparator<in E>? = q.comparator()

    override val size: Int
        get() {
            lock.lock()
            val r = q.size
            lock.unlock()
            return r
        }

    override fun add(element: E): Boolean = offer(element)

    public override fun offer(e: E): Boolean {
        lock.lock()
        q.add(e)
        notEmpty.signal()
        lock.unlock()
        return true
    }

    public override fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean = offer(e)
    public override fun put(e: E) { offer(e) }

    public override fun poll(): E? {
        lock.lock()
        val r = q.poll()
        lock.unlock()
        return r
    }

    public override fun poll(timeout: Long, unit: TimeUnit): E? {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (q.isEmpty()) {
            if (awaitDeadline(notEmpty, end)) break
        }
        val r = q.poll()
        lock.unlock()
        return r
    }

    public override fun take(): E {
        lock.lock()
        while (q.isEmpty()) notEmpty.await()
        val r = q.poll()!!
        lock.unlock()
        return r
    }

    public override fun peek(): E? {
        lock.lock()
        val r = q.peek()
        lock.unlock()
        return r
    }

    public fun element(): E = peek() ?: throw NoSuchElementException()
    public fun remove(): E = poll() ?: throw NoSuchElementException()
    public override fun remainingCapacity(): Int = Int.MAX_VALUE
    public override fun drainTo(c: MutableCollection<in E>): Int = drainTo(c, Int.MAX_VALUE)

    public override fun drainTo(c: MutableCollection<in E>, maxElements: Int): Int {
        lock.lock()
        var n = 0
        while (n < maxElements && !q.isEmpty()) {
            c.add(q.poll()!!)
            n++
        }
        lock.unlock()
        return n
    }

    override fun remove(element: E): Boolean {
        lock.lock()
        val r = q.remove(element)
        lock.unlock()
        return r
    }

    override fun clear() {
        lock.lock()
        q.clear()
        lock.unlock()
    }

    override fun contains(element: E): Boolean {
        lock.lock()
        val r = q.contains(element)
        lock.unlock()
        return r
    }

    override fun iterator(): MutableIterator<E> {
        lock.lock()
        val snapshot = ArrayList<E>(q)
        lock.unlock()
        return snapshot.iterator()
    }
}

/** A zero-capacity queue: every `put` waits for a `take` and the other way round. */
public class SynchronousQueue<E> : AbstractMutableCollection<E>, BlockingQueue<E> {
    public constructor()
    public constructor(fair: Boolean)

    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var item: E? = null
    private var has = false
    private var takers = 0
    private var gen = 0L

    override val size: Int get() = 0
    override fun iterator(): MutableIterator<E> = ArrayList<E>().iterator()
    override fun add(element: E): Boolean {
        if (!offer(element)) throw IllegalStateException("Queue full")
        return true
    }

    public override fun remainingCapacity(): Int = 0

    // puts the element into the slot (the caller holds the lock and the slot is free), then waits until a taker took it
    private fun handOff(e: E, end: Long): Boolean {
        item = e
        has = true
        changed.signalAll()
        val g = gen
        try {
            while (gen == g) {
                if (end >= 0L) {
                    if (awaitDeadline(changed, end)) break
                } else {
                    changed.await()
                }
            }
        } catch (ex: InterruptedException) {
            lock.lock()
            if (gen == g && has) {
                has = false
                item = null
                changed.signalAll()
            }
            lock.unlock()
            throw ex
        }
        if (gen == g) {
            has = false
            item = null
            changed.signalAll()
            return false
        }
        return true
    }

    public override fun put(e: E) {
        lock.lock()
        try {
            while (has) changed.await()
        } catch (ex: InterruptedException) {
            throw ex
        }
        handOff(e, -1L)
        lock.unlock()
    }

    public override fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (has) {
            if (awaitDeadline(changed, end)) {
                lock.unlock()
                return false
            }
        }
        val ok = handOff(e, end)
        lock.unlock()
        return ok
    }

    // succeeds only when a taker is waiting
    public override fun offer(e: E): Boolean {
        lock.lock()
        if (has || takers == 0) {
            lock.unlock()
            return false
        }
        item = e
        has = true
        changed.signalAll()
        lock.unlock()
        return true
    }

    // the caller holds the lock and the slot is full
    private fun grab(): E {
        @Suppress("UNCHECKED_CAST")
        val r = item as E
        item = null
        has = false
        gen++
        changed.signalAll()
        return r
    }

    public override fun take(): E {
        lock.lock()
        takers++
        try {
            while (!has) changed.await()
        } catch (ex: InterruptedException) {
            lock.lock()
            takers--
            lock.unlock()
            throw ex
        }
        takers--
        val r = grab()
        lock.unlock()
        return r
    }

    public override fun poll(timeout: Long, unit: TimeUnit): E? {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        takers++
        try {
            while (!has) {
                if (awaitDeadline(changed, end)) break
            }
        } catch (ex: InterruptedException) {
            lock.lock()
            takers--
            lock.unlock()
            throw ex
        }
        takers--
        val r = if (has) grab() else null
        lock.unlock()
        return r
    }

    public override fun poll(): E? {
        lock.lock()
        val r = if (has) grab() else null
        lock.unlock()
        return r
    }

    public override fun peek(): E? = null

    public override fun drainTo(c: MutableCollection<in E>): Int = drainTo(c, Int.MAX_VALUE)

    public override fun drainTo(c: MutableCollection<in E>, maxElements: Int): Int {
        var n = 0
        while (n < maxElements) {
            val e = poll() ?: break
            c.add(e)
            n++
        }
        return n
    }
}

public interface Delayed : Comparable<Delayed> {
    public fun getDelay(unit: TimeUnit): Long
}

/** An unbounded queue of delayed elements: an element can be taken once its delay has passed. */
public class DelayQueue<E : Delayed> : AbstractMutableCollection<E>, BlockingQueue<E> {
    private val lock = Lock()
    private val available: Condition = lock.newCondition()
    private val q = java.util.PriorityQueue<E>()

    public constructor()

    public constructor(c: Collection<E>) {
        for (e in c) add(e)
    }

    override val size: Int
        get() {
            lock.lock()
            val r = q.size
            lock.unlock()
            return r
        }

    override fun add(element: E): Boolean = offer(element)

    public override fun offer(e: E): Boolean {
        lock.lock()
        q.add(e)
        available.signalAll()
        lock.unlock()
        return true
    }

    public override fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean = offer(e)
    public override fun put(e: E) { offer(e) }

    public override fun poll(): E? {
        lock.lock()
        val first = q.peek()
        val r = if (first == null || first.getDelay(TimeUnit.NANOSECONDS) > 0L) null else q.poll()
        lock.unlock()
        return r
    }

    public override fun take(): E {
        lock.lock()
        while (true) {
            val first = q.peek()
            if (first == null) {
                available.await()
            } else {
                val d = first.getDelay(TimeUnit.NANOSECONDS)
                if (d <= 0L) {
                    val r = q.poll()!!
                    lock.unlock()
                    return r
                }
                available.awaitNanos(d)
            }
        }
    }

    public override fun poll(timeout: Long, unit: TimeUnit): E? {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (true) {
            val first = q.peek()
            val now = sysMonotonicNanos()
            if (first != null) {
                val d = first.getDelay(TimeUnit.NANOSECONDS)
                if (d <= 0L) {
                    val r = q.poll()
                    lock.unlock()
                    return r
                }
                if (now >= end) break
                available.awaitNanos(if (d < end - now) d else end - now)
            } else {
                if (awaitDeadline(available, end)) break
            }
        }
        lock.unlock()
        return null
    }

    // the head, ready or not (as in the JDK)
    public override fun peek(): E? {
        lock.lock()
        val r = q.peek()
        lock.unlock()
        return r
    }

    public override fun remainingCapacity(): Int = Int.MAX_VALUE
    public override fun drainTo(c: MutableCollection<in E>): Int = drainTo(c, Int.MAX_VALUE)

    public override fun drainTo(c: MutableCollection<in E>, maxElements: Int): Int {
        var n = 0
        while (n < maxElements) {
            val e = poll() ?: break
            c.add(e)
            n++
        }
        return n
    }

    override fun remove(element: E): Boolean {
        lock.lock()
        val r = q.remove(element)
        lock.unlock()
        return r
    }

    override fun clear() {
        lock.lock()
        q.clear()
        lock.unlock()
    }

    override fun iterator(): MutableIterator<E> {
        lock.lock()
        val snapshot = ArrayList<E>(q)
        lock.unlock()
        return snapshot.iterator()
    }
}

public class CopyOnWriteArraySet<E> : AbstractMutableSet<E> {
    private val list = CopyOnWriteArrayList<E>()

    public constructor()

    public constructor(c: Collection<E>) {
        for (e in c) add(e)
    }

    override val size: Int get() = list.size
    override fun add(element: E): Boolean = list.addIfAbsent(element)
    override fun contains(element: E): Boolean = list.contains(element)
    override fun remove(element: E): Boolean = list.remove(element)
    override fun clear() { list.clear() }
    override fun iterator(): MutableIterator<E> = list.iterator()
}

/** A sorted concurrent map: a TreeMap behind one mutex. Navigation results and the sub-map views are snapshots. */
public class ConcurrentSkipListMap<K, V> : ConcurrentMap<K, V> {
    private val lock = Lock()
    private val map: java.util.TreeMap<K, V>

    public constructor() {
        map = java.util.TreeMap<K, V>()
    }

    public constructor(comparator: Comparator<in K>?) {
        map = java.util.TreeMap<K, V>(comparator)
    }

    public constructor(m: Map<out K, V>) {
        map = java.util.TreeMap<K, V>()
        for ((k, v) in m) map.put(k, v)
    }

    public fun comparator(): Comparator<in K>? = map.comparator()

    private inline fun <R> locked(f: () -> R): R {
        lock.lock()
        try {
            return f()
        } finally {
            lock.unlock()
        }
    }

    override val size: Int get() = locked { map.size }
    override fun isEmpty(): Boolean = locked { map.isEmpty() }
    override fun containsKey(key: K): Boolean = locked { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = locked { map.containsValue(value) }
    override fun get(key: K): V? = locked { map.get(key) }
    override fun put(key: K, value: V): V? = locked { map.put(key, value) }
    override fun remove(key: K): V? = locked { map.remove(key) }
    override fun clear() { locked { map.clear() } }
    override fun putAll(from: Map<out K, V>) { locked { for ((k, v) in from) map.put(k, v) } }

    override val keys: MutableSet<K> get() = locked { java.util.TreeMap<K, V>(map).keys }
    override val values: MutableCollection<V> get() = locked { ArrayList<V>(map.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = locked { java.util.TreeMap<K, V>(map).entries }

    public override fun putIfAbsent(key: K, value: V): V? = locked {
        val cur = map.get(key)
        if (cur == null) map.put(key, value)
        cur
    }

    public override fun replace(key: K, value: V): V? = locked { if (map.containsKey(key)) map.put(key, value) else null }

    public override fun replace(key: K, oldValue: V, newValue: V): Boolean = locked {
        val ok = map.containsKey(key) && map.get(key) == oldValue
        if (ok) map.put(key, newValue)
        ok
    }

    public fun remove(key: K, value: V): Boolean = locked {
        val ok = map.containsKey(key) && map.get(key) == value
        if (ok) map.remove(key)
        ok
    }

    public override fun computeIfAbsent(key: K, mappingFunction: Function<in K, out V>): V = locked {
        val cur = map.get(key)
        if (cur != null) cur else {
            val v = mappingFunction.apply(key)
            map.put(key, v)
            v
        }
    }

    public override fun computeIfPresent(key: K, remappingFunction: BiFunction<in K, in V, out V?>): V? = locked {
        val cur = map.get(key)
        if (cur == null) null else {
            val v = remappingFunction.apply(key, cur)
            if (v == null) map.remove(key) else map.put(key, v)
            v
        }
    }

    public override fun compute(key: K, remappingFunction: BiFunction<in K, in V?, out V?>): V? = locked {
        val v = remappingFunction.apply(key, map.get(key))
        if (v == null) map.remove(key) else map.put(key, v)
        v
    }

    public override fun merge(key: K, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? = locked {
        val cur = map.get(key)
        val v = if (cur == null) value else remappingFunction.apply(cur, value)
        if (v == null) map.remove(key) else map.put(key, v)
        v
    }

    public fun firstKey(): K = locked { map.firstKey() }
    public fun lastKey(): K = locked { map.lastKey() }
    public fun firstEntry(): Map.Entry<K, V>? = locked { map.firstEntry() }
    public fun lastEntry(): Map.Entry<K, V>? = locked { map.lastEntry() }
    public fun pollFirstEntry(): Map.Entry<K, V>? = locked { map.pollFirstEntry() }
    public fun pollLastEntry(): Map.Entry<K, V>? = locked { map.pollLastEntry() }
    public fun floorKey(key: K): K? = locked { map.floorKey(key) }
    public fun ceilingKey(key: K): K? = locked { map.ceilingKey(key) }
    public fun lowerKey(key: K): K? = locked { map.lowerKey(key) }
    public fun higherKey(key: K): K? = locked { map.higherKey(key) }
    public fun floorEntry(key: K): Map.Entry<K, V>? = locked { map.floorEntry(key) }
    public fun ceilingEntry(key: K): Map.Entry<K, V>? = locked { map.ceilingEntry(key) }
    public fun lowerEntry(key: K): Map.Entry<K, V>? = locked { map.lowerEntry(key) }
    public fun higherEntry(key: K): Map.Entry<K, V>? = locked { map.higherEntry(key) }
    public fun headMap(toKey: K, inclusive: Boolean = false): java.util.TreeMap<K, V> = locked { map.headMap(toKey, inclusive) }
    public fun tailMap(fromKey: K, inclusive: Boolean = true): java.util.TreeMap<K, V> = locked { map.tailMap(fromKey, inclusive) }
    public fun subMap(fromKey: K, toKey: K): java.util.TreeMap<K, V> = locked { map.subMap(fromKey, toKey) }
    public fun descendingMap(): Map<K, V> = locked { map.descendingMap() }
    public fun descendingKeySet(): List<K> = locked { map.descendingKeySet() }

    public override fun toString(): String = locked { map.toString() }
    public override fun equals(other: Any?): Boolean = locked { map == other }
    public override fun hashCode(): Int = locked { map.hashCode() }
}

public class ConcurrentSkipListSet<E> : AbstractMutableSet<E> {
    private val lock = Lock()
    private val set: java.util.TreeSet<E>

    public constructor() {
        set = java.util.TreeSet<E>()
    }

    public constructor(comparator: Comparator<in E>?) {
        set = java.util.TreeSet<E>(comparator)
    }

    public constructor(c: Collection<E>) {
        set = java.util.TreeSet<E>()
        for (e in c) set.add(e)
    }

    private inline fun <R> locked(f: () -> R): R {
        lock.lock()
        try {
            return f()
        } finally {
            lock.unlock()
        }
    }

    override val size: Int get() = locked { set.size }
    override fun add(element: E): Boolean = locked { set.add(element) }
    override fun contains(element: E): Boolean = locked { set.contains(element) }
    override fun remove(element: E): Boolean = locked { set.remove(element) }
    override fun clear() { locked { set.clear() } }
    override fun iterator(): MutableIterator<E> = locked { ArrayList<E>(set).iterator() }

    public fun first(): E = locked { set.first() }
    public fun last(): E = locked { set.last() }
    public fun pollFirst(): E? = locked { set.pollFirst() }
    public fun pollLast(): E? = locked { set.pollLast() }
    public fun floor(e: E): E? = locked { set.floor(e) }
    public fun ceiling(e: E): E? = locked { set.ceiling(e) }
    public fun lower(e: E): E? = locked { set.lower(e) }
    public fun higher(e: E): E? = locked { set.higher(e) }
    public fun headSet(toElement: E, inclusive: Boolean = false): java.util.TreeSet<E> = locked { set.headSet(toElement, inclusive) }
    public fun tailSet(fromElement: E, inclusive: Boolean = true): java.util.TreeSet<E> = locked { set.tailSet(fromElement, inclusive) }
    public fun subSet(fromElement: E, toElement: E): java.util.TreeSet<E> = locked { set.subSet(fromElement, toElement) }
    public fun descendingSet(): List<E> = locked { set.descendingSet() }
    public fun descendingIterator(): Iterator<E> = locked { set.descendingIterator() }
}
