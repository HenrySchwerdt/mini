/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Shape of kotlin-stdlib jvm/concurrent/Thread.kt, on the native threads of java.lang.Thread (runtime/src/sys.c, no libuv);
 * the memory checker treats `thread { }` as a thread boundary.
 */
package kotlin.concurrent

public fun thread(
    start: Boolean = true,
    isDaemon: Boolean = false,
    name: String? = null,
    priority: Int = -1,
    block: () -> Unit,
): java.lang.Thread {
    val t = java.lang.Thread(block, name)
    if (isDaemon) t.isDaemon = true
    if (priority > 0) t.priority = priority
    if (start) t.start()
    return t
}

/** Runs [action] with the lock held. */
public inline fun <T> java.util.concurrent.locks.Lock.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}

public inline fun <T> java.util.concurrent.locks.ReentrantReadWriteLock.read(action: () -> T): T {
    val rl = readLock()
    rl.lock()
    try {
        return action()
    } finally {
        rl.unlock()
    }
}

public inline fun <T> java.util.concurrent.locks.ReentrantReadWriteLock.write(action: () -> T): T {
    val wl = writeLock()
    wl.lock()
    try {
        return action()
    } finally {
        wl.unlock()
    }
}

public inline fun <T : Any> java.lang.ThreadLocal<T>.getOrSet(default: () -> T): T {
    val v = get()
    if (v != null) return v
    val d = default()
    set(d)
    return d
}
