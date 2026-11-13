/*
 * java.util.concurrent: Callable, Future, FutureTask, ExecutorService, Executors (fixed, single, cached and work-stealing pools on
 * native threads) and CompletableFuture. See docs/threads.md.
 */
package java.util.concurrent

import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import mini.concurrent.Condition
import mini.concurrent.Lock
import mini.internal.*

public fun interface Callable<V> {
    public fun call(): V
}

public interface Executor {
    public fun execute(command: Runnable)
}

public interface Future<V> {
    public fun get(): V
    public fun get(timeout: Long, unit: TimeUnit): V
    public fun cancel(mayInterruptIfRunning: Boolean): Boolean
    public val isCancelled: Boolean
    public val isDone: Boolean
}

public interface RunnableFuture<V> : Runnable, Future<V>

public class FutureTask<V> : RunnableFuture<V> {
    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var callable: Callable<V>?
    private var state = 0 // 0 new, 1 running, 2 done, 3 failed, 4 cancelled
    private var result: V? = null
    private var failure: Throwable? = null

    public constructor(callable: Callable<V>) {
        this.callable = callable
    }

    public constructor(runnable: Runnable, value: V) {
        this.callable = Callable { runnable.run(); value }
    }

    public override fun run() {
        lock.lock()
        val c = callable
        if (state != 0 || c == null) {
            lock.unlock()
            return
        }
        state = 1
        lock.unlock()
        var r: V? = null
        var err: Throwable? = null
        try {
            r = c.call()
        } catch (e: Throwable) {
            err = e
        }
        lock.lock()
        if (state == 1) {
            if (err == null) {
                result = r
                state = 2
            } else {
                failure = err
                state = 3
            }
            callable = null
            changed.signalAll()
        }
        lock.unlock()
    }

    /** Runs the computation without setting the result (state stays "new" when it succeeds); false when it failed or was cancelled. */
    internal fun runAndReset(): Boolean {
        lock.lock()
        val c = callable
        if (state != 0 || c == null) {
            lock.unlock()
            return false
        }
        lock.unlock()
        try {
            c.call()
        } catch (e: Throwable) {
            lock.lock()
            if (state == 0) {
                failure = e
                state = 3
                callable = null
                changed.signalAll()
            }
            lock.unlock()
            return false
        }
        lock.lock()
        val ok = state == 0
        lock.unlock()
        return ok
    }

    @Suppress("UNCHECKED_CAST")
    private fun report(): V {
        val s = state
        if (s == 4) throw CancellationException()
        if (s == 3) throw ExecutionException(failure)
        return result as V
    }

    public override fun get(): V {
        lock.lock()
        while (state < 2) changed.await()
        val s = state
        lock.unlock()
        return report()
    }

    public override fun get(timeout: Long, unit: TimeUnit): V {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (state < 2) {
            if (awaitDeadline(changed, end)) break
        }
        if (state < 2) {
            lock.unlock()
            throw TimeoutException()
        }
        lock.unlock()
        return report()
    }

    public override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        lock.lock()
        val ok = state == 0
        if (ok) {
            state = 4
            callable = null
            changed.signalAll()
        }
        lock.unlock()
        return ok
    }

    public override val isCancelled: Boolean get() = state == 4
    public override val isDone: Boolean get() = state >= 2
}

public interface ExecutorService : Executor, AutoCloseable {
    public fun shutdown()
    public fun shutdownNow(): List<Runnable>
    public val isShutdown: Boolean
    public val isTerminated: Boolean
    public fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean
    public fun <T> submit(task: Callable<T>): Future<T>
    public fun submit(task: Runnable): Future<*>
    public fun <T> submit(task: Runnable, result: T): Future<T>
    public fun <T> invokeAll(tasks: Collection<Callable<T>>): List<Future<T>>
    public fun <T> invokeAny(tasks: Collection<Callable<T>>): T
}

public interface ScheduledFuture<V> : Delayed, Future<V>

public interface ScheduledExecutorService : ExecutorService {
    public fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*>
    public fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V>
    public fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*>
    public fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*>
}

public fun interface ThreadFactory {
    public fun newThread(r: Runnable): Thread
}

/** The thread factory of [Executors]: threads named `pool-N-thread-M`, N counting the factories made, M the threads of this one. */
private class DefaultThreadFactory : ThreadFactory {
    private val poolNumber = DefaultThreadFactoryCounter.next()
    private val threadNumber = java.util.concurrent.atomic.AtomicInteger(1)

    override fun newThread(r: Runnable): Thread = Thread(r, "pool-" + poolNumber + "-thread-" + threadNumber.getAndIncrement())
}

private object DefaultThreadFactoryCounter {
    private val n = java.util.concurrent.atomic.AtomicInteger(1)
    fun next(): Int = n.getAndIncrement()
}

/** A delayed or periodic task of a [ThreadPoolExecutor]: [time] is the next run on the monotonic clock; [period] > 0 fixed rate, < 0 fixed delay. */
private class ScheduledTask<V>(
    private val pool: ThreadPoolExecutor,
    callable: Callable<V>,
    var time: Long,
    val period: Long,
    val seq: Long,
) : ScheduledFuture<V>, Runnable {
    val inner = FutureTask(callable)

    override fun run() {
        if (period == 0L) {
            inner.run()
        } else if (inner.runAndReset()) {
            time = if (period > 0L) time + period else sysMonotonicNanos() - period
            pool.requeue(this)
        }
    }

    override fun get(): V = inner.get()
    override fun get(timeout: Long, unit: TimeUnit): V = inner.get(timeout, unit)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val ok = inner.cancel(mayInterruptIfRunning)
        if (ok) pool.removeDelayed(this)
        return ok
    }

    override val isCancelled: Boolean get() = inner.isCancelled
    override val isDone: Boolean get() = inner.isDone
    override fun getDelay(unit: TimeUnit): Long = unit.convert(time - sysMonotonicNanos(), TimeUnit.NANOSECONDS)

    override fun compareTo(other: Delayed): Int {
        if (other === this) return 0
        if (other is ScheduledTask<*>) {
            if (time != other.time) return if (time < other.time) -1 else 1
            return if (seq < other.seq) -1 else if (seq > other.seq) 1 else 0
        }
        val d = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS)
        return if (d < 0L) -1 else if (d > 0L) 1 else 0
    }
}

/** A pool of worker threads over a task queue. [maxThreads] workers at most; idle ones beyond [coreThreads] end after [keepAliveNanos]. */
public open class ThreadPoolExecutor(
    private val coreThreads: Int,
    private val maxThreads: Int,
    private val keepAliveNanos: Long,
) : ScheduledExecutorService {
    private val lock = Lock()
    private val work: Condition = lock.newCondition()
    private val terminated: Condition = lock.newCondition()
    private val queue = ArrayDeque<Runnable>()
    private var workers = 0
    private var idle = 0
    private var shut = false
    private var poolId = 0
    private val delayed = ArrayList<ScheduledTask<*>>()
    private val timerCond: Condition = lock.newCondition()
    private var timerRunning = false
    private var seqCounter = 0L
    private var factory: ThreadFactory? = null

    /** The factory of the worker threads (the default one names them `pool-N-thread-M`). */
    public var threadFactory: ThreadFactory
        get() {
            lock.lock()
            var f = factory
            if (f == null) {
                f = DefaultThreadFactory()
                factory = f
            }
            lock.unlock()
            return f
        }
        set(value) {
            lock.lock()
            factory = value
            lock.unlock()
        }

    // the caller holds the lock
    private fun startWorker() {
        workers++
        val me = this
        var f = factory
        if (f == null) {
            f = DefaultThreadFactory()
            factory = f
        }
        val t = f.newThread { me.workerLoop() }
        t.isDaemon = true
        t.start()
    }

    // the caller holds the lock; delayed tasks of a pool that is shut down are still to run (one-shot ones)
    private fun finished(): Boolean = shut && workers == 0 && queue.isEmpty() && delayed.isEmpty()

    private fun startTimer() {
        timerRunning = true
        val me = this
        val t = Thread({ me.timerLoop() }, "pool-timer")
        t.isDaemon = true
        t.start()
    }

    private fun timerLoop() {
        lock.lock()
        while (true) {
            if (delayed.isEmpty()) {
                if (shut) break
                timerCond.await()
                continue
            }
            val first = delayed[0]
            val now = sysMonotonicNanos()
            if (first.time > now) {
                timerCond.awaitNanos(first.time - now)
                continue
            }
            delayed.removeAt(0)
            enqueue(first)
        }
        timerRunning = false
        work.signalAll()
        if (finished()) terminated.signalAll()
        lock.unlock()
    }

    // the caller holds the lock
    private fun insertDelayed(t: ScheduledTask<*>) {
        var i = delayed.size
        while (i > 0 && delayed[i - 1].compareTo(t) > 0) i--
        delayed.add(i, t)
        if (!timerRunning) startTimer()
        timerCond.signalAll()
    }

    internal fun requeue(t: ScheduledTask<*>) {
        lock.lock()
        if (!shut) insertDelayed(t)
        lock.unlock()
    }

    internal fun removeDelayed(t: ScheduledTask<*>) {
        lock.lock()
        delayed.remove(t)
        timerCond.signalAll()
        if (finished()) terminated.signalAll()
        lock.unlock()
    }

    // the caller holds the lock
    private fun enqueue(command: Runnable) {
        queue.addLast(command)
        if (idle < queue.size && workers < maxThreads) startWorker()
        work.signal()
    }

    private fun workerLoop() {
        while (true) {
            lock.lock()
            var task: Runnable? = null
            while (task == null) {
                if (!queue.isEmpty()) {
                    task = queue.removeFirst()
                } else if (shut && delayed.isEmpty()) {
                    break
                } else {
                    idle++
                    if (workers > coreThreads && keepAliveNanos > 0L) {
                        val timedOut = work.awaitNanos(keepAliveNanos)
                        idle--
                        if (timedOut && queue.isEmpty() && workers > coreThreads) {
                            workers--
                            lock.unlock()
                            return
                        }
                    } else {
                        work.await()
                        idle--
                    }
                }
            }
            if (task == null) {
                workers--
                if (finished()) terminated.signalAll()
                lock.unlock()
                return
            }
            lock.unlock()
            try {
                task.run()
            } catch (e: Throwable) {
                // an exception of a task handed to execute() ends only that task
            }
        }
    }

    public override fun execute(command: Runnable) {
        lock.lock()
        if (shut) {
            lock.unlock()
            throw RejectedExecutionException("executor has been shut down")
        }
        enqueue(command)
        lock.unlock()
    }

    public override fun <T> submit(task: Callable<T>): Future<T> {
        val f = FutureTask(task)
        execute(f)
        return f
    }

    public override fun submit(task: Runnable): Future<*> {
        val f = FutureTask<Any?>(task, null)
        execute(f)
        return f
    }

    public override fun <T> submit(task: Runnable, result: T): Future<T> {
        val f = FutureTask(task, result)
        execute(f)
        return f
    }

    public override fun <T> invokeAll(tasks: Collection<Callable<T>>): List<Future<T>> {
        val fs = ArrayList<Future<T>>()
        for (t in tasks) fs.add(submit(t))
        for (f in fs) {
            try {
                f.get()
            } catch (e: ExecutionException) {
            } catch (e: CancellationException) {
            }
        }
        return fs
    }

    public override fun <T> invokeAny(tasks: Collection<Callable<T>>): T {
        var last: Throwable? = null
        for (t in tasks) {
            try {
                return submit(t).get()
            } catch (e: ExecutionException) {
                last = e
            }
        }
        throw ExecutionException(last)
    }

    public override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        scheduleTask(Callable<Any?> { command.run(); null }, delay, unit, 0L)

    public override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
        scheduleTask(callable, delay, unit, 0L)

    public override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
        if (period <= 0L) throw IllegalArgumentException()
        return scheduleTask(Callable<Any?> { command.run(); null }, initialDelay, unit, unit.toNanos(period))
    }

    public override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        if (delay <= 0L) throw IllegalArgumentException()
        return scheduleTask(Callable<Any?> { command.run(); null }, initialDelay, unit, -unit.toNanos(delay))
    }

    private fun <V> scheduleTask(callable: Callable<V>, delay: Long, unit: TimeUnit, period: Long): ScheduledTask<V> {
        lock.lock()
        if (shut) {
            lock.unlock()
            throw RejectedExecutionException("executor has been shut down")
        }
        val t = ScheduledTask(this, callable, sysMonotonicNanos() + (if (delay > 0L) unit.toNanos(delay) else 0L), period, seqCounter++)
        insertDelayed(t)
        lock.unlock()
        return t
    }

    public override fun shutdown() {
        lock.lock()
        shut = true
        // periodic tasks end with the pool, one-shot delayed tasks still run
        var i = 0
        while (i < delayed.size) {
            val t = delayed[i]
            if (t.period != 0L) {
                t.inner.cancel(false)
                delayed.removeAt(i)
            } else {
                i++
            }
        }
        work.signalAll()
        timerCond.signalAll()
        if (finished()) terminated.signalAll()
        lock.unlock()
    }

    public override fun shutdownNow(): List<Runnable> {
        lock.lock()
        shut = true
        val rest = ArrayList<Runnable>(queue)
        queue.clear()
        for (t in delayed) rest.add(t)
        delayed.clear()
        work.signalAll()
        timerCond.signalAll()
        if (finished()) terminated.signalAll()
        lock.unlock()
        return rest
    }

    public override val isShutdown: Boolean get() = shut

    public override val isTerminated: Boolean
        get() {
            lock.lock()
            val r = finished()
            lock.unlock()
            return r
        }

    public override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (!finished()) {
            if (awaitDeadline(terminated, end)) break
        }
        val r = finished()
        lock.unlock()
        return r
    }

    public override fun close() {
        shutdown()
        awaitTermination(Long.MAX_VALUE / 4, TimeUnit.NANOSECONDS)
    }
}

public object Executors {
    public fun defaultThreadFactory(): ThreadFactory = DefaultThreadFactory()

    private fun pool(core: Int, max: Int, keepAliveNanos: Long, factory: ThreadFactory?): ThreadPoolExecutor {
        val p = ThreadPoolExecutor(core, max, keepAliveNanos)
        p.threadFactory = factory ?: DefaultThreadFactory()
        return p
    }

    public fun newFixedThreadPool(nThreads: Int): ExecutorService = newFixedThreadPool(nThreads, null)

    public fun newFixedThreadPool(nThreads: Int, threadFactory: ThreadFactory?): ExecutorService {
        if (nThreads <= 0) throw IllegalArgumentException()
        return pool(nThreads, nThreads, 0L, threadFactory)
    }

    public fun newSingleThreadExecutor(): ExecutorService = pool(1, 1, 0L, null)

    public fun newSingleThreadExecutor(threadFactory: ThreadFactory?): ExecutorService = pool(1, 1, 0L, threadFactory)

    public fun newCachedThreadPool(): ExecutorService = pool(0, Int.MAX_VALUE, 60000000000L, null)

    public fun newCachedThreadPool(threadFactory: ThreadFactory?): ExecutorService = pool(0, Int.MAX_VALUE, 60000000000L, threadFactory)

    public fun newWorkStealingPool(): ExecutorService {
        var n = sysCpuCount().toInt()
        if (n < 1) n = 1
        return ForkJoinPool(n)
    }

    public fun newWorkStealingPool(parallelism: Int): ExecutorService = ForkJoinPool(parallelism)

    public fun newVirtualThreadPerTaskExecutor(): ExecutorService = newCachedThreadPool()

    public fun newThreadPerTaskExecutor(threadFactory: ThreadFactory): ExecutorService = newCachedThreadPool(threadFactory)

    public fun newScheduledThreadPool(corePoolSize: Int): ScheduledExecutorService = newScheduledThreadPool(corePoolSize, null)

    public fun newScheduledThreadPool(corePoolSize: Int, threadFactory: ThreadFactory?): ScheduledExecutorService =
        pool(corePoolSize, if (corePoolSize < 1) 1 else corePoolSize, 0L, threadFactory)

    public fun newSingleThreadScheduledExecutor(): ScheduledExecutorService = pool(1, 1, 0L, null)

    public fun newSingleThreadScheduledExecutor(threadFactory: ThreadFactory?): ScheduledExecutorService = pool(1, 1, 0L, threadFactory)

    public fun <T> callable(task: Runnable, result: T): Callable<T> = Callable { task.run(); result }

    public fun callable(task: Runnable): Callable<Any?> = Callable { task.run(); null }

    public fun unconfigurableExecutorService(executor: ExecutorService): ExecutorService = executor
}

public interface CompletionService<V> {
    public fun submit(task: Callable<V>): Future<V>
    public fun submit(task: Runnable, result: V): Future<V>
    public fun take(): Future<V>
    public fun poll(): Future<V>?
    public fun poll(timeout: Long, unit: TimeUnit): Future<V>?
}

/** A [CompletionService] over an executor: finished futures come out of `take` in the order they finished. */
public class ExecutorCompletionService<V>(private val executor: Executor) : CompletionService<V> {
    private val done = LinkedBlockingQueue<Future<V>>()

    private fun wrap(task: Callable<V>): FutureTask<V> {
        val q = done
        var self: FutureTask<V>? = null
        val ft = FutureTask<V>(Callable<V> {
            try {
                task.call()
            } finally {
                q.add(self!!)
            }
        })
        self = ft
        return ft
    }

    public override fun submit(task: Callable<V>): Future<V> {
        val f = wrap(task)
        executor.execute(f)
        return f
    }

    public override fun submit(task: Runnable, result: V): Future<V> = submit(Callable<V> { task.run(); result })
    public override fun take(): Future<V> = done.take()
    public override fun poll(): Future<V>? = done.poll()
    public override fun poll(timeout: Long, unit: TimeUnit): Future<V>? = done.poll(timeout, unit)
}

private object CommonPool {
    val pool: ExecutorService = ForkJoinPool.commonPool()
}

/** A future with callbacks. Async stages run on a shared cached pool unless an executor is given. */
public class CompletableFuture<T> : Future<T> {
    private val lock = Lock()
    private val changed: Condition = lock.newCondition()
    private var done = false
    private var value: T? = null
    private var failure: Throwable? = null
    private var callbacks = ArrayList<Runnable>()

    public fun complete(v: T?): Boolean = finish(v, null)

    public fun completeExceptionally(ex: Throwable): Boolean = finish(null, ex)

    private fun finish(v: T?, ex: Throwable?): Boolean {
        lock.lock()
        if (done) {
            lock.unlock()
            return false
        }
        value = v
        failure = ex
        done = true
        changed.signalAll()
        val cbs = callbacks
        callbacks = ArrayList<Runnable>()
        lock.unlock()
        for (c in cbs) c.run()
        return true
    }

    private fun onDone(r: Runnable) {
        lock.lock()
        if (!done) {
            callbacks.add(r)
            lock.unlock()
            return
        }
        lock.unlock()
        r.run()
    }

    private fun wait(): Unit {
        lock.lock()
        while (!done) changed.await()
        lock.unlock()
    }

    @Suppress("UNCHECKED_CAST")
    public override fun get(): T {
        wait()
        val f = failure
        if (f != null) throw ExecutionException(if (f is CompletionException) f.cause else f)
        return value as T
    }

    public override fun get(timeout: Long, unit: TimeUnit): T {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (!done) {
            if (awaitDeadline(changed, end)) break
        }
        val d = done
        lock.unlock()
        if (!d) throw TimeoutException()
        return get()
    }

    @Suppress("UNCHECKED_CAST")
    public fun join(): T {
        wait()
        val f = failure
        if (f != null) throw if (f is CompletionException) f else CompletionException(f)
        return value as T
    }

    @Suppress("UNCHECKED_CAST")
    public fun getNow(valueIfAbsent: T): T {
        lock.lock()
        val d = done
        lock.unlock()
        if (!d) return valueIfAbsent
        return join()
    }

    public override fun cancel(mayInterruptIfRunning: Boolean): Boolean = completeExceptionally(CancellationException())
    public override val isCancelled: Boolean get() = failure is CancellationException
    public override val isDone: Boolean get() = done
    public val isCompletedExceptionally: Boolean get() = done && failure != null

    @Suppress("UNCHECKED_CAST")
    public fun <U> thenApply(fn: Function<in T, out U>): CompletableFuture<U> {
        val next = CompletableFuture<U>()
        val me = this
        onDone {
            val f = me.failure
            if (f != null) next.completeExceptionally(wrap(f))
            else {
                try {
                    next.complete(fn.apply(me.value as T))
                } catch (e: Throwable) {
                    next.completeExceptionally(wrap(e))
                }
            }
        }
        return next
    }

    public fun <U> thenApplyAsync(fn: Function<in T, out U>): CompletableFuture<U> {
        val next = CompletableFuture<U>()
        val me = this
        onDone { CommonPool.pool.execute { me.thenApply(fn).whenComplete { v, e -> if (e != null) next.completeExceptionally(e) else next.complete(v) } } }
        return next
    }

    @Suppress("UNCHECKED_CAST")
    public fun thenAccept(action: Consumer<in T>): CompletableFuture<Unit> = thenApply(Function<T, Unit> { action.accept(it); Unit })

    public fun thenRun(action: Runnable): CompletableFuture<Unit> = thenApply(Function<T, Unit> { action.run(); Unit })

    @Suppress("UNCHECKED_CAST")
    public fun <U> thenCompose(fn: Function<in T, out CompletableFuture<U>>): CompletableFuture<U> {
        val next = CompletableFuture<U>()
        val me = this
        onDone {
            val f = me.failure
            if (f != null) next.completeExceptionally(wrap(f))
            else {
                try {
                    val inner = fn.apply(me.value as T)
                    inner.whenComplete { v, e -> if (e != null) next.completeExceptionally(e) else next.complete(v) }
                } catch (e: Throwable) {
                    next.completeExceptionally(wrap(e))
                }
            }
        }
        return next
    }

    @Suppress("UNCHECKED_CAST")
    public fun <U, V> thenCombine(other: CompletableFuture<out U>, fn: BiFunction<in T, in U, out V>): CompletableFuture<V> {
        val next = CompletableFuture<V>()
        val me = this
        onDone {
            other.onDone {
                val f = me.failure ?: other.failure
                if (f != null) next.completeExceptionally(wrap(f))
                else {
                    try {
                        next.complete(fn.apply(me.value as T, other.value as U))
                    } catch (e: Throwable) {
                        next.completeExceptionally(wrap(e))
                    }
                }
            }
        }
        return next
    }

    @Suppress("UNCHECKED_CAST")
    public fun exceptionally(fn: Function<in Throwable, out T>): CompletableFuture<T> {
        val next = CompletableFuture<T>()
        val me = this
        onDone {
            val f = me.failure
            if (f == null) next.complete(me.value)
            else {
                try {
                    next.complete(fn.apply(f))
                } catch (e: Throwable) {
                    next.completeExceptionally(wrap(e))
                }
            }
        }
        return next
    }

    @Suppress("UNCHECKED_CAST")
    public fun whenComplete(action: BiConsumer<in T?, in Throwable?>): CompletableFuture<T> {
        val next = CompletableFuture<T>()
        val me = this
        onDone {
            try {
                action.accept(me.value, me.failure)
            } catch (e: Throwable) {
                if (me.failure == null) {
                    next.completeExceptionally(wrap(e))
                    return@onDone
                }
            }
            val f = me.failure
            if (f != null) next.completeExceptionally(f) else next.complete(me.value)
        }
        return next
    }


    // the same with Kotlin function types: Mini infers the result type of a lambda through these, not through the SAM interfaces
    public fun <U> thenApply(fn: (T) -> U): CompletableFuture<U> = thenApply(Function<T, U> { fn(it) })

    public fun thenAccept(action: (T) -> Unit): CompletableFuture<Unit> = thenApply(Function<T, Unit> { action(it); Unit })

    public fun <U> thenCompose(fn: (T) -> CompletableFuture<U>): CompletableFuture<U> =
        thenCompose(Function<T, CompletableFuture<U>> { fn(it) })

    public fun <U, V> thenCombine(other: CompletableFuture<U>, fn: (T, U) -> V): CompletableFuture<V> =
        thenCombine(other, BiFunction<T, U, V> { a, b -> fn(a, b) })

    public fun exceptionally(fn: (Throwable) -> T): CompletableFuture<T> = exceptionally(Function<Throwable, T> { fn(it) })

    public fun whenComplete(action: (T?, Throwable?) -> Unit): CompletableFuture<T> =
        whenComplete(BiConsumer<T?, Throwable?> { v, e -> action(v, e) })

    public companion object {
        internal fun wrap(t: Throwable): Throwable = if (t is CompletionException) t else CompletionException(t)

        public fun <U> supplyAsync(supplier: Supplier<out U>): CompletableFuture<U> = supplyAsync(supplier, CommonPool.pool)

        public fun <U> supplyAsync(supplier: () -> U): CompletableFuture<U> = supplyAsync(Supplier<U> { supplier() }, CommonPool.pool)

        public fun <U> supplyAsync(supplier: () -> U, executor: Executor): CompletableFuture<U> =
            supplyAsync(Supplier<U> { supplier() }, executor)

        public fun <U> supplyAsync(supplier: Supplier<out U>, executor: Executor): CompletableFuture<U> {
            val f = CompletableFuture<U>()
            executor.execute {
                try {
                    f.complete(supplier.get())
                } catch (e: Throwable) {
                    f.completeExceptionally(wrap(e))
                }
            }
            return f
        }

        public fun runAsync(action: Runnable): CompletableFuture<Unit> = supplyAsync(Supplier { action.run(); Unit })

        public fun runAsync(action: Runnable, executor: Executor): CompletableFuture<Unit> =
            supplyAsync(Supplier { action.run(); Unit }, executor)

        public fun <U> completedFuture(value: U): CompletableFuture<U> {
            val f = CompletableFuture<U>()
            f.complete(value)
            return f
        }

        public fun allOf(vararg futures: CompletableFuture<*>): CompletableFuture<Unit> {
            val result = CompletableFuture<Unit>()
            if (futures.isEmpty()) {
                result.complete(Unit)
                return result
            }
            val remaining = java.util.concurrent.atomic.AtomicInteger(futures.size)
            for (f in futures) {
                f.whenComplete { _, e ->
                    if (e != null) result.completeExceptionally(e)
                    if (remaining.decrementAndGet() == 0) result.complete(Unit)
                }
            }
            return result
        }
    }
}

public open class CompletionException : RuntimeException {
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}
