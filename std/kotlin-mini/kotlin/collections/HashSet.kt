/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reimplemented after java.util.HashSet / LinkedHashSet: a set is a map with a dummy value (same iteration order).
 */
package kotlin.collections

public open class HashSet<E> : AbstractMutableSet<E> {
    private val map: HashMap<E, Any?>

    public constructor() {
        map = HashMap()
    }

    public constructor(initialCapacity: Int) {
        map = HashMap(initialCapacity)
    }

    public constructor(elements: Collection<E>) {
        // java.util.HashSet: new HashMap<>(Math.max((int) (c.size()/.75f) + 1, 16))
        val n = (elements.size.toDouble() / 0.75).toInt() + 1
        map = HashMap(if (n > 16) n else 16)
        addAll(elements)
    }

    internal constructor(linkedMap: HashMap<E, Any?>) {
        map = linkedMap
    }

    override val size: Int
        get() = map.size

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun contains(element: E): Boolean = map.containsKey(element)

    override fun add(element: E): Boolean {
        if (map.containsKey(element)) return false
        map.put(element, true)
        return true
    }

    override fun remove(element: E): Boolean = map.removeNode(element) != null

    override fun clear() {
        map.clear()
    }

    override fun iterator(): MutableIterator<E> = map.keys.iterator()
}

public open class LinkedHashSet<E> : HashSet<E> {
    public constructor() : super(LinkedHashMap<E, Any?>())

    public constructor(initialCapacity: Int) : super(LinkedHashMap<E, Any?>(initialCapacity))

    public constructor(elements: Collection<E>) : super(LinkedHashMap<E, Any?>()) {
        addAll(elements)
    }
}
