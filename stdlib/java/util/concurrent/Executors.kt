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

public interface ScheduledFuture<V> : Future<V>

public interface ScheduledExecutorService : ExecutorService {
    public fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*>
    public fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V>
}

private class ScheduledTask<V>(private val inner: FutureTask<V>) : ScheduledFuture<V> {
    override fun get(): V = inner.get()
    override fun get(timeout: Long, unit: TimeUnit): V = inner.get(timeout, unit)
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = inner.cancel(mayInterruptIfRunning)
    override val isCancelled: Boolean get() = inner.isCancelled
    override val isDone: Boolean get() = inner.isDone
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

    private fun startWorker() {
        workers++
        val me = this
        val t = Thread({ me.workerLoop() }, "pool-worker")
        t.isDaemon = true
        t.start()
    }

    private fun workerLoop() {
        while (true) {
            lock.lock()
            var task: Runnable? = null
            while (task == null) {
                if (!queue.isEmpty()) {
                    task = queue.removeFirst()
                } else if (shut) {
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
                if (workers == 0) terminated.signalAll()
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
        queue.addLast(command)
        if (idle < queue.size && workers < maxThreads) startWorker()
        work.signal()
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

    public override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val f = FutureTask<Any?>(command, null)
        scheduleRun(f, delay, unit)
        return ScheduledTask(f)
    }

    public override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        val f = FutureTask(callable)
        scheduleRun(f, delay, unit)
        return ScheduledTask(f)
    }

    private fun scheduleRun(f: Runnable, delay: Long, unit: TimeUnit) {
        val me = this
        val nanos = unit.toNanos(delay)
        execute {
            // a pool worker sleeps for the delay (simple, and enough for delays that are short next to the pool size)
            if (nanos > 0L) sysSleepNanos(nanos)
            f.run()
        }
    }

    public override fun shutdown() {
        lock.lock()
        shut = true
        work.signalAll()
        if (workers == 0) terminated.signalAll()
        lock.unlock()
    }

    public override fun shutdownNow(): List<Runnable> {
        lock.lock()
        shut = true
        val rest = ArrayList<Runnable>(queue)
        queue.clear()
        work.signalAll()
        if (workers == 0) terminated.signalAll()
        lock.unlock()
        return rest
    }

    public override val isShutdown: Boolean get() = shut

    public override val isTerminated: Boolean
        get() {
            lock.lock()
            val r = shut && workers == 0
            lock.unlock()
            return r
        }

    public override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        val end = sysMonotonicNanos() + unit.toNanos(timeout)
        lock.lock()
        while (!(shut && workers == 0)) {
            if (awaitDeadline(terminated, end)) break
        }
        val r = shut && workers == 0
        lock.unlock()
        return r
    }

    public override fun close() {
        shutdown()
        awaitTermination(Long.MAX_VALUE / 4, TimeUnit.NANOSECONDS)
    }
}

public object Executors {
    public fun newFixedThreadPool(nThreads: Int): ExecutorService {
        if (nThreads <= 0) throw IllegalArgumentException()
        return ThreadPoolExecutor(nThreads, nThreads, 0L)
    }

    public fun newSingleThreadExecutor(): ExecutorService = ThreadPoolExecutor(1, 1, 0L)

    public fun newCachedThreadPool(): ExecutorService = ThreadPoolExecutor(0, Int.MAX_VALUE, 60000000000L)

    public fun newWorkStealingPool(): ExecutorService {
        var n = sysCpuCount().toInt()
        if (n < 1) n = 1
        return ThreadPoolExecutor(n, n, 0L)
    }

    public fun newWorkStealingPool(parallelism: Int): ExecutorService = ThreadPoolExecutor(parallelism, parallelism, 0L)

    public fun newVirtualThreadPerTaskExecutor(): ExecutorService = newCachedThreadPool()

    public fun newScheduledThreadPool(corePoolSize: Int): ScheduledExecutorService =
        ThreadPoolExecutor(corePoolSize, corePoolSize * 4, 0L)

    public fun newSingleThreadScheduledExecutor(): ScheduledExecutorService = ThreadPoolExecutor(1, 4, 0L)
}

private object CommonPool {
    val pool: ExecutorService = Executors.newCachedThreadPool()
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
