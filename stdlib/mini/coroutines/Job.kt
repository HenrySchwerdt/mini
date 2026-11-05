/*
 * mini.coroutines: jobs, structured concurrency and cancellation (docs/coroutines.md). The API is the core of kotlinx.coroutines;
 * the implementation is small and keeps all state under one global lock, so jobs can be used from any thread (dispatcher).
 */
package mini.coroutines

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import mini.concurrent.Lock
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** The one lock that protects the state of jobs, continuations, channels and mutexes. Handlers run after it was released. */
internal val CoroutinesLock: Lock = Lock()

public open class CancellationException(message: String? = null, cause: Throwable? = null) : IllegalStateException(message, cause)

public class TimeoutCancellationException(message: String) : CancellationException(message)

public interface DisposableHandle {
    public fun dispose()
}

internal object NoopHandle : DisposableHandle {
    override fun dispose() {}
}

public interface Job : CoroutineContext.Element {
    public val isActive: Boolean
    public val isCompleted: Boolean
    public val isCancelled: Boolean

    /** Cancels the job and its children: the coroutine ends at its next suspension point (or when it checks [isActive]). */
    public fun cancel(cause: CancellationException?)

    /** Suspends until the job and all its children are complete. */
    public suspend fun join()

    /** Calls [handler] when the job is complete (immediately when it already is); the argument is the failure or cancellation cause. */
    public fun invokeOnCompletion(handler: (Throwable?) -> Unit): DisposableHandle

    /** Calls [handler] as soon as the job is cancelled or has failed (used by suspension points to resume with the cancellation). */
    public fun invokeOnCancelling(handler: (Throwable) -> Unit): DisposableHandle

    public companion object Key : CoroutineContext.Key<Job>
}

public interface Deferred<out T> : Job {
    public suspend fun await(): T

    /** The value of a completed deferred, which throws its failure. */
    public fun getCompleted(): T
}

public interface CompletableJob : Job {
    public fun complete(): Boolean
    public fun completeExceptionally(exception: Throwable): Boolean
}

public fun Job.cancel() {
    cancel(null)
}

public fun Job(parent: Job? = null): CompletableJob = CompletableJobImpl(parent)

public fun CoroutineContext.cancel(cause: CancellationException? = null) {
    val j = this[Job.Key]
    if (j != null) j.cancel(cause)
}

public val CoroutineContext.isActiveContext: Boolean
    get() {
        val j = this[Job.Key]
        return j == null || j.isActive
    }

/** A registered handler; [dispose] unregisters it and drops the function (which usually refers to the continuation that registered it). */
private class HandlerEntry<T>(var fn: ((T) -> Unit)?) : DisposableHandle {
    var list: ArrayList<HandlerEntry<T>>? = null

    override fun dispose() {
        CoroutinesLock.lock()
        fn = null
        val l = list
        list = null
        if (l != null) l.remove(this)
        CoroutinesLock.unlock()
    }
}

/**
 * The state machine of a job. States: 0 active, 1 cancelling (cancelled or failed, the children and the body are still ending),
 * 2 complete. A job completes when its body has finished and all its children are complete.
 */
public open class JobImpl(parentJob: Job?) : AbstractCoroutineContextElement(Job.Key), Job {
    private var state = 0
    private var bodyFinished = false
    private var cause: Throwable? = null
    private var value: Any? = null
    private val children = ArrayList<JobImpl>()
    private var completionHandlers = ArrayList<HandlerEntry<Throwable?>>()
    private var cancelHandlers = ArrayList<HandlerEntry<Throwable>>()
    private var parentImpl: JobImpl? = null

    /** A coroutine of a scope builder reports its failure to the caller instead of cancelling the parent. */
    protected open val isScopeCoroutine: Boolean get() = false

    init {
        if (parentJob is JobImpl) {
            parentImpl = parentJob
            if (!parentJob.addChild(this)) cancelInternal(CancellationException("Parent job is cancelled"), false)
        }
    }

    public override val isActive: Boolean
        get() {
            CoroutinesLock.lock()
            val r = state == 0
            CoroutinesLock.unlock()
            return r
        }

    public override val isCompleted: Boolean
        get() {
            CoroutinesLock.lock()
            val r = state == 2
            CoroutinesLock.unlock()
            return r
        }

    public override val isCancelled: Boolean
        get() {
            CoroutinesLock.lock()
            val r = state != 0 && cause != null
            CoroutinesLock.unlock()
            return r
        }

    /** the failure or cancellation cause of a job that is cancelling or complete, else null */
    public fun causeOrNull(): Throwable? {
        CoroutinesLock.lock()
        val r = cause
        CoroutinesLock.unlock()
        return r
    }

    /** the value of the body of a completed job without a cause */
    @Suppress("UNCHECKED_CAST")
    protected fun <T> completedValue(): T {
        CoroutinesLock.lock()
        val v = value
        CoroutinesLock.unlock()
        return v as T
    }

    /** is the job complete? The caller holds [CoroutinesLock]. */
    internal fun isCompletedLocked(): Boolean = state == 2

    internal fun hasParent(): Boolean = parentImpl != null

    internal fun addChild(child: JobImpl): Boolean {
        CoroutinesLock.lock()
        if (state != 0) {
            CoroutinesLock.unlock()
            return false
        }
        children.add(child)
        CoroutinesLock.unlock()
        return true
    }

    private fun removeChild(child: JobImpl) {
        CoroutinesLock.lock()
        children.remove(child)
        CoroutinesLock.unlock()
    }

    public override fun cancel(cause: CancellationException?) {
        cancelInternal(cause ?: CancellationException("Job was cancelled"), false)
    }

    /** Starts cancelling: returns false when the job was not active. [fromChild]: the cause is the failure of a child. */
    internal fun cancelInternal(reason: Throwable, fromChild: Boolean): Boolean {
        CoroutinesLock.lock()
        if (state != 0) {
            CoroutinesLock.unlock()
            return false
        }
        state = 1
        cause = reason
        val ch = ArrayList<JobImpl>(children)
        val hs = cancelHandlers
        cancelHandlers = ArrayList<HandlerEntry<Throwable>>()
        CoroutinesLock.unlock()
        val childCause: Throwable = if (reason is CancellationException) reason else CancellationException("Parent job is cancelling", reason)
        for (c in ch) c.cancelInternal(childCause, false)
        for (h in hs) {
            val f = h.fn
            h.list = null
            if (f != null) f(reason)
        }
        tryFinalize()
        return true
    }

    /** a child failed with [e]: this job is cancelled with it (unless it handles failures of children itself) */
    internal fun childFailed(e: Throwable) {
        if (!handlesChildFailure()) return
        cancelInternal(e, true)
    }

    protected open fun handlesChildFailure(): Boolean = true

    /** The body of the coroutine has ended (value or failure). */
    protected fun bodyDone(result: Result<Any?>) {
        val e = result.exceptionOrNull()
        var propagate = false
        CoroutinesLock.lock()
        bodyFinished = true
        var cancelChildren: ArrayList<JobImpl>? = null
        var hs: ArrayList<HandlerEntry<Throwable>>? = null
        if (e == null) {
            value = result.value
        } else if (state == 0) {
            state = 1
            cause = e
            propagate = e !is CancellationException
            cancelChildren = ArrayList<JobImpl>(children)
            hs = cancelHandlers
            cancelHandlers = ArrayList<HandlerEntry<Throwable>>()
        } else if (e !is CancellationException && (cause == null || cause is CancellationException)) {
            // a failure while cancelling is the result of the job
            cause = e
            propagate = true
        }
        CoroutinesLock.unlock()
        if (cancelChildren != null) {
            val cc: Throwable = if (e is CancellationException) e else CancellationException("Parent job is cancelling", e)
            for (c in cancelChildren) c.cancelInternal(cc, false)
        }
        if (hs != null && e != null) {
            for (h in hs) {
                val f = h.fn
                h.list = null
                if (f != null) f(e)
            }
        }
        val p = parentImpl
        if (propagate && e != null && p != null && !isScopeCoroutine) p.childFailed(e)
        tryFinalize()
    }

    private fun tryFinalize() {
        CoroutinesLock.lock()
        if (state == 2 || !bodyFinished || children.isNotEmpty()) {
            CoroutinesLock.unlock()
            return
        }
        state = 2
        val hs = completionHandlers
        completionHandlers = ArrayList<HandlerEntry<Throwable?>>()
        val c = cause
        CoroutinesLock.unlock()
        onFinished(c)
        for (h in hs) {
            val f = h.fn
            h.list = null
            if (f != null) f(c)
        }
        val p = parentImpl
        if (p != null) {
            p.removeChild(this)
            p.tryFinalize()
        }
    }

    /** called once when the job is complete, before the completion handlers run */
    protected open fun onFinished(cause: Throwable?) {}

    public override fun invokeOnCompletion(handler: (Throwable?) -> Unit): DisposableHandle {
        val h = HandlerEntry<Throwable?>(handler)
        CoroutinesLock.lock()
        if (state != 2) {
            completionHandlers.add(h)
            h.list = completionHandlers
            CoroutinesLock.unlock()
            return h
        }
        val c = cause
        CoroutinesLock.unlock()
        handler(c)
        return NoopHandle
    }

    public override fun invokeOnCancelling(handler: (Throwable) -> Unit): DisposableHandle {
        val h = HandlerEntry<Throwable>(handler)
        CoroutinesLock.lock()
        if (state == 0) {
            cancelHandlers.add(h)
            h.list = cancelHandlers
            CoroutinesLock.unlock()
            return h
        }
        val c = cause
        CoroutinesLock.unlock()
        if (c != null) handler(c)
        return NoopHandle
    }

    public override suspend fun join() {
        if (isCompleted) return
        suspendCancellableCoroutine<Unit> { cont ->
            val h = invokeOnCompletion { _ -> cont.resume(Unit) }
            cont.invokeOnCancellation { h.dispose() }
        }
    }

    protected fun completeBodyWithValue(v: Any?) {
        bodyDone(Result.success(v))
    }

    protected fun completeBodyWithFailure(e: Throwable) {
        bodyDone(Result.failure<Any?>(e))
    }
}

private class CompletableJobImpl(parentJob: Job?) : JobImpl(parentJob), CompletableJob {
    override fun complete(): Boolean {
        if (isCompleted) return false
        completeBodyWithValue(null)
        return true
    }

    override fun completeExceptionally(exception: Throwable): Boolean {
        if (isCompleted) return false
        completeBodyWithFailure(exception)
        return true
    }
}

/** A job whose children do not cancel each other: a failing child does not cancel the others (like kotlinx SupervisorJob). */
public fun SupervisorJob(parent: Job? = null): CompletableJob = SupervisorJobImpl(parent)

private class SupervisorJobImpl(parentJob: Job?) : JobImpl(parentJob), CompletableJob {
    override fun handlesChildFailure(): Boolean = false

    override fun complete(): Boolean {
        if (isCompleted) return false
        completeBodyWithValue(null)
        return true
    }

    override fun completeExceptionally(exception: Throwable): Boolean {
        if (isCompleted) return false
        completeBodyWithFailure(exception)
        return true
    }
}

/**
 * A coroutine: a job that is the completion of a suspend block, and the scope of its children. [parentContext] plus this job is
 * its context.
 */
public abstract class AbstractCoroutine<T>(parentContext: CoroutineContext) : JobImpl(parentContext[Job.Key]), Continuation<T>, CoroutineScope {
    // the parent context only: a context that contained this job would be a reference cycle (no cycle collector)
    private val parentCtx: CoroutineContext = parentContext

    public override val context: CoroutineContext get() = parentCtx + this
    public override val coroutineContext: CoroutineContext get() = parentCtx + this

    public override fun resumeWith(result: Result<T>) {
        @Suppress("UNCHECKED_CAST")
        bodyDone(result as Result<Any?>)
    }
}

public interface CoroutineScope {
    public val coroutineContext: CoroutineContext
}

public val CoroutineScope.isActive: Boolean
    get() {
        val j = coroutineContext[Job.Key]
        return j == null || j.isActive
    }

public fun CoroutineScope.cancel(cause: CancellationException? = null) {
    val j = coroutineContext[Job.Key]
    if (j != null) j.cancel(cause)
}

public fun CoroutineScope(context: CoroutineContext): CoroutineScope {
    val withJob = if (context[Job.Key] == null) context + Job() else context
    return ContextScope(withJob)
}

private class ContextScope(override val coroutineContext: CoroutineContext) : CoroutineScope

public object GlobalScope : CoroutineScope {
    public override val coroutineContext: CoroutineContext get() = Dispatchers.Default
}

public operator fun CoroutineScope.plus(context: CoroutineContext): CoroutineScope = ContextScope(coroutineContext + context)

public suspend fun ensureActive() {
    val ctx = currentCoroutineContext()
    val j = ctx[Job.Key]
    if (j != null && !j.isActive) throw CancellationException("Job was cancelled")
}

// ---- suspendCancellableCoroutine ------------------------------------------------------------------------------------------

/**
 * A continuation that the job of its context can cancel: the suspended coroutine is resumed with a [CancellationException].
 * [invokeOnCancellation] registers the clean-up of the operation that was waited for (a timer, a queue entry).
 */
public class CancellableContinuation<T> internal constructor(private val delegate: Continuation<T>) : Continuation<T> {
    // state: 0 pending (block still running), 1 pending (suspended), 2 resumed with a value (before suspending), 3 done
    private var state = 0
    private var cancelHandler: ((Throwable?) -> Unit)? = null
    private var jobHandle: DisposableHandle? = null
    private var earlyResult: Any? = null

    public override val context: CoroutineContext get() = delegate.context

    internal fun attach() {
        val j = delegate.context[Job.Key]
        if (j != null) {
            val h = j.invokeOnCancelling { e -> cancelWith(e) }
            CoroutinesLock.lock()
            if (state == 3) {
                CoroutinesLock.unlock()
                h.dispose()
            } else {
                jobHandle = h
                CoroutinesLock.unlock()
            }
        }
    }

    public fun invokeOnCancellation(handler: (Throwable?) -> Unit) {
        CoroutinesLock.lock()
        if (state == 3) {
            CoroutinesLock.unlock()
            return
        }
        cancelHandler = handler
        CoroutinesLock.unlock()
    }

    public val isActive: Boolean
        get() {
            CoroutinesLock.lock()
            val r = state != 3 && state != 2
            CoroutinesLock.unlock()
            return r
        }

    /** like [isActive], for callers that hold [CoroutinesLock] */
    internal fun isActiveLocked(): Boolean = state != 3 && state != 2

    private fun cancelWith(e: Throwable) {
        CoroutinesLock.lock()
        if (state == 3 || state == 2) {
            CoroutinesLock.unlock()
            return
        }
        val wasSuspended = state == 1
        val h = cancelHandler
        val jh = jobHandle
        cancelHandler = null
        jobHandle = null
        state = if (wasSuspended) 3 else 2
        if (!wasSuspended) earlyResult = Result.failure<Any?>(if (e is CancellationException) e else CancellationException("Job was cancelled", e))
        CoroutinesLock.unlock()
        if (h != null) h(e)
        if (jh != null) jh.dispose()
        if (wasSuspended) delegate.resumeWith(Result.failure<T>(if (e is CancellationException) e else CancellationException("Job was cancelled", e)))
    }

    @Suppress("UNCHECKED_CAST")
    public override fun resumeWith(result: Result<T>) {
        var r: Result<T> = result
        // a cancelled job does not continue with a value
        val j = delegate.context[Job.Key]
        if (j != null && result.isSuccess && !j.isActive) r = Result.failure<T>(CancellationException("Job was cancelled"))
        CoroutinesLock.lock()
        if (state == 3 || state == 2) {
            CoroutinesLock.unlock()
            return
        }
        val wasSuspended = state == 1
        val jh = jobHandle
        jobHandle = null
        cancelHandler = null
        state = if (wasSuspended) 3 else 2
        if (!wasSuspended) earlyResult = r
        CoroutinesLock.unlock()
        if (jh != null) jh.dispose()
        if (wasSuspended) delegate.resumeWith(r)
    }

    /** the value of the suspension point: the result when it was resumed already, else COROUTINE_SUSPENDED */
    @Suppress("UNCHECKED_CAST")
    internal fun getResult(): Any? {
        CoroutinesLock.lock()
        if (state == 0) {
            state = 1
            CoroutinesLock.unlock()
            return COROUTINE_SUSPENDED
        }
        val er = earlyResult as Result<Any?>
        earlyResult = null
        CoroutinesLock.unlock()
        er.throwOnFailureInternal()
        return er.value
    }
}

private fun Result<Any?>.throwOnFailureInternal() {
    val e = exceptionOrNull()
    if (e != null) throw e
}

public suspend fun <T> suspendCancellableCoroutine(block: (CancellableContinuation<T>) -> Unit): T = suspendCoroutineUninterceptedOrReturn { uCont ->
    val cont = CancellableContinuation<T>(uCont.intercepted())
    cont.attach()
    block(cont)
    cont.getResult()
}
