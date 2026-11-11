/*
 * The Kotlin/Native worker API (`kotlin.native.concurrent`) on the native threads of java.lang.Thread: a worker is a thread that
 * runs the jobs handed to it, one after the other. `execute` takes the job with a producer for its argument (the transfer is a
 * plain reference here), the Future gives the result or throws the exception of the job.
 */
package kotlin.native.concurrent

import java.lang.Thread
import mini.concurrent.Condition
import mini.concurrent.Lock

public enum class TransferMode { SAFE, UNSAFE }

public class Future<T> internal constructor() {
    private val lock = Lock()
    private val cond: Condition = lock.newCondition()
    private var done = false
    private var value: Any? = null
    private var error: Throwable? = null

    internal fun complete(v: Any?, e: Throwable?) {
        lock.lock()
        value = v
        error = e
        done = true
        cond.signalAll()
        lock.unlock()
    }

    public val isDone: Boolean
        get() {
            lock.lock()
            val d = done
            lock.unlock()
            return d
        }

    @Suppress("UNCHECKED_CAST")
    public val result: T
        get() {
            lock.lock()
            while (!done) cond.await()
            val e = error
            val v = value
            lock.unlock()
            if (e != null) throw e
            return v as T
        }
}

public class Worker private constructor(public val name: String) {
    private val lock = Lock()
    private val cond: Condition = lock.newCondition()
    private val queue = ArrayList<() -> Unit>()
    private var terminating = false
    private val thread = Thread({ loop() }, name)
    public val id: Int = nextId()

    private fun loop() {
        while (true) {
            lock.lock()
            while (queue.isEmpty() && !terminating) cond.await()
            if (queue.isEmpty()) {
                lock.unlock()
                return
            }
            val task = queue.removeAt(0)
            lock.unlock()
            task()
        }
    }

    private fun post(task: () -> Unit) {
        lock.lock()
        queue.add(task)
        cond.signal()
        lock.unlock()
    }

    public fun <T1, T2> execute(mode: TransferMode, producer: () -> T1, job: (T1) -> T2): Future<T2> {
        val f = Future<T2>()
        val arg = producer()
        post {
            try {
                f.complete(job(arg), null)
            } catch (t: Throwable) {
                f.complete(null, t)
            }
        }
        return f
    }

    public fun requestTermination(processScheduledJobs: Boolean = true): Future<Unit> {
        val f = Future<Unit>()
        post {
            f.complete(Unit, null)
        }
        lock.lock()
        terminating = true
        cond.signalAll()
        lock.unlock()
        return f
    }

    public companion object {
        private val idLock = Lock()
        private var counter = 0

        internal fun nextId(): Int {
            idLock.lock()
            counter = counter + 1
            val n = counter
            idLock.unlock()
            return n
        }

        public fun start(errorReporting: Boolean = true, name: String? = null): Worker {
            val w = Worker(name ?: "worker")
            w.thread.start()
            return w
        }
    }
}
