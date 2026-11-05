/*
 * `java.lang.Thread` as far as portable sources use it (default-imported on the JVM), on native threads
 * (runtime/src/sys.c: POSIX threads, Win32 threads). The first started thread makes all reference counts atomic.
 */
package java.lang

import mini.internal.*

public class Thread(private val block: () -> Unit) {
    private var handle = 0L
    private var started = false

    public fun start() {
        if (started) throw IllegalStateException("Thread already started")
        started = true
        handle = sysThreadStart(block)
        if (handle == 0L) throw IllegalStateException("threads are not supported on this platform")
    }

    public fun join() {
        if (!started) return
        val h = handle
        handle = 0L
        if (h != 0L) sysThreadJoin(h)
    }

    public companion object {
        public fun sleep(millis: Long) {
            sysSleepNanos(millis * 1000000L)
        }
    }
}
