/*
 * java.util.LinkedList (on a circular array deque: O(1) at both ends, O(n) in the middle), Vector, Stack, PriorityQueue (the binary heap
 * of the JDK, so iteration order and toString equal the JVM's) and StringJoiner.
 */
package java.util

public open class LinkedList<E> : AbstractMutableList<E> {
    private val d: ArrayDeque<E>

    public constructor() {
        d = ArrayDeque<E>()
    }

    public constructor(c: Collection<E>) {
        d = ArrayDeque<E>()
        for (x in c) d.addLast(x)
    }

    override val size: Int
        get() = d.size

    override fun get(index: Int): E = d[index]

    override fun set(index: Int, element: E): E = d.set(index, element)

    override fun add(element: E): Boolean {
        d.addLast(element)
        return true
    }

    override fun add(index: Int, element: E) {
        d.add(index, element)
    }

    override fun removeAt(index: Int): E = d.removeAt(index)

    override fun clear() {
        d.clear()
    }

    public fun addFirst(e: E) { d.addFirst(e) }
    public fun addLast(e: E) { d.addLast(e) }
    public fun offer(e: E): Boolean { d.addLast(e); return true }
    public fun offerFirst(e: E): Boolean { d.addFirst(e); return true }
    public fun offerLast(e: E): Boolean { d.addLast(e); return true }
    public fun push(e: E) { d.addFirst(e) }

    public fun removeFirst(): E {
        if (d.isEmpty()) throw NoSuchElementException()
        return d.removeFirst()
    }

    public fun removeLast(): E {
        if (d.isEmpty()) throw NoSuchElementException()
        return d.removeLast()
    }

    public fun pop(): E = removeFirst()
    public fun remove(): E = removeFirst()

    public fun getFirst(): E {
        if (d.isEmpty()) throw NoSuchElementException()
        return d.first()
    }

    public fun getLast(): E {
        if (d.isEmpty()) throw NoSuchElementException()
        return d.last()
    }

    public fun element(): E = getFirst()
    public fun peek(): E? = if (d.isEmpty()) null else d.first()
    public fun peekFirst(): E? = peek()
    public fun peekLast(): E? = if (d.isEmpty()) null else d.last()
    public fun poll(): E? = if (d.isEmpty()) null else d.removeFirst()
    public fun pollFirst(): E? = poll()
    public fun pollLast(): E? = if (d.isEmpty()) null else d.removeLast()
}

public open class Vector<E> : ArrayList<E> {
    public constructor() : super()
    public constructor(initialCapacity: Int) : super(initialCapacity)
    public constructor(c: Collection<E>) : super(c)

    public fun addElement(e: E) { add(e) }
    public fun elementAt(i: Int): E = get(i)
    public fun firstElement(): E {
        if (isEmpty()) throw NoSuchElementException()
        return get(0)
    }

    public fun lastElement(): E {
        if (isEmpty()) throw NoSuchElementException()
        return get(size - 1)
    }

    public fun removeAllElements() { clear() }
    public fun insertElementAt(e: E, i: Int) { add(i, e) }
    public fun removeElementAt(i: Int) { removeAt(i) }
}

public open class Stack<E> : Vector<E>() {
    public fun push(e: E): E {
        add(e)
        return e
    }

    public fun pop(): E {
        if (isEmpty()) throw EmptyStackException()
        return removeAt(size - 1)
    }

    public fun peek(): E {
        if (isEmpty()) throw EmptyStackException()
        return get(size - 1)
    }

    public fun empty(): Boolean = isEmpty()

    public fun search(o: Any?): Int {
        val i = lastIndexOf(o)
        return if (i >= 0) size - i else -1
    }
}

public class EmptyStackException : RuntimeException()

public class PriorityQueue<E> : AbstractMutableCollection<E> {
    private var queue: ArrayList<E> = ArrayList<E>()
    private val cmp: Comparator<in E>?

    public constructor() {
        cmp = null
    }

    public constructor(initialCapacity: Int) {
        if (initialCapacity < 1) throw IllegalArgumentException()
        cmp = null
    }

    public constructor(comparator: Comparator<in E>?) {
        cmp = comparator
    }

    public constructor(initialCapacity: Int, comparator: Comparator<in E>?) {
        if (initialCapacity < 1) throw IllegalArgumentException()
        cmp = comparator
    }

    public constructor(c: Collection<E>) {
        cmp = if (c is PriorityQueue<*>) (c as PriorityQueue<E>).cmp else null
        for (x in c) {
            if (x == null) throw NullPointerException()
            queue.add(x)
        }
        var i = (queue.size ushr 1) - 1
        while (i >= 0) {
            siftDown(i, queue[i])
            i--
        }
    }

    public fun comparator(): Comparator<in E>? = cmp

    override val size: Int
        get() = queue.size

    @Suppress("UNCHECKED_CAST")
    private fun compare(a: E, b: E): Int {
        val c = cmp
        if (c != null) return c.compare(a, b)
        return (a as Comparable<Any?>).compareTo(b)
    }

    private fun siftUp(k0: Int, x: E) {
        var k = k0
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val e = queue[parent]
            if (compare(x, e) >= 0) break
            queue[k] = e
            k = parent
        }
        queue[k] = x
    }

    private fun siftDown(k0: Int, x: E) {
        var k = k0
        val n = queue.size
        val half = n ushr 1
        while (k < half) {
            var child = (k shl 1) + 1
            var c = queue[child]
            val right = child + 1
            if (right < n && compare(c, queue[right]) > 0) {
                child = right
                c = queue[child]
            }
            if (compare(x, c) <= 0) break
            queue[k] = c
            k = child
        }
        queue[k] = x
    }

    override fun add(element: E): Boolean = offer(element)

    public fun offer(e: E): Boolean {
        if (e == null) throw NullPointerException()
        queue.add(e)
        siftUp(queue.size - 1, e)
        return true
    }

    public fun peek(): E? = if (queue.isEmpty()) null else queue[0]

    public fun element(): E {
        if (queue.isEmpty()) throw NoSuchElementException()
        return queue[0]
    }

    public fun poll(): E? {
        if (queue.isEmpty()) return null
        val result = queue[0]
        val last = queue.removeAt(queue.size - 1)
        if (queue.size > 0) siftDown(0, last)
        return result
    }

    public fun remove(): E {
        if (queue.isEmpty()) throw NoSuchElementException()
        return poll()!!
    }

    override fun remove(element: E): Boolean {
        val i = queue.indexOf(element)
        if (i < 0) return false
        removeAtIndex(i)
        return true
    }

    private fun removeAtIndex(i: Int) {
        val s = queue.size - 1
        if (s == i) {
            queue.removeAt(s)
        } else {
            val moved = queue.removeAt(s)
            siftDown(i, moved)
            if (queue[i] === moved) siftUp(i, moved)
        }
    }

    override fun contains(element: E): Boolean = queue.indexOf(element) >= 0

    override fun clear() {
        queue.clear()
    }

    override fun iterator(): MutableIterator<E> {
        val it = queue.iterator()
        return object : MutableIterator<E> {
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): E = it.next()
            override fun remove() {
                throw UnsupportedOperationException()
            }
        }
    }
}

public class StringJoiner {
    private val delimiter: String
    private val prefix: String
    private val suffix: String
    private var value: StringBuilder? = null
    private var emptyValue: String? = null

    public constructor(delimiter: CharSequence) {
        this.delimiter = delimiter.toString()
        this.prefix = ""
        this.suffix = ""
    }

    public constructor(delimiter: CharSequence, prefix: CharSequence, suffix: CharSequence) {
        this.delimiter = delimiter.toString()
        this.prefix = prefix.toString()
        this.suffix = suffix.toString()
    }

    public fun add(s: CharSequence?): StringJoiner {
        val v = value
        if (v == null) {
            val nv = StringBuilder()
            nv.append(prefix)
            nv.append(s.toString())
            value = nv
        } else {
            v.append(delimiter)
            v.append(s.toString())
        }
        return this
    }

    public fun setEmptyValue(s: CharSequence): StringJoiner {
        emptyValue = s.toString()
        return this
    }

    public fun length(): Int = toString().length

    override fun toString(): String {
        val v = value
        if (v == null) return emptyValue ?: (prefix + suffix)
        return v.toString() + suffix
    }
}
