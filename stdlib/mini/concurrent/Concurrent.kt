/*
 * Locks, condition variables and atomics over the thread layer of the runtime (docs/coroutines.md). The first started thread
 * switches every reference count of the process to atomic (runtime/src/object.c), so objects can be handed to threads freely;
 * data that is changed by several threads still needs a Lock or an atomic.
 */
package mini.concurrent

import mini.internal.*

/** A mutual-exclusion lock (not reentrant). */
public class Lock {
    internal val storage = LongArray(16)

    init {
        sysLockInit(storage)
    }

    public fun lock() {
        sysLock(storage)
    }

    public fun tryLock(): Boolean = sysTryLock(storage)

    public fun unlock() {
        sysUnlock(storage)
    }

    public fun newCondition(): Condition = Condition(this)
}

public inline fun <T> Lock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** A condition variable bound to a [Lock]; all calls need the lock to be held. */
public class Condition internal constructor(private val owner: Lock) {
    private val storage = LongArray(16)

    init {
        sysCondInit(storage)
    }

    /**
     * Waits for a signal (spurious returns are possible: loop on the predicate). When the thread is interrupted it throws
     * InterruptedException, and the [Lock] is *released* by then (so the plain `lock(); ...; await(); ...; unlock()` code of the library needs
     * no `finally`); the JVM-style conditions of java.util.concurrent.locks keep the lock held instead.
     */
    public fun await() {
        try {
            java.lang.interruptibleWait(storage, owner.storage, -1L)
        } catch (e: InterruptedException) {
            owner.unlock()
            throw e
        }
    }

    /** Waits at most [nanos] nanoseconds; true when the time ran out. Interrupts as [await] does. */
    public fun awaitNanos(nanos: Long): Boolean {
        try {
            return java.lang.interruptibleWait(storage, owner.storage, nanos)
        } catch (e: InterruptedException) {
            owner.unlock()
            throw e
        }
    }

    public fun signal() {
        sysCondSignal(storage)
    }

    public fun signalAll() {
        sysCondBroadcast(storage)
    }
}

public class AtomicInt(initial: Int = 0) {
    private val cell = IntArray(1)

    init {
        cell[0] = initial
    }

    public fun get(): Int = atomicIntGet(cell)
    public fun set(value: Int) {
        atomicIntSet(cell, value)
    }
    public fun addAndGet(delta: Int): Int = atomicIntAdd(cell, delta)
    public fun getAndAdd(delta: Int): Int = atomicIntAdd(cell, delta) - delta
    public fun incrementAndGet(): Int = atomicIntAdd(cell, 1)
    public fun decrementAndGet(): Int = atomicIntAdd(cell, -1)
    public fun getAndIncrement(): Int = atomicIntAdd(cell, 1) - 1
    public fun getAndSet(value: Int): Int = atomicIntExchange(cell, value)
    public fun compareAndSet(expected: Int, value: Int): Boolean = atomicIntCas(cell, expected, value)
    public override fun toString(): String = get().toString()
}

public class AtomicLong(initial: Long = 0L) {
    private val cell = LongArray(1)

    init {
        cell[0] = initial
    }

    public fun get(): Long = atomicLongGet(cell)
    public fun set(value: Long) {
        atomicLongSet(cell, value)
    }
    public fun addAndGet(delta: Long): Long = atomicLongAdd(cell, delta)
    public fun getAndAdd(delta: Long): Long = atomicLongAdd(cell, delta) - delta
    public fun incrementAndGet(): Long = atomicLongAdd(cell, 1L)
    public fun decrementAndGet(): Long = atomicLongAdd(cell, -1L)
    public fun getAndIncrement(): Long = atomicLongAdd(cell, 1L) - 1L
    public fun getAndSet(value: Long): Long = atomicLongExchange(cell, value)
    public fun compareAndSet(expected: Long, value: Long): Boolean = atomicLongCas(cell, expected, value)
    public override fun toString(): String = get().toString()
}

/** A reference cell; [compareAndSet] compares by identity. */
public class AtomicReference<T>(initial: T) {
    private val cell = arrayOfNulls<Any?>(1)

    init {
        cell[0] = initial
    }

    @Suppress("UNCHECKED_CAST")
    public fun get(): T = atomicRefGet(cell) as T
    public fun set(value: T) {
        atomicRefSet(cell, value)
    }
    @Suppress("UNCHECKED_CAST")
    public fun getAndSet(value: T): T = atomicRefExchange(cell, value) as T
    public fun compareAndSet(expected: T, value: T): Boolean = atomicRefCas(cell, expected, value)
}

/** Monotonic clock in nanoseconds (for measuring and for timers). */
public fun monotonicNanos(): Long = sysMonotonicNanos()

/** Wall clock in milliseconds since 1970. */
public fun currentTimeMillis(): Long = sysWallMillis()

/** Blocks the calling thread. */
public fun sleepMillis(ms: Long) {
    sysSleepNanos(ms * 1000000L)
}

public fun availableProcessors(): Int = sysCpuCount().toInt()
