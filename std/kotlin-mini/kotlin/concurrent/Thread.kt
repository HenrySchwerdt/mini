/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Shape of kotlin-stdlib jvm/concurrent/Thread.kt; backed by the java.lang.Thread placeholder until
 * libuv threads land (the memory checker treats `thread { }` as a thread boundary).
 */
package kotlin.concurrent

public fun thread(
    start: Boolean = true,
    isDaemon: Boolean = false,
    name: String? = null,
    priority: Int = -1,
    block: () -> Unit,
): java.lang.Thread {
    val t = java.lang.Thread(block)
    if (start) t.start()
    return t
}
