/*
 * java.lang.Thread on the native threads of the runtime (runtime/src/sys.c: pthreads / Win32 threads, no libuv), Runnable,
 * ThreadLocal and the thread exceptions. See docs/threads.md. The compiler keys on `java.lang.Thread.start` to switch every
 * reference count of the program to atomic.
 */
package java.lang

import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

/** `java.lang.Object`: `Any` (its `wait`, `notify` and `notifyAll` are the extension functions of kotlin.synchronized). */
public typealias Object = Any

public fun interface Runnable {
    public fun run()
}

public open class InterruptedException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IllegalThreadStateException : IllegalArgumentException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IllegalMonitorStateException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

private object ThreadRegistry {
    val lock = Lock()
    val byId = HashMap<Long, Thread>()
    var nextIndex = 0
    var nextId = 2L
}

/**
 * A native thread. `Thread { ... }`, `Thread(runnable)`, `Thread(block, name)` and subclasses that override [run] work as on the JVM.
 * An interrupt is a flag ([interrupt], [isInterrupted], [interrupted]); blocking calls of this library poll it only where noted.
 */
public open class Thread(private val block: (() -> Unit)?, name: String?) : Runnable {
    public constructor() : this(null, null)
    public constructor(block: () -> Unit) : this(block, null)
    public constructor(target: Runnable) : this({ target.run() }, null)
    public constructor(target: Runnable, name: String) : this({ target.run() }, name)
    public constructor(name: String) : this(null, name)

    private var handle = 0L
    private var started = false
    private var finished = false
    private var interruptedFlag = false
    private val state = Lock()
    private val done: Condition = state.newCondition()
    private val sleepCond = LongArray(16)
    private var waitCond: LongArray? = null
    private var waitMutex: LongArray? = null

    public var name: String
    public var id: Long = 0L
        internal set
    public var isDaemon: Boolean = false
    public var priority: Int = 5

    init {
        sysCondInit(sleepCond)
        ThreadRegistry.lock.lock()
        val n = ThreadRegistry.nextIndex
        if (name == null) ThreadRegistry.nextIndex = n + 1
        val i = ThreadRegistry.nextId
        ThreadRegistry.nextId = i + 1L
        ThreadRegistry.lock.unlock()
        this.name = name ?: ("Thread-" + n)
        this.id = i
    }

    /** The body of the thread: the block it was made with (does nothing for a plain `Thread()`). */
    public override fun run() {
        val b = block
        if (b != null) b()
    }

    public fun start() {
        state.lock()
        if (started) {
            state.unlock()
            throw IllegalThreadStateException()
        }
        started = true
        val me = this
        handle = sysThreadStart({ me.runBody() })
        val failed = handle == 0L
        state.unlock()
        if (failed) throw IllegalStateException("threads are not supported on this platform")
    }

    private fun runBody() {
        val sid = sysThreadId()
        ThreadRegistry.lock.lock()
        ThreadRegistry.byId[sid] = this
        ThreadRegistry.lock.unlock()
        try {
            run()
        } finally {
            ThreadRegistry.lock.lock()
            ThreadRegistry.byId.remove(sid)
            ThreadRegistry.lock.unlock()
            state.lock()
            finished = true
            done.signalAll()
            // the operating-system thread is detached: give up the handle (the runtime frees it when the thread ends)
            val h = handle
            handle = 0L
            state.unlock()
            if (h != 0L) sysThreadDetach(h)
        }
    }

    public val isAlive: Boolean
        get() {
            state.lock()
            val r = started && !finished
            state.unlock()
            return r
        }

    /** Waits until the thread has ended. */
    public fun join() {
        join(0L)
    }

    /** Waits at most [millis] milliseconds for the thread to end (0: without limit). */
    public fun join(millis: Long) {
        state.lock()
        if (!started) {
            state.unlock()
            return
        }
        if (millis <= 0L) {
            while (!finished) done.await()
        } else {
            val end = sysMonotonicNanos() + millis * 1000000L
            while (!finished) {
                val left = end - sysMonotonicNanos()
                if (left <= 0L) break
                done.awaitNanos(left)
            }
        }
        state.unlock()
    }

    /**
     * Sets the interrupt flag and wakes the thread if it sleeps, waits in [join], in a [Condition] / a blocking queue / a latch /
     * a semaphore or in `Object.wait`: the waiting call throws [InterruptedException] (and clears the flag).
     */
    public fun interrupt() {
        state.lock()
        interruptedFlag = true
        sysCondBroadcast(sleepCond)
        val c = waitCond
        val m = waitMutex
        state.unlock()
        if (c != null && m != null) {
            // wake the condition variable the thread waits on; its mutex is taken for that, so the thread is surely inside the wait. If the
            // mutex stays taken (the interrupting thread holds it itself) the wait ends at its next slice, see interruptibleWait.
            var tries = 0
            while (tries < 200) {
                if (sysTryLock(m)) {
                    sysCondBroadcast(c)
                    sysUnlock(m)
                    break
                }
                sysThreadYield()
                tries++
            }
        }
    }

    public val isInterrupted: Boolean
        get() {
            state.lock()
            val r = interruptedFlag
            state.unlock()
            return r
        }

    internal fun clearInterrupt(): Boolean {
        state.lock()
        val r = interruptedFlag
        interruptedFlag = false
        state.unlock()
        return r
    }

    internal fun throwIfInterrupted() {
        if (clearInterrupt()) throw InterruptedException()
    }

    internal fun setWaiting(cond: LongArray?, mutex: LongArray?) {
        state.lock()
        waitCond = cond
        waitMutex = mutex
        state.unlock()
    }

    /** Sleeps; the sleep ends early (with InterruptedException) when the thread is interrupted. */
    internal fun sleepNanos(nanos: Long) {
        val end = sysMonotonicNanos() + nanos
        state.lock()
        while (!interruptedFlag) {
            val left = end - sysMonotonicNanos()
            if (left <= 0L) break
            sysCondWaitNanos(sleepCond, state.storage, left)
        }
        val was = interruptedFlag
        interruptedFlag = false
        state.unlock()
        if (was) throw InterruptedException("sleep interrupted")
    }

    public override fun toString(): String = "Thread[#" + id + "," + name + "," + priority + "]"

    public companion object {
        private var mainThread: Thread? = null

        public fun sleep(millis: Long) {
            if (millis < 0L) throw IllegalArgumentException("timeout value is negative")
            currentThread().sleepNanos(millis * 1000000L)
        }

        /** Hints the scheduler to run another thread. */
        public fun yield() {
            sysThreadYield()
        }

        public fun currentThread(): Thread {
            val sid = sysThreadId()
            ThreadRegistry.lock.lock()
            val t = ThreadRegistry.byId[sid]
            ThreadRegistry.lock.unlock()
            if (t != null) return t
            // the main thread, or a thread that was not started through this class
            var m = mainThread
            if (m == null) {
                val created = Thread(null, "main")
                created.id = 1L
                ThreadRegistry.lock.lock()
                if (mainThread == null) mainThread = created
                m = mainThread
                ThreadRegistry.lock.unlock()
            }
            return m!!
        }

        /** Tests and clears the interrupt flag of the current thread. */
        public fun interrupted(): Boolean = currentThread().clearInterrupt()

        public fun onSpinWait() {
            sysThreadYield()
        }
    }
}

/**
 * One wait on a condition variable that the thread can be interrupted in: a wait of at most one second (a longer wait is repeated by the
 * callers, which loop on their predicates anyway), registered with the thread so that [Thread.interrupt] can wake it.
 * [nanos] < 0: without a limit. True when the time given ran out.
 */
internal fun interruptibleWait(cond: LongArray, mutex: LongArray, nanos: Long): Boolean {
    val t = Thread.currentThread()
    t.throwIfInterrupted()
    t.setWaiting(cond, mutex)
    val slice = 1000000000L
    var timedOut = false
    if (nanos in 0L..slice) {
        timedOut = sysCondWaitNanos(cond, mutex, nanos)
    } else {
        sysCondWaitNanos(cond, mutex, slice)
    }
    t.setWaiting(null, null)
    t.throwIfInterrupted()
    return timedOut
}

/** A value per thread. Subclass and override [initialValue], or use [withInitial]. */
public open class ThreadLocal<T> {
    private val lock = Lock()
    private val values = HashMap<Long, Any?>()
    private var supplier: (() -> T)? = null

    protected open fun initialValue(): T? {
        val s = supplier
        return if (s != null) s() else null
    }

    @Suppress("UNCHECKED_CAST")
    public fun get(): T? {
        val sid = sysThreadId()
        lock.lock()
        if (values.containsKey(sid)) {
            val v = values[sid]
            lock.unlock()
            return v as T?
        }
        lock.unlock()
        val init = initialValue()
        lock.lock()
        values[sid] = init
        lock.unlock()
        return init
    }

    public fun set(value: T?) {
        val sid = sysThreadId()
        lock.lock()
        values[sid] = value
        lock.unlock()
    }

    public fun remove() {
        val sid = sysThreadId()
        lock.lock()
        values.remove(sid)
        lock.unlock()
    }

    public companion object {
        public fun <S> withInitial(supplier: java.util.function.Supplier<out S>): ThreadLocal<S> {
            val t = ThreadLocal<S>()
            t.supplier = { supplier.get() }
            return t
        }
    }
}
