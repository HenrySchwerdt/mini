/*
 * The Kotlin/Native atomic classes of package kotlin.concurrent (`AtomicInt(0)`, `value`, `incrementAndGet()`), over the cells of
 * mini.concurrent. (The common atomics of Kotlin 2.1 are in kotlin.concurrent.atomics.)
 */
package kotlin.concurrent

import mini.concurrent.AtomicInt as MiniAtomicInt
import mini.concurrent.AtomicLong as MiniAtomicLong
import mini.concurrent.AtomicReference as MiniAtomicReference

public class AtomicInt(value: Int) {
    private val cell = MiniAtomicInt(value)

    public var value: Int
        get() = cell.get()
        set(v) {
            cell.set(v)
        }

    public fun addAndGet(delta: Int): Int = cell.addAndGet(delta)
    public fun compareAndSet(expected: Int, new: Int): Boolean = cell.compareAndSet(expected, new)
    public fun compareAndSwap(expected: Int, new: Int): Int {
        while (true) {
            val cur = cell.get()
            if (cur != expected) return cur
            if (cell.compareAndSet(expected, new)) return expected
        }
    }
    public fun getAndAdd(delta: Int): Int = cell.getAndAdd(delta)
    public fun incrementAndGet(): Int = cell.incrementAndGet()
    public fun decrementAndGet(): Int = cell.decrementAndGet()
    public fun getAndIncrement(): Int = cell.getAndIncrement()
    public fun getAndDecrement(): Int = cell.getAndAdd(-1)
    public override fun toString(): String = cell.get().toString()
}

public class AtomicLong(value: Long) {
    private val cell = MiniAtomicLong(value)

    public var value: Long
        get() = cell.get()
        set(v) {
            cell.set(v)
        }

    public fun addAndGet(delta: Long): Long = cell.addAndGet(delta)
    public fun compareAndSet(expected: Long, new: Long): Boolean = cell.compareAndSet(expected, new)
    public fun compareAndSwap(expected: Long, new: Long): Long {
        while (true) {
            val cur = cell.get()
            if (cur != expected) return cur
            if (cell.compareAndSet(expected, new)) return expected
        }
    }
    public fun getAndAdd(delta: Long): Long = cell.getAndAdd(delta)
    public fun incrementAndGet(): Long = cell.incrementAndGet()
    public fun decrementAndGet(): Long = cell.decrementAndGet()
    public override fun toString(): String = cell.get().toString()
}

public class AtomicReference<T>(value: T) {
    private val cell = MiniAtomicReference(value)

    public var value: T
        get() = cell.get()
        set(v) {
            cell.set(v)
        }

    public fun compareAndSet(expected: T, new: T): Boolean = cell.compareAndSet(expected, new)
    public fun getAndSet(new: T): T = cell.getAndSet(new)
    public override fun toString(): String = "AtomicReference(" + cell.get().toString() + ")"
}
