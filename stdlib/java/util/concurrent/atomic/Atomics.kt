/*
 * java.util.concurrent.atomic on the atomics of the runtime (mini.concurrent / runtime/src/sys.c).
 */
package java.util.concurrent.atomic

import java.util.function.IntBinaryOperator
import java.util.function.IntUnaryOperator
import java.util.function.LongBinaryOperator
import java.util.function.LongUnaryOperator
import java.util.function.UnaryOperator
import mini.internal.*

public class AtomicInteger(initialValue: Int) {
    public constructor() : this(0)

    private val cell = IntArray(1)

    init {
        atomicIntSet(cell, initialValue)
    }

    public fun get(): Int = atomicIntGet(cell)
    public fun set(newValue: Int) { atomicIntSet(cell, newValue) }
    public fun lazySet(newValue: Int) { atomicIntSet(cell, newValue) }
    public fun getAndSet(newValue: Int): Int = atomicIntExchange(cell, newValue)
    public fun compareAndSet(expect: Int, update: Int): Boolean = atomicIntCas(cell, expect, update)
    public fun weakCompareAndSet(expect: Int, update: Int): Boolean = atomicIntCas(cell, expect, update)
    public fun getAndAdd(delta: Int): Int = atomicIntAdd(cell, delta) - delta
    public fun addAndGet(delta: Int): Int = atomicIntAdd(cell, delta)
    public fun incrementAndGet(): Int = atomicIntAdd(cell, 1)
    public fun decrementAndGet(): Int = atomicIntAdd(cell, -1)
    public fun getAndIncrement(): Int = atomicIntAdd(cell, 1) - 1
    public fun getAndDecrement(): Int = atomicIntAdd(cell, -1) + 1

    public fun updateAndGet(f: IntUnaryOperator): Int {
        while (true) {
            val prev = get()
            val next = f.applyAsInt(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    public fun getAndUpdate(f: IntUnaryOperator): Int {
        while (true) {
            val prev = get()
            if (compareAndSet(prev, f.applyAsInt(prev))) return prev
        }
    }

    public fun accumulateAndGet(x: Int, f: IntBinaryOperator): Int {
        while (true) {
            val prev = get()
            val next = f.applyAsInt(prev, x)
            if (compareAndSet(prev, next)) return next
        }
    }

    public fun getAndAccumulate(x: Int, f: IntBinaryOperator): Int {
        while (true) {
            val prev = get()
            if (compareAndSet(prev, f.applyAsInt(prev, x))) return prev
        }
    }

    public fun toInt(): Int = get()
    public fun toLong(): Long = get().toLong()
    override fun toString(): String = get().toString()
}

public class AtomicLong(initialValue: Long) {
    public constructor() : this(0L)

    private val cell = LongArray(1)

    init {
        atomicLongSet(cell, initialValue)
    }

    public fun get(): Long = atomicLongGet(cell)
    public fun set(newValue: Long) { atomicLongSet(cell, newValue) }
    public fun lazySet(newValue: Long) { atomicLongSet(cell, newValue) }
    public fun getAndSet(newValue: Long): Long = atomicLongExchange(cell, newValue)
    public fun compareAndSet(expect: Long, update: Long): Boolean = atomicLongCas(cell, expect, update)
    public fun weakCompareAndSet(expect: Long, update: Long): Boolean = atomicLongCas(cell, expect, update)
    public fun getAndAdd(delta: Long): Long = atomicLongAdd(cell, delta) - delta
    public fun addAndGet(delta: Long): Long = atomicLongAdd(cell, delta)
    public fun incrementAndGet(): Long = atomicLongAdd(cell, 1L)
    public fun decrementAndGet(): Long = atomicLongAdd(cell, -1L)
    public fun getAndIncrement(): Long = atomicLongAdd(cell, 1L) - 1L
    public fun getAndDecrement(): Long = atomicLongAdd(cell, -1L) + 1L

    public fun updateAndGet(f: LongUnaryOperator): Long {
        while (true) {
            val prev = get()
            val next = f.applyAsLong(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    public fun getAndUpdate(f: LongUnaryOperator): Long {
        while (true) {
            val prev = get()
            if (compareAndSet(prev, f.applyAsLong(prev))) return prev
        }
    }

    public fun accumulateAndGet(x: Long, f: LongBinaryOperator): Long {
        while (true) {
            val prev = get()
            val next = f.applyAsLong(prev, x)
            if (compareAndSet(prev, next)) return next
        }
    }

    public fun toInt(): Int = get().toInt()
    public fun toLong(): Long = get()
    override fun toString(): String = get().toString()
}

public class AtomicBoolean(initialValue: Boolean) {
    public constructor() : this(false)

    private val cell = IntArray(1)

    init {
        atomicIntSet(cell, if (initialValue) 1 else 0)
    }

    public fun get(): Boolean = atomicIntGet(cell) != 0
    public fun set(newValue: Boolean) { atomicIntSet(cell, if (newValue) 1 else 0) }
    public fun lazySet(newValue: Boolean) { set(newValue) }
    public fun getAndSet(newValue: Boolean): Boolean = atomicIntExchange(cell, if (newValue) 1 else 0) != 0
    public fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
        atomicIntCas(cell, if (expect) 1 else 0, if (update) 1 else 0)
    override fun toString(): String = get().toString()
}

public class AtomicReference<V>(initialValue: V?) {
    public constructor() : this(null)

    private val cell: Array<Any?> = arrayOfNulls<Any?>(1)

    init {
        atomicRefSet(cell, initialValue)
    }

    @Suppress("UNCHECKED_CAST")
    public fun get(): V? = atomicRefGet(cell) as V?
    public fun set(newValue: V?) { atomicRefSet(cell, newValue) }
    public fun lazySet(newValue: V?) { atomicRefSet(cell, newValue) }
    @Suppress("UNCHECKED_CAST")
    public fun getAndSet(newValue: V?): V? = atomicRefExchange(cell, newValue) as V?
    public fun compareAndSet(expect: V?, update: V?): Boolean = atomicRefCas(cell, expect, update)

    public fun updateAndGet(f: UnaryOperator<V?>): V? {
        while (true) {
            val prev = get()
            val next = f.apply(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    public fun getAndUpdate(f: UnaryOperator<V?>): V? {
        while (true) {
            val prev = get()
            if (compareAndSet(prev, f.apply(prev))) return prev
        }
    }

    override fun toString(): String = get().toString()
}
