/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/ArrayList.kt (Kotlin/Native/JS); storage is a native ref array.
 */
package kotlin.collections

import kotlin.internal.ReturnsBorrowed
import kotlin.internal.TakesOwnership
import mini.internal.refArrayGet
import mini.internal.refArrayGetBorrowed
import mini.internal.refArrayLength
import mini.internal.refArrayMove
import mini.internal.refArrayNew
import mini.internal.refArraySet
import mini.internal.refArraySetOwned

public open class ArrayList<E> : AbstractMutableList<E>, RandomAccess {
    internal var array: Any
    internal var length: Int = 0
    /** structural modifications so far: an iterator that sees another count than it started with fails (ConcurrentModificationException) */
    internal var modCount: Int = 0

    public constructor() {
        array = refArrayNew(4)
    }

    public constructor(initialCapacity: Int) {
        if (initialCapacity < 0) mini.internal.throwIllegalArgument("Illegal Capacity: $initialCapacity")
        array = refArrayNew(if (initialCapacity < 4) 4 else initialCapacity)
    }

    public constructor(elements: Collection<E>) {
        array = refArrayNew(if (elements.size < 4) 4 else elements.size)
        addAll(elements)
    }

    override val size: Int
        get() = length

    override fun isEmpty(): Boolean = length == 0

    @Suppress("UNCHECKED_CAST")
    @ReturnsBorrowed
    override fun get(index: Int): E {
        checkElementIndex(index)
        return refArrayGetBorrowed(array, index) as E
    }

    @Suppress("UNCHECKED_CAST")
    @mini.memory.Mutates override fun set(index: Int, @TakesOwnership element: E): E {
        checkElementIndex(index)
        val old = refArrayGet(array, index) as E
        refArraySetOwned(array, index, element)
        return old
    }

    // NoHeapEffects: appending only fills the first free (NULL) slot, and growing moves every element into the new storage, so
    // no reference that an element read (a borrowed alias of an element, of the list) depends on is ever dropped.
    @kotlin.internal.NoHeapEffects
    @mini.memory.Mutates override fun add(@TakesOwnership element: E): Boolean {
        // one read of the storage and of the length on the common path (room left); growing is the rare case
        val len = length
        var arr = array
        if (len >= refArrayLength(arr)) {
            ensureCapacity(len + 1)
            arr = array
        }
        refArraySetOwned(arr, len, element)
        length = len + 1
        modCount++
        return true
    }

    @mini.memory.Mutates override fun add(index: Int, @TakesOwnership element: E) {
        if (index < 0 || index > length) mini.internal.throwIndexOutOfBounds("index: $index, size: $length")
        ensureCapacity(length + 1)
        var i = length
        while (i > index) {
            refArraySet(array, i, refArrayGet(array, i - 1))
            i--
        }
        refArraySetOwned(array, index, element)
        length++
        modCount++
    }

    @mini.memory.Mutates override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        // The count is fixed up front: `list.addAll(list)` must append the elements that were there, not chase its own tail.
        var remaining = elements.size
        if (length > Int.MAX_VALUE - remaining) mini.internal.throwIllegalArgument("List too large")
        ensureCapacity(length + remaining)
        val it = elements.iterator()
        while (remaining > 0 && it.hasNext()) {
            refArraySet(array, length, it.next())
            length++
            remaining--
        }
        modCount++
        return true
    }

    @Suppress("UNCHECKED_CAST")
    @mini.memory.Mutates override fun removeAt(index: Int): E {
        checkElementIndex(index)
        val old = refArrayGet(array, index) as E
        var i = index
        while (i < length - 1) {
            refArraySet(array, i, refArrayGet(array, i + 1))
            i++
        }
        refArraySet(array, length - 1, null)
        length--
        modCount++
        return old
    }

    @mini.memory.Mutates override fun remove(element: E): Boolean {
        val i = indexOf(element)
        if (i < 0) return false
        removeAt(i)
        return true
    }

    @mini.memory.Mutates override fun clear() {
        var i = 0
        while (i < length) {
            refArraySet(array, i, null)
            i++
        }
        length = 0
        modCount++
    }

    override fun indexOf(element: E): Int {
        var i = 0
        while (i < length) {
            if (refArrayGet(array, i) == element) return i
            i++
        }
        return -1
    }

    override fun lastIndexOf(element: E): Int {
        var i = length - 1
        while (i >= 0) {
            if (refArrayGet(array, i) == element) return i
            i--
        }
        return -1
    }

    public fun trimToSize() {}

    @kotlin.internal.NoHeapEffects
    public fun ensureCapacity(minCapacity: Int) {
        val cap = refArrayLength(array)
        if (minCapacity <= cap) return
        var newCap = cap * 2
        if (newCap < minCapacity) newCap = minCapacity
        val newArray = refArrayNew(newCap)
        refArrayMove(newArray, array, length)
        array = newArray
    }

    private fun checkElementIndex(index: Int) {
        if (index < 0 || index >= length) mini.internal.throwIndexOutOfBounds("index: $index, size: $length")
    }

    // reads the storage directly: no list interface, no index checks beyond the one the loop needs
    override fun iterator(): MutableIterator<E> = ArrayListIterator(this)

    @kotlin.internal.NoHeapEffects
    override fun toString(): String = super.toString()
}

internal class ArrayListIterator<E>(private val list: ArrayList<E>) : MutableIterator<E> {
    private var index: Int = 0
    private var last: Int = -1
    private var expectedModCount: Int = list.modCount

    override fun hasNext(): Boolean = index < list.length

    @Suppress("UNCHECKED_CAST")
    @ReturnsBorrowed
    override fun next(): E {
        if (list.modCount != expectedModCount) throw ConcurrentModificationException()
        if (index >= list.length) mini.internal.throwNoSuchElement(null)
        last = index
        index++
        return refArrayGetBorrowed(list.array, last) as E
    }

    @mini.memory.Mutates override fun remove() {
        if (last < 0) mini.internal.throwIllegalState(null)
        if (list.modCount != expectedModCount) throw ConcurrentModificationException()
        list.removeAt(last)
        expectedModCount = list.modCount
        if (last < index) index--
        last = -1
    }
}
