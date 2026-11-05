/*
 * mini.coroutines: dispatchers. `runBlocking` runs an event loop on the calling thread (a queue and a timer heap, woken by a
 * condition variable: no libuv; the same interface takes an I/O poller later, see docs/coroutines.md). `Dispatchers.Default`
 * and `Dispatchers.IO` are fixed thread pools on native threads, `Dispatchers.Unconfined` runs the continuation where it resumes.
 */
package mini.coroutines

import kotlin.coroutines.*
import mini.concurrent.*
import kotlin.concurrent.thread

public abstract class CoroutineDispatcher : AbstractCoroutineContextElement(ContinuationInterceptor.Key), ContinuationInterceptor {
    /** Runs [block] later, on this dispatcher; may be called from any thread. */
    public abstract fun dispatch(block: () -> Unit)

    /** Runs [block] on this dispatcher after [millis] milliseconds; the handle cancels the timer. */
    public abstract fun scheduleAfter(millis: Long, block: () -> Unit): DisposableHandle

    public override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = DispatchedContinuation(this, continuation)

    public override fun toString(): String = "CoroutineDispatcher"
}

private class DispatchedContinuation<T>(private val dispatcher: CoroutineDispatcher, private val delegate: Continuation<T>) : Continuation<T> {
    public override val context: CoroutineContext get() = delegate.context

    public override fun resumeWith(result: Result<T>) {
        val d = delegate
        dispatcher.dispatch { d.resumeWith(result) }
    }
}

// ---- timers --------------------------------------------------------------------------------------------------------------------

internal class TimerEntry(val at: Long, val seq: Long, var block: (() -> Unit)?) : DisposableHandle {
    var cancelled = false

    // the block is dropped: it usually refers to the coroutine that owns this handle (a reference cycle)
    override fun dispose() {
        CoroutinesLock.lock()
        cancelled = true
        block = null
        CoroutinesLock.unlock()
    }
}

/** A queue of tasks and a heap of timers, protected by [CoroutinesLock]; [cond] wakes the thread that waits for work. */
internal class TaskQueue {
    val tasks = ArrayDeque<() -> Unit>()
    val timers = ArrayList<TimerEntry>()
    var seq = 0L
    val cond: Condition = CoroutinesLock.newCondition()

    fun addTask(block: () -> Unit) {
        CoroutinesLock.lock()
        tasks.addLast(block)
        cond.signal()
        CoroutinesLock.unlock()
    }

    fun addTimer(millis: Long, block: () -> Unit): DisposableHandle {
        CoroutinesLock.lock()
        val at = monotonicNanos() + (if (millis < 0L) 0L else millis) * 1000000L
        val e = TimerEntry(at, seq++, block)
        // insertion sort: the earliest timer first, equal times in the order of creation
        var i = timers.size
        while (i > 0 && timers[i - 1].at > at) i--
        timers.add(i, e)
        cond.signal()
        CoroutinesLock.unlock()
        return e
    }

    /** Moves the due timers to the task queue; returns the nanoseconds until the next one (-1: no timer). Lock held. */
    fun pollTimers(): Long {
        val now = monotonicNanos()
        while (timers.isNotEmpty()) {
            val t = timers[0]
            if (t.cancelled) {
                timers.removeAt(0)
            } else if (t.at <= now) {
                timers.removeAt(0)
                val b = t.block
                t.block = null
                if (b != null) tasks.addLast(b)
            } else {
                return t.at - now
            }
        }
        return -1L
    }
}

// ---- the event loop of runBlocking -------------------------------------------------------------------------------------------

public class EventLoop internal constructor() : CoroutineDispatcher() {
    private val q = TaskQueue()

    public override fun dispatch(block: () -> Unit) {
        q.addTask(block)
    }

    public override fun scheduleAfter(millis: Long, block: () -> Unit): DisposableHandle = q.addTimer(millis) { dispatch(block) }

    internal fun wake() {
        CoroutinesLock.lock()
        q.cond.signal()
        CoroutinesLock.unlock()
    }

    /** Runs tasks and timers until [done] returns true (it is called with the lock held). */
    internal fun runUntil(done: () -> Boolean) {
        while (true) {
            var task: (() -> Unit)? = null
            CoroutinesLock.lock()
            while (task == null) {
                if (done()) {
                    CoroutinesLock.unlock()
                    return
                }
                val wait = q.pollTimers()
                if (q.tasks.isNotEmpty()) {
                    task = q.tasks.removeFirst()
                } else if (wait < 0L) {
                    q.cond.await()
                } else {
                    q.cond.awaitNanos(wait)
                }
            }
            CoroutinesLock.unlock()
            task()
        }
    }
}

// ---- thread pools ---------------------------------------------------------------------------------------------------------------

private class TimerThread {
    val q = TaskQueue()
    var started = false

    fun ensureStarted() {
        CoroutinesLock.lock()
        if (started) {
            CoroutinesLock.unlock()
            return
        }
        started = true
        CoroutinesLock.unlock()
        val t = this
        thread { t.run() }
    }

    fun run() {
        while (true) {
            var task: (() -> Unit)? = null
            CoroutinesLock.lock()
            while (task == null) {
                val wait = q.pollTimers()
                if (q.tasks.isNotEmpty()) task = q.tasks.removeFirst()
                else if (wait < 0L) q.cond.await()
                else q.cond.awaitNanos(wait)
            }
            CoroutinesLock.unlock()
            task()
        }
    }
}

private val timerThread: TimerThread = TimerThread()

/** A fixed pool of native threads that run the dispatched blocks in order of arrival. */
public class ThreadPoolDispatcher(private val name: String, private val threads: Int) : CoroutineDispatcher() {
    private val q = TaskQueue()
    private var started = false

    private fun ensureStarted() {
        CoroutinesLock.lock()
        if (started) {
            CoroutinesLock.unlock()
            return
        }
        started = true
        CoroutinesLock.unlock()
        val me = this
        for (i in 0 until threads) thread { me.worker() }
    }

    private fun worker() {
        while (true) {
            var task: (() -> Unit)? = null
            CoroutinesLock.lock()
            while (task == null) {
                if (q.tasks.isNotEmpty()) task = q.tasks.removeFirst() else q.cond.await()
            }
            CoroutinesLock.unlock()
            task()
        }
    }

    public override fun dispatch(block: () -> Unit) {
        ensureStarted()
        q.addTask(block)
    }

    public override fun scheduleAfter(millis: Long, block: () -> Unit): DisposableHandle {
        timerThread.ensureStarted()
        val me = this
        return timerThread.q.addTimer(millis) { me.dispatch(block) }
    }

    public override fun toString(): String = name
}

/** Runs the continuation in the thread that resumes it. Timers run on the timer thread. */
private object UnconfinedDispatcher : CoroutineDispatcher() {
    override fun dispatch(block: () -> Unit) {
        block()
    }

    override fun scheduleAfter(millis: Long, block: () -> Unit): DisposableHandle {
        timerThread.ensureStarted()
        return timerThread.q.addTimer(millis, block)
    }

    override fun toString(): String = "Dispatchers.Unconfined"
}

public object Dispatchers {
    private var default: ThreadPoolDispatcher? = null
    private var io: ThreadPoolDispatcher? = null

    public val Default: CoroutineDispatcher
        get() {
            CoroutinesLock.lock()
            var d = default
            if (d == null) {
                var n = availableProcessors()
                if (n < 2) n = 2
                d = ThreadPoolDispatcher("Dispatchers.Default", n)
                default = d
            }
            CoroutinesLock.unlock()
            return d
        }

    public val IO: CoroutineDispatcher
        get() {
            CoroutinesLock.lock()
            var d = io
            if (d == null) {
                d = ThreadPoolDispatcher("Dispatchers.IO", 16)
                io = d
            }
            CoroutinesLock.unlock()
            return d
        }

    public val Unconfined: CoroutineDispatcher get() = UnconfinedDispatcher
}
