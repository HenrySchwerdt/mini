/*
 * java.util.concurrent: ForkJoinPool, ForkJoinTask, RecursiveTask, RecursiveAction on the thread pool of Executors.kt. `fork` hands the task to the
 * pool, `join` runs it on the calling thread when no worker has started it yet (so a join never waits for a task that is not running, and a pool
 * of any size cannot deadlock on recursive tasks); there is no work stealing between per-thread deques.
 */
package java.util.concurrent

import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

public abstract class ForkJoinTask<V> : Future<V> {
    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var state = 0 // 0 new, 1 running, 2 done, 3 failed, 4 cancelled
    private var result: V? = null
    private var failure: Throwable? = null

    /** The computation (compute() of RecursiveTask / RecursiveAction). */
    internal abstract fun runBody(): V?

    // runs the task here unless somebody else has; true when this call ran it
    internal fun tryRun(pool: ForkJoinPool?): Boolean {
        lock.lock()
        if (state != 0) {
            lock.unlock()
            return false
        }
        state = 1
        lock.unlock()
        val saved = ForkJoinPool.currentCell.get()
        if (pool != null) ForkJoinPool.currentCell.set(pool)
        var r: V? = null
        var err: Throwable? = null
        try {
            r = runBody()
        } catch (e: Throwable) {
            err = e
        }
        ForkJoinPool.currentCell.set(saved)
        lock.lock()
        if (err == null) {
            result = r
            state = 2
        } else {
            failure = err
            state = 3
        }
        changed.signalAll()
        lock.unlock()
        return true
    }

    public fun fork(): ForkJoinTask<V> {
        val pool = ForkJoinPool.currentOrCommon()
        val me = this
        pool.execute { me.tryRun(pool) }
        return this
    }

    private fun await() {
        lock.lock()
        while (state < 2) changed.await()
        lock.unlock()
    }

    @Suppress("UNCHECKED_CAST")
    private fun report(): V {
        if (state == 4) throw CancellationException()
        val f = failure
        if (f != null) throw f
        return result as V
    }

    public fun join(): V {
        tryRun(null)
        await()
        return report()
    }

    public fun invoke(): V = join()

    public override fun get(): V {
        tryRun(null)
        await()
        if (state == 3) throw ExecutionException(failure)
        return report()
    }

    public override fun get(timeout: Long, unit: TimeUnit): V {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (state < 2) {
            if (awaitDeadline(changed, end)) break
        }
        val s = state
        lock.unlock()
        if (s < 2) throw TimeoutException()
        return get()
    }

    public override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        lock.lock()
        val ok = state == 0
        if (ok) {
            state = 4
            changed.signalAll()
        }
        val r = state == 4
        lock.unlock()
        return r
    }

    public override val isCancelled: Boolean get() = state == 4
    public override val isDone: Boolean get() = state >= 2
    public val isCompletedNormally: Boolean get() = state == 2
    public val isCompletedAbnormally: Boolean get() = state >= 3
    public val exception: Throwable? get() = failure

    @Suppress("UNCHECKED_CAST")
    public fun getRawResult(): V? = result

    public companion object {
        public fun invokeAll(t1: ForkJoinTask<*>, t2: ForkJoinTask<*>) {
            t2.fork()
            t1.invoke()
            t2.join()
        }

        public fun invokeAll(vararg tasks: ForkJoinTask<*>) {
            for (i in tasks.size - 1 downTo 1) tasks[i].fork()
            if (tasks.isNotEmpty()) tasks[0].invoke()
            for (i in 1 until tasks.size) tasks[i].join()
        }

        public fun <T : ForkJoinTask<*>> invokeAll(tasks: Collection<T>): Collection<T> {
            val l = ArrayList<T>(tasks)
            for (i in l.size - 1 downTo 1) l[i].fork()
            if (l.isNotEmpty()) l[0].invoke()
            for (i in 1 until l.size) l[i].join()
            return tasks
        }

        public fun getPool(): ForkJoinPool? = ForkJoinPool.currentCell.get()
        public fun inForkJoinPool(): Boolean = ForkJoinPool.currentCell.get() != null
    }
}

public abstract class RecursiveTask<V> : ForkJoinTask<V>() {
    protected abstract fun compute(): V

    internal override fun runBody(): V? = compute()
}

public abstract class RecursiveAction : ForkJoinTask<Unit>() {
    protected abstract fun compute()

    internal override fun runBody(): Unit? {
        compute()
        return Unit
    }
}

/** A pool of [parallelism] workers (the common pool grows beyond it, as a cached pool does, since its tasks may block). */
public class ForkJoinPool private constructor(public val parallelism: Int, max: Int, common: Boolean) :
    ThreadPoolExecutor(parallelism, max, if (common) 60000000000L else 0L) {
    public constructor(parallelism: Int) : this(parallelism, parallelism, false)
    public constructor() : this(sysCpuCount().toInt().let { if (it < 1) 1 else it })

    init {
        if (parallelism <= 0) throw IllegalArgumentException()
        val n = java.util.concurrent.atomic.AtomicInteger(1)
        val id = if (common) 0 else nextPoolId()
        threadFactory = ThreadFactory { r ->
            Thread(r, if (common) "ForkJoinPool.commonPool-worker-" + n.getAndIncrement() else "ForkJoinPool-" + id + "-worker-" + n.getAndIncrement())
        }
    }

    public fun <T> invoke(task: ForkJoinTask<T>): T {
        task.tryRun(this)
        return task.join()
    }

    public fun <T> submit(task: ForkJoinTask<T>): ForkJoinTask<T> {
        val me = this
        execute { task.tryRun(me) }
        return task
    }

    public fun <T> execute(task: ForkJoinTask<T>) {
        submit(task)
    }

    public companion object {
        internal val currentCell = java.lang.ThreadLocal<ForkJoinPool?>()
        private val poolIds = java.util.concurrent.atomic.AtomicInteger(1)
        private val common = ForkJoinPool(commonParallelism(), Int.MAX_VALUE, true)

        private fun commonParallelism(): Int {
            val n = sysCpuCount().toInt() - 1
            return if (n < 1) 1 else n
        }

        internal fun nextPoolId(): Int = poolIds.getAndIncrement()
        public fun commonPool(): ForkJoinPool = common
        public fun getCommonPoolParallelism(): Int = common.parallelism
        internal fun currentOrCommon(): ForkJoinPool = currentCell.get() ?: common
    }
}
