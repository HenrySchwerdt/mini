/*
 * mini.coroutines: coroutine builders and the suspending basics (launch, async, runBlocking, coroutineScope, withContext, delay,
 * withTimeout, yield).
 */
package mini.coroutines

import kotlin.coroutines.*

// ---- launch / async --------------------------------------------------------------------------------------------------------------

private class StandaloneCoroutine(parentContext: CoroutineContext) : AbstractCoroutine<Unit>(parentContext) {
    protected override fun onFinished(cause: Throwable?) {
        // a failure that no parent took over is reported like an uncaught exception of a thread
        if (cause != null && cause !is CancellationException && !hasParent()) reportUncaught(cause)
    }
}

internal fun reportUncaught(e: Throwable) {
    mini.internal.stderrWrite("Exception in coroutine: " + e.toString() + "\n")
}

private class DeferredCoroutine<T>(parentContext: CoroutineContext) : AbstractCoroutine<T>(parentContext), Deferred<T> {
    override suspend fun await(): T {
        join()
        return getCompleted()
    }

    @Suppress("UNCHECKED_CAST")
    override fun getCompleted(): T {
        val e = causeOrNull()
        if (e != null) throw e
        return completedValue<T>()
    }
}

/** Starts a coroutine that runs [block] and returns its [Job]. The coroutine is a child of the job of the scope. */
public fun CoroutineScope.launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> Unit): Job {
    val c = StandaloneCoroutine(coroutineContext + context)
    block.startCoroutine(c, c)
    return c
}

public fun <T> CoroutineScope.async(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): Deferred<T> {
    val c = DeferredCoroutine<T>(coroutineContext + context)
    block.startCoroutine(c, c)
    return c
}

// ---- runBlocking -------------------------------------------------------------------------------------------------------------------

private class BlockingCoroutine<T>(parentContext: CoroutineContext, private val loop: EventLoop) : AbstractCoroutine<T>(parentContext), Deferred<T> {
    override suspend fun await(): T {
        join()
        return getCompleted()
    }

    @Suppress("UNCHECKED_CAST")
    override fun getCompleted(): T {
        val e = causeOrNull()
        if (e != null) throw e
        return completedValue<T>()
    }

    protected override fun onFinished(cause: Throwable?) {
        loop.wake()
    }
}

/** Runs [block] in a new coroutine on an event loop of the calling thread and blocks until it and its children are complete. */
public fun <T> runBlocking(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): T {
    val loop = EventLoop()
    val c = BlockingCoroutine<T>(context + loop, loop)
    block.startCoroutine(c, c)
    loop.runUntil { c.isCompletedLocked() }
    return c.getCompleted()
}

// ---- scopes -------------------------------------------------------------------------------------------------------------------------

/** The coroutine of `coroutineScope` / `withContext` / `withTimeout`: resumes the caller when it and its children are complete. */
private open class ScopeCoroutine<T>(parentContext: CoroutineContext, private val caller: Continuation<T>) : AbstractCoroutine<T>(parentContext) {
    protected override val isScopeCoroutine: Boolean get() = true

    @Suppress("UNCHECKED_CAST")
    protected override fun onFinished(cause: Throwable?) {
        if (cause != null) caller.resumeWith(Result.failure<T>(finalCause(cause))) else caller.resumeWith(Result.success(completedValue<T>()))
    }

    protected open fun finalCause(cause: Throwable): Throwable = cause
}

public suspend fun <T> coroutineScope(@mini.memory.Borrowed block: suspend CoroutineScope.() -> T): T = suspendCoroutine { cont ->
    val c = ScopeCoroutine<T>(cont.context, cont)
    block.startCoroutine(c, c)
}

/** Like [coroutineScope], but a failing child does not cancel the scope or the other children. */
public suspend fun <T> supervisorScope(@mini.memory.Borrowed block: suspend CoroutineScope.() -> T): T = suspendCoroutine { cont ->
    val c = SupervisorScopeCoroutine<T>(cont.context, cont)
    block.startCoroutine(c, c)
}

private class SupervisorScopeCoroutine<T>(parentContext: CoroutineContext, caller: Continuation<T>) : ScopeCoroutine<T>(parentContext, caller) {
    protected override fun handlesChildFailure(): Boolean = false
}

/** Runs [block] in the given context (usually another dispatcher) and returns its result. */
public suspend fun <T> withContext(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T = suspendCoroutine { cont ->
    val c = ScopeCoroutine<T>(cont.context + context, cont)
    block.startCoroutine(c, c)
}

// ---- time -----------------------------------------------------------------------------------------------------------------------------

private fun dispatcherOf(context: CoroutineContext): CoroutineDispatcher? {
    val i = context[ContinuationInterceptor.Key]
    return if (i is CoroutineDispatcher) i else null
}

/** Suspends for [millis] milliseconds without blocking the thread. */
public suspend fun delay(millis: Long) {
    if (millis <= 0L) {
        yield()
        return
    }
    suspendCancellableCoroutine<Unit> { cont ->
        val d = dispatcherOf(cont.context) ?: Dispatchers.Unconfined as CoroutineDispatcher
        val h = d.scheduleAfter(millis) { cont.resume(Unit) }
        cont.invokeOnCancellation { _ -> h.dispose() }
    }
}

/** Lets other coroutines of the dispatcher run before this one continues. */
public suspend fun yield() {
    ensureActive()
    suspendCancellableCoroutine<Unit> { cont ->
        val d = dispatcherOf(cont.context)
        if (d == null) cont.resume(Unit) else d.dispatch { cont.resume(Unit) }
    }
}

private class TimeoutCoroutine<T>(parentContext: CoroutineContext, caller: Continuation<T>, private val millis: Long) : ScopeCoroutine<T>(parentContext, caller) {
    private var timer: DisposableHandle? = null

    fun arm(d: CoroutineDispatcher) {
        val me = this
        timer = d.scheduleAfter(millis) { me.cancelInternal(TimeoutCancellationException("Timed out waiting for " + millis + " ms"), false) }
    }

    protected override fun finalCause(cause: Throwable): Throwable {
        val t = timer
        if (t != null) t.dispose()
        return cause
    }

    protected override fun onFinished(cause: Throwable?) {
        val t = timer
        if (t != null) t.dispose()
        super.onFinished(cause)
    }
}

/** Runs [block]; when it takes longer than [millis] milliseconds it is cancelled and a [TimeoutCancellationException] is thrown. */
public suspend fun <T> withTimeout(millis: Long, @mini.memory.Borrowed block: suspend CoroutineScope.() -> T): T = suspendCoroutine { cont ->
    val c = TimeoutCoroutine<T>(cont.context, cont, millis)
    val d = dispatcherOf(cont.context) ?: Dispatchers.Unconfined as CoroutineDispatcher
    c.arm(d)
    block.startCoroutine(c, c)
}

public suspend fun <T> withTimeoutOrNull(millis: Long, @mini.memory.Borrowed block: suspend CoroutineScope.() -> T): T? {
    try {
        return withTimeout(millis, block)
    } catch (e: TimeoutCancellationException) {
        return null
    }
}
