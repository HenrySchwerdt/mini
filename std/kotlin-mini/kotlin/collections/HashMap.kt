/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reimplemented after java.util.HashMap / LinkedHashMap (which kotlin.collections.HashMap is on the JVM):
 * bucket array of chains, hash spreading `h ^ (h ushr 16)`, load factor 0.75, resize splits keep the
 * relative order; so the iteration order equals the JVM's. (No tree bins: only affects pathological hashes.)
 */
package kotlin.collections

import mini.internal.refArrayGet
import mini.internal.refArrayLength
import mini.internal.refArrayNew
import mini.internal.refArraySet

internal class HashNode<K, V>(
    val hash: Int,
    override val key: K,
    override var value: V,
    var next: HashNode<K, V>?,
) : MutableMap.MutableEntry<K, V> {
    // insertion-order links, only maintained by LinkedHashMap
    var before: HashNode<K, V>? = null
    var after: HashNode<K, V>? = null

    override fun setValue(newValue: V): V {
        val old = value
        value = newValue
        return old
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map.Entry<*, *>) return false
        return key == other.key && value == other.value
    }

    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)

    override fun toString(): String = "$key=$value"
}

public open class HashMap<K, V> : AbstractMutableMap<K, V> {
    private var table: Any = refArrayNew(0)
    private var count: Int = 0
    private var threshold: Int = 0

    // insertion-ordered list (LinkedHashMap only)
    internal var head: HashNode<K, V>? = null
    internal var tail: HashNode<K, V>? = null
    internal val linked: Boolean

    public constructor() {
        linked = false
    }

    public constructor(initialCapacity: Int) {
        if (initialCapacity < 0) throw IllegalArgumentException("Illegal initial capacity: $initialCapacity")
        linked = false
        threshold = tableSizeFor(initialCapacity)
    }

    public constructor(original: Map<out K, V>) {
        linked = false
        // java.util.HashMap#putMapEntries sizes the table from the argument
        val s = original.size
        if (s > 0) threshold = tableSizeFor((s.toDouble() / 0.75 + 1.0).toInt())
        putAll(original)
    }

    internal constructor(linked: Boolean, initialCapacity: Int) {
        this.linked = linked
        threshold = tableSizeFor(initialCapacity)
    }

    override val size: Int
        get() = count

    override fun isEmpty(): Boolean = count == 0

    private fun tableSizeFor(cap: Int): Int {
        var n = 1
        while (n < cap) n = n shl 1
        return n
    }

    private fun spread(key: Any?): Int {
        if (key == null) return 0
        val h = key.hashCode()
        return h xor (h ushr 16)
    }

    @Suppress("UNCHECKED_CAST")
    private fun slot(i: Int): HashNode<K, V>? = refArrayGet(table, i) as HashNode<K, V>?

    private fun findNode(key: Any?): HashNode<K, V>? {
        val n = refArrayLength(table)
        if (n == 0) return null
        val hash = spread(key)
        var e = slot((n - 1) and hash)
        while (e != null) {
            if (e.hash == hash && e.key == key) return e
            e = e.next
        }
        return null
    }

    override fun containsKey(key: K): Boolean = findNode(key) != null

    override fun get(key: K): V? = findNode(key)?.value

    override fun containsValue(value: V): Boolean {
        val n = refArrayLength(table)
        var i = 0
        while (i < n) {
            var e = slot(i)
            while (e != null) {
                if (e.value == value) return true
                e = e.next
            }
            i++
        }
        return false
    }

    override fun put(key: K, value: V): V? {
        var n = refArrayLength(table)
        if (n == 0) {
            resize()
            n = refArrayLength(table)
        }
        val hash = spread(key)
        val index = (n - 1) and hash
        var e = slot(index)
        if (e == null) {
            refArraySet(table, index, newNode(hash, key, value))
        } else {
            while (true) {
                if (e!!.hash == hash && e.key == key) {
                    val old = e.value
                    e.value = value
                    return old
                }
                val nx = e.next
                if (nx == null) {
                    e.next = newNode(hash, key, value)
                    break
                }
                e = nx
            }
        }
        count++
        if (count > threshold) resize()
        return null
    }

    private fun newNode(hash: Int, key: K, value: V): HashNode<K, V> {
        val node = HashNode(hash, key, value, null)
        if (linked) {
            val t = tail
            if (t == null) {
                head = node
            } else {
                node.before = t
                t.after = node
            }
            tail = node
        }
        return node
    }

    private fun resize() {
        val oldTab = table
        val oldCap = refArrayLength(oldTab)
        val newCap: Int
        if (oldCap > 0) {
            newCap = oldCap shl 1
        } else if (threshold > 0) {
            newCap = threshold
        } else {
            newCap = 16
        }
        threshold = (newCap * 3) / 4
        val newTab = refArrayNew(newCap)
        var j = 0
        while (j < oldCap) {
            @Suppress("UNCHECKED_CAST")
            var e = refArrayGet(oldTab, j) as HashNode<K, V>?
            if (e != null) {
                var loHead: HashNode<K, V>? = null
                var loTail: HashNode<K, V>? = null
                var hiHead: HashNode<K, V>? = null
                var hiTail: HashNode<K, V>? = null
                while (e != null) {
                    val next = e.next
                    e.next = null
                    if ((e.hash and oldCap) == 0) {
                        if (loTail == null) loHead = e else loTail.next = e
                        loTail = e
                    } else {
                        if (hiTail == null) hiHead = e else hiTail.next = e
                        hiTail = e
                    }
                    e = next
                }
                refArraySet(newTab, j, loHead)
                refArraySet(newTab, j + oldCap, hiHead)
            }
            j++
        }
        table = newTab
    }

    override fun remove(key: K): V? {
        val node = removeNode(key) ?: return null
        return node.value
    }

    internal fun removeNode(key: Any?): HashNode<K, V>? {
        val n = refArrayLength(table)
        if (n == 0) return null
        val hash = spread(key)
        val index = (n - 1) and hash
        var prev: HashNode<K, V>? = null
        var e = slot(index)
        while (e != null) {
            if (e.hash == hash && e.key == key) {
                if (prev == null) refArraySet(table, index, e.next) else prev.next = e.next
                e.next = null
                if (linked) unlink(e)
                count--
                return e
            }
            prev = e
            e = e.next
        }
        return null
    }

    private fun unlink(e: HashNode<K, V>) {
        val b = e.before
        val a = e.after
        if (b == null) head = a else b.after = a
        if (a == null) tail = b else a.before = b
        e.before = null
        e.after = null
    }

    override fun clear() {
        val n = refArrayLength(table)
        var i = 0
        while (i < n) {
            refArraySet(table, i, null)
            i++
        }
        count = 0
        head = null
        tail = null
    }

    override fun putAll(from: Map<out K, V>) {
        val it = from.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            put(e.key, e.value)
        }
    }

    internal fun nodeIterator(): MutableIterator<HashNode<K, V>> = NodeIterator(this)

    private class NodeIterator<K, V>(private val map: HashMap<K, V>) : MutableIterator<HashNode<K, V>> {
        private var nextNode: HashNode<K, V>? = null
        private var current: HashNode<K, V>? = null
        private var index: Int = 0

        init {
            if (map.linked) {
                nextNode = map.head
            } else {
                advanceFrom(null)
            }
        }

        private fun advanceFrom(node: HashNode<K, V>?) {
            var nx = node?.next
            val n = refArrayLength(map.table)
            while (nx == null && index < n) {
                @Suppress("UNCHECKED_CAST")
                nx = refArrayGet(map.table, index++) as HashNode<K, V>?
            }
            nextNode = nx
        }

        override fun hasNext(): Boolean = nextNode != null

        override fun next(): HashNode<K, V> {
            val e = nextNode ?: throw NoSuchElementException()
            current = e
            if (map.linked) nextNode = e.after else advanceFrom(e)
            return e
        }

        override fun remove() {
            val c = current ?: throw IllegalStateException()
            current = null
            map.removeNode(c.key)
        }
    }

    private var entriesView: MutableSet<MutableMap.MutableEntry<K, V>>? = null
    private var keysView: MutableSet<K>? = null
    private var valuesView: MutableCollection<V>? = null

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            var v = entriesView
            if (v == null) {
                v = EntrySet(this)
                entriesView = v
            }
            return v
        }

    override val keys: MutableSet<K>
        get() {
            var v = keysView
            if (v == null) {
                v = KeySet(this)
                keysView = v
            }
            return v
        }

    override val values: MutableCollection<V>
        get() {
            var v = valuesView
            if (v == null) {
                v = ValueCollection(this)
                valuesView = v
            }
            return v
        }

    private class EntrySet<K, V>(private val map: HashMap<K, V>) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int
            get() = map.size

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> =
            object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                private val it = map.nodeIterator()
                override fun hasNext(): Boolean = it.hasNext()
                override fun next(): MutableMap.MutableEntry<K, V> = it.next()
                override fun remove() = it.remove()
            }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

        override fun clear() = map.clear()

        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val n = map.findNode(element.key) ?: return false
            return n.value == element.value
        }
    }

    private class KeySet<K, V>(private val map: HashMap<K, V>) : AbstractMutableSet<K>() {
        override val size: Int
            get() = map.size

        override fun iterator(): MutableIterator<K> = object : MutableIterator<K> {
            private val it = map.nodeIterator()
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): K = it.next().key
            override fun remove() = it.remove()
        }

        override fun add(element: K): Boolean = throw UnsupportedOperationException()

        override fun clear() = map.clear()

        override fun contains(element: K): Boolean = map.containsKey(element)

        override fun remove(element: K): Boolean = map.removeNode(element) != null
    }

    private class ValueCollection<K, V>(private val map: HashMap<K, V>) : AbstractMutableCollection<V>() {
        override val size: Int
            get() = map.size

        override fun iterator(): MutableIterator<V> = object : MutableIterator<V> {
            private val it = map.nodeIterator()
            override fun hasNext(): Boolean = it.hasNext()
            override fun next(): V = it.next().value
            override fun remove() = it.remove()
        }

        override fun add(element: V): Boolean = throw UnsupportedOperationException()

        override fun clear() = map.clear()
    }
}

public open class LinkedHashMap<K, V> : HashMap<K, V> {
    public constructor() : super(true, 16)

    public constructor(initialCapacity: Int) : super(true, initialCapacity)

    public constructor(original: Map<out K, V>) : super(true, 16) {
        putAll(original)
    }
}
