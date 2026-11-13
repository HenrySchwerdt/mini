/*
 * The atomic element operations of Kotlin/Native on IntArray, LongArray and Array<T> (`kotlin.concurrent`): `atomicGet`, `atomicSet`,
 * `compareAndExchange`, `compareAndSet`, `getAndSet`, `getAndAdd`. All of them run under one lock, so they are atomic with respect to
 * each other (not to plain element accesses). References are compared by identity.
 */
package kotlin.concurrent

import mini.concurrent.Lock
import mini.internal.*
import mini.concurrent.withLock

private val arrayAtomicsLock = Lock()

private fun checkAtomicIndex(index: Int, size: Int) {
    if (index < 0 || index >= size) throw IndexOutOfBoundsException("index: $index, size: $size")
}

public fun IntArray.atomicGet(index: Int): Int {
    checkAtomicIndex(index, size)
    return atomicIntGetAt(this, index)
}

public fun IntArray.atomicSet(index: Int, newValue: Int) {
    checkAtomicIndex(index, size)
    atomicIntSetAt(this, index, newValue)
}

public fun IntArray.compareAndExchange(index: Int, expectedValue: Int, newValue: Int): Int {
    checkAtomicIndex(index, size)
    while (true) {
        val cur = atomicIntGetAt(this, index)
        if (cur != expectedValue) return cur
        if (atomicIntCasAt(this, index, expectedValue, newValue)) return expectedValue
    }
}

public fun IntArray.compareAndSet(index: Int, expectedValue: Int, newValue: Int): Boolean {
    checkAtomicIndex(index, size)
    return atomicIntCasAt(this, index, expectedValue, newValue)
}

public fun IntArray.getAndSet(index: Int, newValue: Int): Int {
    checkAtomicIndex(index, size)
    return atomicIntExchangeAt(this, index, newValue)
}

public fun IntArray.getAndAdd(index: Int, delta: Int): Int {
    checkAtomicIndex(index, size)
    return atomicIntAddAt(this, index, delta) - delta
}

public fun LongArray.atomicGet(index: Int): Long {
    checkAtomicIndex(index, size)
    return atomicLongGetAt(this, index)
}

public fun LongArray.atomicSet(index: Int, newValue: Long) {
    checkAtomicIndex(index, size)
    atomicLongSetAt(this, index, newValue)
}

public fun LongArray.compareAndExchange(index: Int, expectedValue: Long, newValue: Long): Long {
    checkAtomicIndex(index, size)
    while (true) {
        val cur = atomicLongGetAt(this, index)
        if (cur != expectedValue) return cur
        if (atomicLongCasAt(this, index, expectedValue, newValue)) return expectedValue
    }
}

public fun LongArray.compareAndSet(index: Int, expectedValue: Long, newValue: Long): Boolean {
    checkAtomicIndex(index, size)
    return atomicLongCasAt(this, index, expectedValue, newValue)
}

public fun LongArray.getAndSet(index: Int, newValue: Long): Long {
    checkAtomicIndex(index, size)
    return atomicLongExchangeAt(this, index, newValue)
}

public fun LongArray.getAndAdd(index: Int, delta: Long): Long {
    checkAtomicIndex(index, size)
    return atomicLongAddAt(this, index, delta) - delta
}

public fun <T> Array<T>.atomicGet(index: Int): T = arrayAtomicsLock.withLock { this[index] }

public fun <T> Array<T>.atomicSet(index: Int, newValue: T) {
    arrayAtomicsLock.withLock { this[index] = newValue }
}

public fun <T> Array<T>.compareAndExchange(index: Int, expectedValue: T, newValue: T): T = arrayAtomicsLock.withLock {
    val cur = this[index]
    if (cur === expectedValue) this[index] = newValue
    cur
}

public fun <T> Array<T>.compareAndSet(index: Int, expectedValue: T, newValue: T): Boolean = arrayAtomicsLock.withLock {
    val ok = this[index] === expectedValue
    if (ok) this[index] = newValue
    ok
}

public fun <T> Array<T>.getAndSet(index: Int, newValue: T): T = arrayAtomicsLock.withLock {
    val cur = this[index]
    this[index] = newValue
    cur
}
