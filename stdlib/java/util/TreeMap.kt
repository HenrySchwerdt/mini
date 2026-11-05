/*
 * java.util.TreeMap and TreeSet as sorted arrays (binary search; O(n) insertion, O(log n) lookup).
 * The sub-map / sub-set functions (headMap, tailMap, subMap, descendingMap, ...) return copies, not views.
 */
package java.util

import kotlin.collections.AbstractMutableMap
import kotlin.collections.AbstractMutableSet

private fun <K> compareKeys(cmp: Comparator<in K>?, a: K, b: K): Int {
    if (cmp != null) return cmp.compare(a, b)
    @Suppress("UNCHECKED_CAST")
    return (a as Comparable<Any?>).compareTo(b)
}

public class TreeEntry<K, V>(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V {
        val old = value
        value = newValue
        return old
    }

    override fun toString(): String = key.toString() + "=" + value
    override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
}

public class TreeMap<K, V> : AbstractMutableMap<K, V> {
    private val comparatorOrNull: Comparator<in K>?
    private val list: ArrayList<TreeEntry<K, V>> = ArrayList<TreeEntry<K, V>>()

    public constructor() : super() {
        comparatorOrNull = null
    }

    public constructor(comparator: Comparator<in K>?) : super() {
        comparatorOrNull = comparator
    }

    public constructor(from: Map<out K, V>) : super() {
        comparatorOrNull = null
        putAll(from)
    }

    public fun comparator(): Comparator<in K>? = comparatorOrNull

    /** index of [key], or -(insertion point) - 1 */
    private fun search(key: K): Int {
        var low = 0
        var high = list.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = compareKeys(comparatorOrNull, list[mid].key, key)
            if (c < 0) low = mid + 1 else if (c > 0) high = mid - 1 else return mid
        }
        return -(low + 1)
    }

    override val size: Int
        get() = list.size

    override fun put(key: K, value: V): V? {
        val i = search(key)
        if (i >= 0) {
            val e = list[i]
            val old = e.value
            e.value = value
            return old
        }
        list.add(-(i + 1), TreeEntry(key, value))
        return null
    }

    override fun get(key: K): V? {
        val i = search(key)
        return if (i >= 0) list[i].value else null
    }

    override fun containsKey(key: K): Boolean = search(key) >= 0

    override fun remove(key: K): V? {
        val i = search(key)
        if (i < 0) return null
        return list.removeAt(i).value
    }

    override fun clear() {
        list.clear()
    }

    private class Entries<K, V>(private val map: TreeMap<K, V>) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int
            get() = map.list.size

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = object : MutableIterator<MutableMap.MutableEntry<K, V>> {
            private var index = 0
            override fun hasNext(): Boolean = index < map.list.size
            override fun next(): MutableMap.MutableEntry<K, V> {
                if (index >= map.list.size) throw NoSuchElementException()
                return map.list[index++]
            }
            override fun remove() {
                if (index == 0) throw IllegalStateException()
                index--
                map.list.removeAt(index)
            }
        }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

        override fun clear() {
            map.list.clear()
        }
    }

    private var entriesView: MutableSet<MutableMap.MutableEntry<K, V>>? = null

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            var v = entriesView
            if (v == null) {
                v = Entries(this)
                entriesView = v
            }
            return v
        }

    public fun firstKey(): K {
        if (list.isEmpty()) throw NoSuchElementException()
        return list[0].key
    }

    public fun lastKey(): K {
        if (list.isEmpty()) throw NoSuchElementException()
        return list[list.size - 1].key
    }

    public fun firstEntry(): Map.Entry<K, V>? = if (list.isEmpty()) null else list[0]

    public fun lastEntry(): Map.Entry<K, V>? = if (list.isEmpty()) null else list[list.size - 1]

    public fun pollFirstEntry(): Map.Entry<K, V>? = if (list.isEmpty()) null else list.removeAt(0)

    public fun pollLastEntry(): Map.Entry<K, V>? = if (list.isEmpty()) null else list.removeAt(list.size - 1)

    private fun floorIndex(key: K): Int {
        val i = search(key)
        return if (i >= 0) i else -(i + 1) - 1
    }

    private fun ceilingIndex(key: K): Int {
        val i = search(key)
        return if (i >= 0) i else -(i + 1)
    }

    private fun lowerIndex(key: K): Int {
        val i = search(key)
        return if (i >= 0) i - 1 else -(i + 1) - 1
    }

    private fun higherIndex(key: K): Int {
        val i = search(key)
        return if (i >= 0) i + 1 else -(i + 1)
    }

    private fun entryAt(i: Int): Map.Entry<K, V>? = if (i >= 0 && i < list.size) list[i] else null

    private fun keyAt(i: Int): K? = if (i >= 0 && i < list.size) list[i].key else null

    public fun floorEntry(key: K): Map.Entry<K, V>? = entryAt(floorIndex(key))
    public fun ceilingEntry(key: K): Map.Entry<K, V>? = entryAt(ceilingIndex(key))
    public fun lowerEntry(key: K): Map.Entry<K, V>? = entryAt(lowerIndex(key))
    public fun higherEntry(key: K): Map.Entry<K, V>? = entryAt(higherIndex(key))
    public fun floorKey(key: K): K? = keyAt(floorIndex(key))
    public fun ceilingKey(key: K): K? = keyAt(ceilingIndex(key))
    public fun lowerKey(key: K): K? = keyAt(lowerIndex(key))
    public fun higherKey(key: K): K? = keyAt(higherIndex(key))

    private fun copyRange(from: Int, to: Int): TreeMap<K, V> {
        val r = TreeMap<K, V>(comparatorOrNull)
        var i = from
        while (i < to) {
            r.list.add(TreeEntry(list[i].key, list[i].value))
            i++
        }
        return r
    }

    public fun headMap(toKey: K, inclusive: Boolean = false): TreeMap<K, V> = copyRange(0, if (inclusive) floorIndex(toKey) + 1 else lowerIndex(toKey) + 1)

    public fun tailMap(fromKey: K, inclusive: Boolean = true): TreeMap<K, V> = copyRange(if (inclusive) ceilingIndex(fromKey) else higherIndex(fromKey), list.size)

    public fun subMap(fromKey: K, toKey: K): TreeMap<K, V> = copyRange(ceilingIndex(fromKey), lowerIndex(toKey) + 1)

    public fun descendingMap(): Map<K, V> {
        val r = LinkedHashMap<K, V>()
        var i = list.size - 1
        while (i >= 0) {
            r[list[i].key] = list[i].value
            i--
        }
        return r
    }

    public fun descendingKeySet(): List<K> {
        val r = ArrayList<K>()
        var i = list.size - 1
        while (i >= 0) {
            r.add(list[i].key)
            i--
        }
        return r
    }

    public fun navigableKeySet(): MutableSet<K> = keys
}

public class TreeSet<E> : AbstractMutableSet<E> {
    private val comparatorOrNull: Comparator<in E>?
    private val list: ArrayList<E> = ArrayList<E>()

    public constructor() : super() {
        comparatorOrNull = null
    }

    public constructor(comparator: Comparator<in E>?) : super() {
        comparatorOrNull = comparator
    }

    public constructor(c: Collection<E>) : super() {
        comparatorOrNull = null
        for (e in c) add(e)
    }

    public fun comparator(): Comparator<in E>? = comparatorOrNull

    private fun search(e: E): Int {
        var low = 0
        var high = list.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = compareKeys(comparatorOrNull, list[mid], e)
            if (c < 0) low = mid + 1 else if (c > 0) high = mid - 1 else return mid
        }
        return -(low + 1)
    }

    override val size: Int
        get() = list.size

    override fun add(element: E): Boolean {
        val i = search(element)
        if (i >= 0) return false
        list.add(-(i + 1), element)
        return true
    }

    override fun contains(element: E): Boolean = search(element) >= 0

    override fun remove(element: E): Boolean {
        val i = search(element)
        if (i < 0) return false
        list.removeAt(i)
        return true
    }

    override fun clear() {
        list.clear()
    }

    override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): E {
            if (index >= list.size) throw NoSuchElementException()
            return list[index++]
        }
        override fun remove() {
            if (index == 0) throw IllegalStateException()
            index--
            list.removeAt(index)
        }
    }

    public fun first(): E {
        if (list.isEmpty()) throw NoSuchElementException()
        return list[0]
    }

    public fun last(): E {
        if (list.isEmpty()) throw NoSuchElementException()
        return list[list.size - 1]
    }

    public fun pollFirst(): E? = if (list.isEmpty()) null else list.removeAt(0)

    public fun pollLast(): E? = if (list.isEmpty()) null else list.removeAt(list.size - 1)

    private fun floorIndex(e: E): Int {
        val i = search(e)
        return if (i >= 0) i else -(i + 1) - 1
    }

    private fun ceilingIndex(e: E): Int {
        val i = search(e)
        return if (i >= 0) i else -(i + 1)
    }

    private fun at(i: Int): E? = if (i >= 0 && i < list.size) list[i] else null

    public fun floor(e: E): E? = at(floorIndex(e))
    public fun ceiling(e: E): E? = at(ceilingIndex(e))
    public fun lower(e: E): E? {
        val i = search(e)
        return at(if (i >= 0) i - 1 else -(i + 1) - 1)
    }
    public fun higher(e: E): E? {
        val i = search(e)
        return at(if (i >= 0) i + 1 else -(i + 1))
    }

    private fun copyRange(from: Int, to: Int): TreeSet<E> {
        val r = TreeSet<E>(comparatorOrNull)
        var i = from
        while (i < to) {
            r.list.add(list[i])
            i++
        }
        return r
    }

    public fun headSet(toElement: E, inclusive: Boolean = false): TreeSet<E> {
        val i = search(toElement)
        val end = if (i >= 0) (if (inclusive) i + 1 else i) else -(i + 1)
        return copyRange(0, end)
    }

    public fun tailSet(fromElement: E, inclusive: Boolean = true): TreeSet<E> {
        val i = search(fromElement)
        val start = if (i >= 0) (if (inclusive) i else i + 1) else -(i + 1)
        return copyRange(start, list.size)
    }

    public fun subSet(fromElement: E, toElement: E): TreeSet<E> = copyRange(ceilingIndex(fromElement), ceilingIndex(toElement))

    public fun descendingSet(): List<E> = list.reversed()

    public fun descendingIterator(): Iterator<E> = list.reversed().iterator()
}
