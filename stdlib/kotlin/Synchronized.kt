/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.synchronized on native threads (see docs/threads.md).
 */
package kotlin

private object MonitorStripes {
    val locks = Array<java.util.concurrent.locks.ReentrantLock>(256) { java.util.concurrent.locks.ReentrantLock() }
    val conds = Array<java.util.concurrent.locks.Condition>(256) { locks[it].newCondition() }
}

/**
 * Runs [block] holding the monitor of [lock]. The monitors are 256 reentrant locks chosen by the identity of the object, so two
 * different objects can share one (a rare lock-order inversion between such objects can then deadlock). `@Synchronized` on a function is
 * the same around its body (the compiler rewrites it: `this` for a member, one global monitor for a top-level function).
 */
public inline fun <R> synchronized(lock: Any, block: () -> R): R {
    val m = monitorOf(lock)
    m.lock()
    try {
        return block()
    } finally {
        m.unlock()
    }
}

public fun monitorOf(o: Any): java.util.concurrent.locks.ReentrantLock = MonitorStripes.locks[mini.internal.identityHashCode(o) and 255]

private fun heldStripe(o: Any): Int {
    val i = mini.internal.identityHashCode(o) and 255
    if (!MonitorStripes.locks[i].isHeldByCurrentThread) throw IllegalMonitorStateException("current thread is not owner")
    return i
}

/**
 * `Object.wait()`: releases the monitor of the receiver (the caller must be inside `synchronized(this)`) and waits for `notify` /
 * `notifyAll`. Objects that share a monitor stripe also share its condition variable, so a wait can return spuriously: loop on the
 * condition, as on the JVM. Throws InterruptedException when the thread is interrupted.
 */
public fun Any.wait() {
    MonitorStripes.conds[heldStripe(this)].await()
}

/** `Object.wait(timeout)`: at most [timeoutMillis] milliseconds (0: without limit). */
public fun Any.wait(timeoutMillis: Long) {
    val c = MonitorStripes.conds[heldStripe(this)]
    if (timeoutMillis == 0L) c.await() else c.awaitNanos(timeoutMillis * 1000000L)
}

/** Wakes waiting threads (all that wait on the monitor stripe: `notify` and `notifyAll` are the same here). */
public fun Any.notify() {
    MonitorStripes.conds[heldStripe(this)].signalAll()
}

public fun Any.notifyAll() {
    MonitorStripes.conds[heldStripe(this)].signalAll()
}
