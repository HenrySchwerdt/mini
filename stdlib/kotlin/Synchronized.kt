/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.synchronized on native threads (see docs/threads.md).
 */
package kotlin

private object MonitorStripes {
    val locks = Array<java.util.concurrent.locks.ReentrantLock>(256) { java.util.concurrent.locks.ReentrantLock() }
}

/**
 * Runs [block] holding the monitor of [lock]. The monitors are 256 reentrant locks chosen by the identity of the object, so two
 * different objects can share one (a rare lock-order inversion between such objects can then deadlock); `wait` / `notify` do not exist.
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
