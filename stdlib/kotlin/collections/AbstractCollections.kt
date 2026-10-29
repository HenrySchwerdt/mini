/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/Abstract{Collection,List,Set,Map}.kt and the
 * Abstract{Mutable*} classes (which on the JVM are java.util.Abstract*).
 */
package kotlin.collections

public interface RandomAccess

public abstract class AbstractCollection<out E> protected constructor() : Collection<E> {
    abstract override val size: Int
    abstract override fun iterator(): Iterator<E>

    override fun contains(element: @UnsafeVariance E): Boolean {
        val it = iterator()
        while (it.hasNext()) {
            if (it.next() == element) return true
        }
        return false
    }

    override fun containsAll(elements: Collection<@UnsafeVariance E>): Boolean {
        val it = elements.iterator()
        while (it.hasNext()) {
            if (!contains(it.next())) return false
        }
        return true
    }

    override fun isEmpty(): Boolean = size == 0

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        val it = iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(", ")
            first = false
            val e = it.next()
            if (e === this) sb.append("(this Collection)") else sb.append(e)
        }
        sb.append(']')
        return sb.toString()
    }
}

public abstract class AbstractMutableCollection<E> protected constructor() : MutableCollection<E> {
    abstract override val size: Int
    abstract override fun iterator(): MutableIterator<E>
    abstract override fun add(element: E): Boolean

    override fun contains(element: E): Boolean {
        val it = iterator()
        while (it.hasNext()) {
            if (it.next() == element) return true
        }
        return false
    }

    override fun containsAll(elements: Collection<E>): Boolean {
        val it = elements.iterator()
        while (it.hasNext()) {
            if (!contains(it.next())) return false
        }
        return true
    }

    override fun isEmpty(): Boolean = size == 0

    override fun remove(element: E): Boolean {
        val it = iterator()
        while (it.hasNext()) {
            if (it.next() == element) {
                it.remove()
                return true
            }
        }
        return false
    }

    override fun addAll(elements: Collection<E>): Boolean {
        var modified = false
        val it = elements.iterator()
        while (it.hasNext()) {
            if (add(it.next())) modified = true
        }
        return modified
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        var modified = false
        val it = iterator()
        while (it.hasNext()) {
            if (elements.contains(it.next())) {
                it.remove()
                modified = true
            }
        }
        return modified
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        var modified = false
        val it = iterator()
        while (it.hasNext()) {
            if (!elements.contains(it.next())) {
                it.remove()
                modified = true
            }
        }
        return modified
    }

    override fun clear() {
        val it = iterator()
        while (it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        val it = iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(", ")
            first = false
            val e = it.next()
            if (e === this) sb.append("(this Collection)") else sb.append(e)
        }
        sb.append(']')
        return sb.toString()
    }
}

public abstract class AbstractList<out E> protected constructor() : AbstractCollection<E>(), List<E> {
    abstract override val size: Int
    abstract override fun get(index: Int): E

    override fun iterator(): Iterator<E> = ListIteratorImpl(this, 0)

    override fun listIterator(): ListIterator<E> = ListIteratorImpl(this, 0)

    override fun listIterator(index: Int): ListIterator<E> {
        if (index < 0 || index > size) mini.internal.throwIndexOutOfBounds("index: $index, size: $size")
        return ListIteratorImpl(this, index)
    }

    override fun indexOf(element: @UnsafeVariance E): Int {
        var i = 0
        while (i < size) {
            if (get(i) == element) return i
            i++
        }
        return -1
    }

    override fun lastIndexOf(element: @UnsafeVariance E): Int {
        var i = size - 1
        while (i >= 0) {
            if (get(i) == element) return i
            i--
        }
        return -1
    }

    override fun contains(element: @UnsafeVariance E): Boolean = indexOf(element) >= 0

    override fun subList(fromIndex: Int, toIndex: Int): List<E> {
        if (fromIndex < 0 || toIndex > size) mini.internal.throwIndexOutOfBounds("fromIndex: $fromIndex, toIndex: $toIndex, size: $size")
        if (fromIndex > toIndex) mini.internal.throwIllegalArgument("fromIndex: $fromIndex > toIndex: $toIndex")
        return SubList(this, fromIndex, toIndex)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is List<*>) return false
        if (size != other.size) return false
        var i = 0
        while (i < size) {
            if (get(i) != other[i]) return false
            i++
        }
        return true
    }

    override fun hashCode(): Int {
        var hashCode = 1
        var i = 0
        while (i < size) {
            val e = get(i)
            hashCode = 31 * hashCode + (e?.hashCode() ?: 0)
            i++
        }
        return hashCode
    }
}

internal class SubList<out E>(private val list: List<E>, private val fromIndex: Int, toIndex: Int) : AbstractList<E>(), RandomAccess {
    private var _size: Int = toIndex - fromIndex

    override val size: Int
        get() = _size

    override fun get(index: Int): E {
        if (index < 0 || index >= _size) mini.internal.throwIndexOutOfBounds("index: $index, size: $_size")
        return list[fromIndex + index]
    }
}

internal class ListIteratorImpl<out E>(private val list: List<E>, start: Int) : ListIterator<E> {
    private var index: Int = start

    override fun hasNext(): Boolean = index < list.size

    override fun next(): E {
        if (!hasNext()) mini.internal.throwNoSuchElement(null)
        return list[index++]
    }

    override fun hasPrevious(): Boolean = index > 0

    override fun previous(): E {
        if (!hasPrevious()) mini.internal.throwNoSuchElement(null)
        index--
        return list[index]
    }

    override fun nextIndex(): Int = index

    override fun previousIndex(): Int = index - 1
}

public abstract class AbstractMutableList<E> protected constructor() : AbstractMutableCollection<E>(), MutableList<E> {
    abstract override val size: Int
    abstract override fun get(index: Int): E
    abstract override fun set(index: Int, element: E): E
    abstract override fun add(index: Int, element: E)
    abstract override fun removeAt(index: Int): E

    override fun add(element: E): Boolean {
        add(size, element)
        return true
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        if (index < 0 || index > size) mini.internal.throwIndexOutOfBounds("index: $index, size: $size")
        var i = index
        val it = elements.iterator()
        var modified = false
        while (it.hasNext()) {
            add(i++, it.next())
            modified = true
        }
        return modified
    }

    override fun clear() {
        var i = size - 1
        while (i >= 0) {
            removeAt(i)
            i--
        }
    }

    override fun remove(element: E): Boolean {
        val i = indexOf(element)
        if (i < 0) return false
        removeAt(i)
        return true
    }

    override fun iterator(): MutableIterator<E> = MutableListIteratorImpl(this, 0)

    override fun listIterator(): MutableListIterator<E> = MutableListIteratorImpl(this, 0)

    override fun listIterator(index: Int): MutableListIterator<E> {
        if (index < 0 || index > size) mini.internal.throwIndexOutOfBounds("index: $index, size: $size")
        return MutableListIteratorImpl(this, index)
    }

    override fun indexOf(element: E): Int {
        var i = 0
        while (i < size) {
            if (get(i) == element) return i
            i++
        }
        return -1
    }

    override fun lastIndexOf(element: E): Int {
        var i = size - 1
        while (i >= 0) {
            if (get(i) == element) return i
            i--
        }
        return -1
    }

    override fun contains(element: E): Boolean = indexOf(element) >= 0

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
        if (fromIndex < 0 || toIndex > size) mini.internal.throwIndexOutOfBounds("fromIndex: $fromIndex, toIndex: $toIndex, size: $size")
        if (fromIndex > toIndex) mini.internal.throwIllegalArgument("fromIndex: $fromIndex > toIndex: $toIndex")
        return MutableSubList(this, fromIndex, toIndex)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is List<*>) return false
        if (size != other.size) return false
        var i = 0
        while (i < size) {
            if (get(i) != other[i]) return false
            i++
        }
        return true
    }

    override fun hashCode(): Int {
        var hashCode = 1
        var i = 0
        while (i < size) {
            val e = get(i)
            hashCode = 31 * hashCode + (e?.hashCode() ?: 0)
            i++
        }
        return hashCode
    }
}

internal class MutableSubList<E>(private val list: MutableList<E>, private val fromIndex: Int, toIndex: Int) :
    AbstractMutableList<E>(), RandomAccess {
    private var _size: Int = toIndex - fromIndex

    override val size: Int
        get() = _size

    override fun get(index: Int): E {
        if (index < 0 || index >= _size) mini.internal.throwIndexOutOfBounds("index: $index, size: $_size")
        return list[fromIndex + index]
    }

    override fun set(index: Int, element: E): E {
        if (index < 0 || index >= _size) mini.internal.throwIndexOutOfBounds("index: $index, size: $_size")
        return list.set(fromIndex + index, element)
    }

    override fun add(index: Int, element: E) {
        if (index < 0 || index > _size) mini.internal.throwIndexOutOfBounds("index: $index, size: $_size")
        list.add(fromIndex + index, element)
        _size++
    }

    override fun removeAt(index: Int): E {
        if (index < 0 || index >= _size) mini.internal.throwIndexOutOfBounds("index: $index, size: $_size")
        val r = list.removeAt(fromIndex + index)
        _size--
        return r
    }
}

internal class MutableListIteratorImpl<E>(private val list: MutableList<E>, start: Int) : MutableListIterator<E> {
    private var index: Int = start
    private var last: Int = -1

    override fun hasNext(): Boolean = index < list.size

    override fun next(): E {
        if (!hasNext()) mini.internal.throwNoSuchElement(null)
        last = index
        return list[index++]
    }

    override fun hasPrevious(): Boolean = index > 0

    override fun previous(): E {
        if (!hasPrevious()) mini.internal.throwNoSuchElement(null)
        index--
        last = index
        return list[index]
    }

    override fun nextIndex(): Int = index

    override fun previousIndex(): Int = index - 1

    override fun remove() {
        if (last < 0) mini.internal.throwIllegalState(null)
        list.removeAt(last)
        if (last < index) index--
        last = -1
    }

    override fun set(element: E) {
        if (last < 0) mini.internal.throwIllegalState(null)
        list[last] = element
    }

    override fun add(element: E) {
        list.add(index, element)
        index++
        last = -1
    }
}

public abstract class AbstractSet<out E> protected constructor() : AbstractCollection<E>(), Set<E> {
    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Set<*>) return false
        if (size != other.size) return false
        return containsAll(other as Collection<E>)
    }

    override fun hashCode(): Int {
        var h = 0
        val it = iterator()
        while (it.hasNext()) {
            val e = it.next()
            h += e?.hashCode() ?: 0
        }
        return h
    }
}

public abstract class AbstractMutableSet<E> protected constructor() : AbstractMutableCollection<E>(), MutableSet<E> {
    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Set<*>) return false
        if (size != other.size) return false
        return containsAll(other as Collection<E>)
    }

    override fun hashCode(): Int {
        var h = 0
        val it = iterator()
        while (it.hasNext()) {
            val e = it.next()
            h += e?.hashCode() ?: 0
        }
        return h
    }
}

public abstract class AbstractMap<K, out V> protected constructor() : Map<K, V> {
    abstract override val entries: Set<Map.Entry<K, V>>

    override fun containsKey(key: K): Boolean = findEntry(key) != null

    override fun containsValue(value: @UnsafeVariance V): Boolean {
        val it = entries.iterator()
        while (it.hasNext()) {
            if (it.next().value == value) return true
        }
        return false
    }

    private fun findEntry(key: K): Map.Entry<K, V>? {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == key) return e
        }
        return null
    }

    override fun get(key: K): V? = findEntry(key)?.value

    override fun isEmpty(): Boolean = size == 0

    override val size: Int
        get() = entries.size

    override val keys: Set<K>
        get() {
            val result = LinkedHashSet<K>()
            val it = entries.iterator()
            while (it.hasNext()) result.add(it.next().key)
            return result
        }

    override val values: Collection<V>
        get() {
            val result = ArrayList<V>()
            val it = entries.iterator()
            while (it.hasNext()) result.add(it.next().value)
            return result
        }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map<*, *>) return false
        if (size != other.size) return false
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = e.value
            val key = e.key
            @Suppress("UNCHECKED_CAST")
            val ov = (other as Map<Any?, Any?>)[key]
            if (v != ov) return false
            if (v == null && !other.containsKey(key)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        val it = entries.iterator()
        while (it.hasNext()) h += it.next().hashCode()
        return h
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        val it = entries.iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(", ")
            first = false
            val e = it.next()
            sb.append(if (e.key === this) "(this Map)" else e.key.toString())
            sb.append('=')
            sb.append(if (e.value === this) "(this Map)" else e.value.toString())
        }
        sb.append('}')
        return sb.toString()
    }
}

public abstract class AbstractMutableMap<K, V> protected constructor() : MutableMap<K, V> {
    abstract override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
    abstract override fun put(key: K, value: V): V?

    override fun containsKey(key: K): Boolean {
        val it = entries.iterator()
        while (it.hasNext()) {
            if (it.next().key == key) return true
        }
        return false
    }

    override fun containsValue(value: V): Boolean {
        val it = entries.iterator()
        while (it.hasNext()) {
            if (it.next().value == value) return true
        }
        return false
    }

    override fun get(key: K): V? {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == key) return e.value
        }
        return null
    }

    override fun remove(key: K): V? {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key == key) {
                val v = e.value
                it.remove()
                return v
            }
        }
        return null
    }

    override fun putAll(from: Map<out K, V>) {
        val it = from.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            put(e.key, e.value)
        }
    }

    override fun clear() {
        entries.clear()
    }

    override fun isEmpty(): Boolean = size == 0

    override val size: Int
        get() = entries.size

    override val keys: MutableSet<K>
        get() {
            val result = LinkedHashSet<K>()
            val it = entries.iterator()
            while (it.hasNext()) result.add(it.next().key)
            return result
        }

    override val values: MutableCollection<V>
        get() {
            val result = ArrayList<V>()
            val it = entries.iterator()
            while (it.hasNext()) result.add(it.next().value)
            return result
        }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map<*, *>) return false
        if (size != other.size) return false
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = e.value
            val key = e.key
            @Suppress("UNCHECKED_CAST")
            val ov = (other as Map<Any?, Any?>)[key]
            if (v != ov) return false
            if (v == null && !other.containsKey(key)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        val it = entries.iterator()
        while (it.hasNext()) h += it.next().hashCode()
        return h
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        val it = entries.iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(", ")
            first = false
            val e = it.next()
            sb.append(if (e.key === this) "(this Map)" else e.key.toString())
            sb.append('=')
            sb.append(if (e.value === this) "(this Map)" else e.value.toString())
        }
        sb.append('}')
        return sb.toString()
    }
}
