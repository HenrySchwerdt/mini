/*
 * The atomic element operations of Kotlin/Native on IntArray, LongArray and Array<T> (`kotlin.concurrent`): `atomicGet`, `atomicSet`,
 * `compareAndExchange`, `compareAndSet`, `getAndSet`, `getAndAdd`. All of them run under one lock, so they are atomic with respect to
 * each other (not to plain element accesses). References are compared by identity.
 */
package kotlin.concurrent

import mini.concurrent.Lock
import mini.concurrent.withLock

private val arrayAtomicsLock = Lock()

public fun IntArray.atomicGet(index: Int): Int = arrayAtomicsLock.withLock { this[index] }

public fun IntArray.atomicSet(index: Int, newValue: Int) {
    arrayAtomicsLock.withLock { this[index] = newValue }
}

public fun IntArray.compareAndExchange(index: Int, expectedValue: Int, newValue: Int): Int = arrayAtomicsLock.withLock {
    val cur = this[index]
    if (cur == expectedValue) this[index] = newValue
    cur
}

public fun IntArray.compareAndSet(index: Int, expectedValue: Int, newValue: Int): Boolean = arrayAtomicsLock.withLock {
    val ok = this[index] == expectedValue
    if (ok) this[index] = newValue
    ok
}

public fun IntArray.getAndSet(index: Int, newValue: Int): Int = arrayAtomicsLock.withLock {
    val cur = this[index]
    this[index] = newValue
    cur
}

public fun IntArray.getAndAdd(index: Int, delta: Int): Int = arrayAtomicsLock.withLock {
    val cur = this[index]
    this[index] = cur + delta
    cur
}

public fun LongArray.atomicGet(index: Int): Long = arrayAtomicsLock.withLock { this[index] }

public fun LongArray.atomicSet(index: Int, newValue: Long) {
    arrayAtomicsLock.withLock { this[index] = newValue }
}

public fun LongArray.compareAndExchange(index: Int, expectedValue: Long, newValue: Long): Long = arrayAtomicsLock.withLock {
    val cur = this[index]
    if (cur == expectedValue) this[index] = newValue
    cur
}

public fun LongArray.compareAndSet(index: Int, expectedValue: Long, newValue: Long): Boolean = arrayAtomicsLock.withLock {
    val ok = this[index] == expectedValue
    if (ok) this[index] = newValue
    ok
}

public fun LongArray.getAndSet(index: Int, newValue: Long): Long = arrayAtomicsLock.withLock {
    val cur = this[index]
    this[index] = newValue
    cur
}

public fun LongArray.getAndAdd(index: Int, delta: Long): Long = arrayAtomicsLock.withLock {
    val cur = this[index]
    this[index] = cur + delta
    cur
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
