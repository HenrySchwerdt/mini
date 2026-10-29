/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/ArrayDeque.kt (circular buffer on a native ref array, like ArrayList); the bulk operations
 * (insert/remove in the middle) shift element by element instead of using `copyInto`.
 */
package kotlin.collections

import mini.internal.refArrayGet
import mini.internal.refArrayLength
import mini.internal.refArrayNew
import mini.internal.refArraySet

public class ArrayDeque<E> : AbstractMutableList<E> {
    private var head: Int = 0
    private var elementData: Any

    override var size: Int = 0
        private set

    public constructor(initialCapacity: Int) {
        if (initialCapacity < 0) throw IllegalArgumentException("Illegal Capacity: $initialCapacity")
        elementData = refArrayNew(initialCapacity)
    }

    public constructor() {
        elementData = refArrayNew(0)
    }

    public constructor(elements: Collection<E>) {
        elementData = refArrayNew(elements.size)
        for (e in elements) {
            refArraySet(elementData, size, e)
            size += 1
        }
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity < 0) throw IllegalStateException("Deque is too big.")
        if (minCapacity <= refArrayLength(elementData)) return
        var newCapacity = if (refArrayLength(elementData) == 0) 10 else refArrayLength(elementData) + (refArrayLength(elementData) shr 1) + 1
        if (newCapacity < minCapacity) newCapacity = minCapacity
        val newElements = refArrayNew(newCapacity)
        var i = 0
        while (i < size) {
            refArraySet(newElements, i, refArrayGet(elementData, positiveMod(head + i)))
            i++
        }
        head = 0
        elementData = newElements
    }

    @Suppress("UNCHECKED_CAST")
    private fun internalGet(internalIndex: Int): E = refArrayGet(elementData, internalIndex) as E

    private fun positiveMod(index: Int): Int = if (index >= refArrayLength(elementData)) index - refArrayLength(elementData) else index

    private fun internalIndex(index: Int): Int = positiveMod(head + index)

    private fun incremented(index: Int): Int = if (index == refArrayLength(elementData) - 1) 0 else index + 1

    private fun decremented(index: Int): Int = if (index == 0) refArrayLength(elementData) - 1 else index - 1

    override fun isEmpty(): Boolean = size == 0

    public fun first(): E = if (isEmpty()) throw NoSuchElementException("ArrayDeque is empty.") else internalGet(head)

    public fun firstOrNull(): E? = if (isEmpty()) null else internalGet(head)

    public fun last(): E = if (isEmpty()) throw NoSuchElementException("ArrayDeque is empty.") else internalGet(internalIndex(size - 1))

    public fun lastOrNull(): E? = if (isEmpty()) null else internalGet(internalIndex(size - 1))

    public fun addFirst(element: E) {
        ensureCapacity(size + 1)
        head = decremented(head)
        refArraySet(elementData, head, element)
        size += 1
    }

    public fun addLast(element: E) {
        ensureCapacity(size + 1)
        refArraySet(elementData, internalIndex(size), element)
        size += 1
    }

    public fun removeFirst(): E {
        if (isEmpty()) throw NoSuchElementException("ArrayDeque is empty.")
        val element = internalGet(head)
        refArraySet(elementData, head, null)
        head = incremented(head)
        size -= 1
        return element
    }

    public fun removeFirstOrNull(): E? = if (isEmpty()) null else removeFirst()

    public fun removeLast(): E {
        if (isEmpty()) throw NoSuchElementException("ArrayDeque is empty.")
        val internalLastIndex = internalIndex(size - 1)
        val element = internalGet(internalLastIndex)
        refArraySet(elementData, internalLastIndex, null)
        size -= 1
        return element
    }

    public fun removeLastOrNull(): E? = if (isEmpty()) null else removeLast()

    public override fun add(element: E): Boolean {
        addLast(element)
        return true
    }

    public override fun add(index: Int, element: E) {
        if (index < 0 || index > size) throw IndexOutOfBoundsException("index: $index, size: $size")
        if (index == size) {
            addLast(element)
            return
        } else if (index == 0) {
            addFirst(element)
            return
        }
        ensureCapacity(size + 1)
        // open a gap at `index` by shifting the elements after it one step towards the tail
        var i = size
        while (i > index) {
            refArraySet(elementData, internalIndex(i), refArrayGet(elementData, internalIndex(i - 1)))
            i--
        }
        refArraySet(elementData, internalIndex(index), element)
        size += 1
    }

    public override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        ensureCapacity(size + elements.size)
        for (e in elements) addLast(e)
        return true
    }

    public override fun get(index: Int): E {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
        return internalGet(internalIndex(index))
    }

    public override fun set(index: Int, element: E): E {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
        val internalIndex = internalIndex(index)
        val oldElement = internalGet(internalIndex)
        refArraySet(elementData, internalIndex, element)
        return oldElement
    }

    public override fun contains(element: E): Boolean = indexOf(element) != -1

    public override fun indexOf(element: E): Int {
        var i = 0
        while (i < size) {
            if (element == refArrayGet(elementData, internalIndex(i))) return i
            i++
        }
        return -1
    }

    public override fun lastIndexOf(element: E): Int {
        var i = size - 1
        while (i >= 0) {
            if (element == refArrayGet(elementData, internalIndex(i))) return i
            i--
        }
        return -1
    }

    public override fun remove(element: E): Boolean {
        val index = indexOf(element)
        if (index == -1) return false
        removeAt(index)
        return true
    }

    public override fun removeAt(index: Int): E {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
        if (index == size - 1) return removeLast()
        if (index == 0) return removeFirst()
        val element = internalGet(internalIndex(index))
        var i = index
        while (i < size - 1) {
            refArraySet(elementData, internalIndex(i), refArrayGet(elementData, internalIndex(i + 1)))
            i++
        }
        refArraySet(elementData, internalIndex(size - 1), null)
        size -= 1
        return element
    }

    public override fun clear() {
        var i = 0
        while (i < size) {
            refArraySet(elementData, internalIndex(i), null)
            i++
        }
        head = 0
        size = 0
    }
}
