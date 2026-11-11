/*
 * java.util.concurrent collections: ConcurrentHashMap, ConcurrentLinkedQueue, the blocking queues and CopyOnWriteArrayList.
 * Every operation takes one mutex (no lock striping). Iteration works on a snapshot; the views `keys`, `values` and `entries` are
 * snapshots too, a change through them does not reach the map. Callbacks given to computeIfAbsent / compute / merge run with the mutex
 * held and must not use the same map.
 */
package java.util.concurrent

import java.util.function.BiFunction
import java.util.function.Function
import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

public interface ConcurrentMap<K, V> : MutableMap<K, V> {
    public fun putIfAbsent(key: K, value: V): V?
    public fun replace(key: K, value: V): V?
    public fun replace(key: K, oldValue: V, newValue: V): Boolean
    public fun computeIfAbsent(key: K, mappingFunction: Function<in K, out V>): V
    public fun computeIfPresent(key: K, remappingFunction: BiFunction<in K, in V, out V?>): V?
    public fun compute(key: K, remappingFunction: BiFunction<in K, in V?, out V?>): V?
    public fun merge(key: K, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V?
}

public class ConcurrentHashMap<K, V>(initialCapacity: Int) : ConcurrentMap<K, V> {
    public constructor() : this(16)
    public constructor(m: Map<out K, V>) : this(16) {
        putAll(m)
    }

    // SEGMENTS maps, each behind its own mutex (a power of two). It is 1: with 8 threads the segmented map measured 2 to 3 times slower than
    // one mutex, because every access to a shared object costs atomic reference count updates (docs/threads.md); the code stays segmented
    // so that the number can be raised where it pays.
    // (one object per segment: loading it from the array costs one reference count pair, not two)
    private val segs = Array<Segment<K, V>>(SEGMENTS) { Segment<K, V>() }

    private fun segmentOf(key: Any?): Int {
        val h = if (key == null) 0 else key.hashCode()
        return (h xor (h ushr 16)) and (SEGMENTS - 1)
    }

    public override val size: Int
        get() {
            var n = 0
            for (i in 0 until SEGMENTS) {
                val g = segs[i]
                g.lock()
                n += g.map.size
                g.unlock()
            }
            return n
        }

    public override fun isEmpty(): Boolean {
        for (i in 0 until SEGMENTS) {
            val g = segs[i]
            g.lock()
            val e = g.map.isEmpty()
            g.unlock()
            if (!e) return false
        }
        return true
    }

    public override fun containsKey(key: K): Boolean {
        val g = segs[segmentOf(key)]
        g.lock()
        val r = g.map.containsKey(key)
        g.unlock()
        return r
    }

    public override fun containsValue(value: V): Boolean {
        for (i in 0 until SEGMENTS) {
            val g = segs[i]
            g.lock()
            val r = g.map.containsValue(value)
            g.unlock()
            if (r) return true
        }
        return false
    }

    public override fun get(key: K): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val r = g.map[key]
        g.unlock()
        return r
    }

    public override fun put(key: K, value: V): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val r = g.map.put(key, value)
        g.unlock()
        return r
    }

    public override fun remove(key: K): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val r = g.map.remove(key)
        g.unlock()
        return r
    }

    public fun remove(key: K, value: V): Boolean {
        val g = segs[segmentOf(key)]
        g.lock()
        var r = false
        val m = g.map
        if (m.containsKey(key) && m[key] == value) {
            m.remove(key)
            r = true
        }
        g.unlock()
        return r
    }

    public override fun putAll(from: Map<out K, V>) {
        for (e in from.entries) put(e.key, e.value)
    }

    public override fun clear() {
        for (i in 0 until SEGMENTS) {
            val g = segs[i]
            g.lock()
            g.map.clear()
            g.unlock()
        }
    }

    private fun copy(): LinkedHashMap<K, V> {
        val c = LinkedHashMap<K, V>()
        for (i in 0 until SEGMENTS) {
            val g = segs[i]
            g.lock()
            c.putAll(g.map)
            g.unlock()
        }
        return c
    }

    public override val keys: MutableSet<K>
        get() = copy().keys

    public override val values: MutableCollection<V>
        get() = copy().values

    public override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = copy().entries

    public override fun putIfAbsent(key: K, value: V): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val cur = g.map[key]
        if (cur == null) g.map.put(key, value)
        g.unlock()
        return cur
    }

    public override fun replace(key: K, value: V): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        var r: V? = null
        if (g.map.containsKey(key)) r = g.map.put(key, value)
        g.unlock()
        return r
    }

    public override fun replace(key: K, oldValue: V, newValue: V): Boolean {
        val g = segs[segmentOf(key)]
        g.lock()
        var ok = false
        val m = g.map
        if (m.containsKey(key) && m[key] == oldValue) {
            m.put(key, newValue)
            ok = true
        }
        g.unlock()
        return ok
    }

    public override fun computeIfAbsent(key: K, mappingFunction: Function<in K, out V>): V {
        val g = segs[segmentOf(key)]
        g.lock()
        var cur = g.map[key]
        if (cur == null) {
            cur = mappingFunction.apply(key)
            if (cur != null) g.map.put(key, cur)
        }
        g.unlock()
        return cur!!
    }

    public override fun computeIfPresent(key: K, remappingFunction: BiFunction<in K, in V, out V?>): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val m = g.map
        val cur = m[key]
        var r: V? = null
        if (cur != null) {
            r = remappingFunction.apply(key, cur)
            if (r != null) m.put(key, r) else m.remove(key)
        }
        g.unlock()
        return r
    }

    public override fun compute(key: K, remappingFunction: BiFunction<in K, in V?, out V?>): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val m = g.map
        val r = remappingFunction.apply(key, m[key])
        if (r != null) m.put(key, r) else m.remove(key)
        g.unlock()
        return r
    }

    public override fun merge(key: K, value: V, remappingFunction: BiFunction<in V, in V, out V?>): V? {
        val g = segs[segmentOf(key)]
        g.lock()
        val m = g.map
        val cur = m[key]
        val r = if (cur == null) value else remappingFunction.apply(cur, value)
        if (r != null) m.put(key, r) else m.remove(key)
        g.unlock()
        return r
    }

    public fun getOrDefault(key: Any?, defaultValue: V): V {
        val g = segs[segmentOf(key)]
        g.lock()
        @Suppress("UNCHECKED_CAST")
        val k = key as K
        val m = g.map
        val r = if (m.containsKey(k)) m[k] as V else defaultValue
        g.unlock()
        return r
    }

    override fun equals(other: Any?): Boolean = other is Map<*, *> && copy() == other
    override fun hashCode(): Int = copy().hashCode()
    override fun toString(): String = copy().toString()

    private companion object {
        const val SEGMENTS = 1
    }
}

internal class Segment<K, V> {
    val mutex = LongArray(16)
    val map = HashMap<K, V>()

    init {
        sysLockInit(mutex)
    }

    fun lock() {
        sysLock(mutex)
    }

    fun unlock() {
        sysUnlock(mutex)
    }
}

public interface BlockingQueue<E> : MutableCollection<E> {
    public fun offer(e: E): Boolean
    public fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean
    public fun put(e: E)
    public fun poll(): E?
    public fun poll(timeout: Long, unit: TimeUnit): E?
    public fun take(): E
    public fun peek(): E?
    public fun remainingCapacity(): Int
    public fun drainTo(c: MutableCollection<in E>): Int
    public fun drainTo(c: MutableCollection<in E>, maxElements: Int): Int
}

/** The queue behind ConcurrentLinkedQueue, LinkedBlockingQueue and ArrayBlockingQueue; [capacity] bounds the size. */
public open class LinkedBlockingQueue<E>(private val capacity: Int) : AbstractMutableCollection<E>(), BlockingQueue<E> {
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

    override fun add(element: E): Boolean {
        if (!offer(element)) throw IllegalStateException("Queue full")
        return true
    }

    public override fun offer(e: E): Boolean {
        lock.lock()
        val ok = items.size < capacity
        if (ok) {
            items.addLast(e)
            notEmpty.signal()
        }
        lock.unlock()
        return ok
    }

    public override fun offer(e: E, timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        var ok = true
        while (items.size >= capacity) {
            if (awaitDeadline(notFull, end)) {
                ok = items.size < capacity
                break
            }
        }
        if (ok) {
            items.addLast(e)
            notEmpty.signal()
        }
        lock.unlock()
        return ok
    }

    public override fun put(e: E) {
        lock.lock()
        while (items.size >= capacity) notFull.await()
        items.addLast(e)
        notEmpty.signal()
        lock.unlock()
    }

    public override fun poll(): E? {
        lock.lock()
        val r = if (items.isEmpty()) null else items.removeFirst()
        if (r != null) notFull.signal()
        lock.unlock()
        return r
    }

    public override fun poll(timeout: Long, unit: TimeUnit): E? {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (items.isEmpty()) {
            if (awaitDeadline(notEmpty, end)) break
        }
        val r = if (items.isEmpty()) null else items.removeFirst()
        if (r != null) notFull.signal()
        lock.unlock()
        return r
    }

    public override fun take(): E {
        lock.lock()
        while (items.isEmpty()) notEmpty.await()
        val r = items.removeFirst()
        notFull.signal()
        lock.unlock()
        return r
    }

    public override fun peek(): E? {
        lock.lock()
        val r = if (items.isEmpty()) null else items.first()
        lock.unlock()
        return r
    }

    public fun element(): E = peek() ?: throw NoSuchElementException()

    public fun remove(): E = poll() ?: throw NoSuchElementException()

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
}

public class ArrayBlockingQueue<E>(capacity: Int, fair: Boolean) : LinkedBlockingQueue<E>(capacity) {
    public constructor(capacity: Int) : this(capacity, false)
}

public class LinkedBlockingDeque<E> : LinkedBlockingQueue<E> {
    public constructor() : super()
    public constructor(capacity: Int) : super(capacity)
}

public class ConcurrentLinkedQueue<E> : LinkedBlockingQueue<E> {
    public constructor() : super()
    public constructor(c: Collection<E>) : super(c)
}

public class CopyOnWriteArrayList<E> : AbstractMutableList<E> {
    public constructor() : super()
    public constructor(c: Collection<E>) : super() {
        for (e in c) items.add(e)
    }

    private val lock = Lock()
    private val items = ArrayList<E>()

    override val size: Int
        get() {
            lock.lock()
            val r = items.size
            lock.unlock()
            return r
        }

    override fun get(index: Int): E {
        lock.lock()
        if (index < 0 || index >= items.size) {
            lock.unlock()
            throw IndexOutOfBoundsException("Index: " + index + ", Size: " + items.size)
        }
        val r = items[index]
        lock.unlock()
        return r
    }

    override fun set(index: Int, element: E): E {
        lock.lock()
        val r = items.set(index, element)
        lock.unlock()
        return r
    }

    override fun add(index: Int, element: E) {
        lock.lock()
        items.add(index, element)
        lock.unlock()
    }

    override fun removeAt(index: Int): E {
        lock.lock()
        val r = items.removeAt(index)
        lock.unlock()
        return r
    }

    public fun addIfAbsent(e: E): Boolean {
        lock.lock()
        val ok = !items.contains(e)
        if (ok) items.add(e)
        lock.unlock()
        return ok
    }

    override fun iterator(): MutableIterator<E> {
        lock.lock()
        val snapshot = ArrayList<E>(items)
        lock.unlock()
        return snapshot.iterator()
    }
}
