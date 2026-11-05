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
    override fun set(index: Int, @TakesOwnership element: E): E {
        checkElementIndex(index)
        val old = refArrayGet(array, index) as E
        refArraySetOwned(array, index, element)
        return old
    }

    override fun add(@TakesOwnership element: E): Boolean {
        ensureCapacity(length + 1)
        refArraySetOwned(array, length, element)
        length++
        return true
    }

    override fun add(index: Int, @TakesOwnership element: E) {
        if (index < 0 || index > length) mini.internal.throwIndexOutOfBounds("index: $index, size: $length")
        ensureCapacity(length + 1)
        var i = length
        while (i > index) {
            refArraySet(array, i, refArrayGet(array, i - 1))
            i--
        }
        refArraySetOwned(array, index, element)
        length++
    }

    override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        ensureCapacity(length + elements.size)
        val it = elements.iterator()
        while (it.hasNext()) {
            refArraySet(array, length, it.next())
            length++
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override fun removeAt(index: Int): E {
        checkElementIndex(index)
        val old = refArrayGet(array, index) as E
        var i = index
        while (i < length - 1) {
            refArraySet(array, i, refArrayGet(array, i + 1))
            i++
        }
        refArraySet(array, length - 1, null)
        length--
        return old
    }

    override fun clear() {
        var i = 0
        while (i < length) {
            refArraySet(array, i, null)
            i++
        }
        length = 0
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

    override fun hasNext(): Boolean = index < list.length

    @Suppress("UNCHECKED_CAST")
    @ReturnsBorrowed
    override fun next(): E {
        if (index >= list.length) mini.internal.throwNoSuchElement(null)
        last = index
        index++
        return refArrayGetBorrowed(list.array, last) as E
    }

    override fun remove() {
        if (last < 0) mini.internal.throwIllegalState(null)
        list.removeAt(last)
        if (last < index) index--
        last = -1
    }
}
