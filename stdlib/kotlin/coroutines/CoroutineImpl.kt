package kotlin.coroutines

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

import kotlin.coroutines.intrinsics.SuspendMarker

/** The result of [BaseContinuation.resumeRaw] when the coroutine has completed (its last completion is not a state machine). */
public object CoroutineCompleted

/** The part of a state machine that is the same for all of them: completing the parent continuation. */
public abstract class BaseContinuation(public val completion: Continuation<Any?>) : Continuation<Any?> {
    public override val context: CoroutineContext get() = completion.context

    public abstract fun invokeSuspend(result: Any?): Any?

    /** The continuation to resume from outside: wrapped by the interceptor (dispatcher) of the context, if there is one. */
    public fun interceptedContinuation(): Continuation<Any?> {
        val interceptor = context[ContinuationInterceptor.Key] ?: return this
        // not cached: the wrapper refers to this object, so keeping it here would be a reference cycle
        return interceptor.interceptContinuation(this)
    }

    public override fun resumeWith(result: Result<Any?>) {
        resumeRaw(result)
    }

    /**
     * Resumes this state machine and, when it completes, its parents that are state machines too (a loop, not recursion).
     * Returns the suspension object of the code that suspended (COROUTINE_SUSPENDED or a request), or [CoroutineCompleted]
     * after the first continuation that is not a state machine has been resumed with the final result.
     */
    public fun resumeRaw(result: Result<Any?>): Any? {
        var cur: BaseContinuation = this
        var res = result
        while (true) {
            var out: Any? = null
            var failure: Throwable? = null
            try {
                out = cur.invokeSuspend(res)
            } catch (e: Throwable) {
                failure = e
            }
            if (failure == null && out is SuspendMarker) return out
            val parent = cur.completion
            res = if (failure != null) Result.failure<Any?>(failure) else Result.success(out)
            if (parent is BaseContinuation) {
                cur = parent
            } else {
                parent.resumeWith(res)
                return CoroutineCompleted
            }
        }
    }
}

/**
 * The continuation of a suspend function with suspension points: [step] is the state machine (a lambda built by the
 * compiler that captures the locals of the function), called with this object and the result of the call that suspended.
 */
public class CoroutineImpl(completion: Continuation<Any?>, private val step: (CoroutineImpl, Any?) -> Any?) : BaseContinuation(completion) {
    public override fun invokeSuspend(result: Any?): Any? = step(this, result)

    /** the first run of the state machine: the value of the function, or COROUTINE_SUSPENDED */
    public fun start(): Any? = step(this, null)

    /** the value of the call that suspended: throws when it failed */
    @Suppress("UNCHECKED_CAST")
    public fun resumeValue(r: Any?): Any? {
        val res = r as Result<Any?>
        res.throwOnFailure()
        return res.value
    }

    public fun isSuspended(v: Any?): Boolean = v is SuspendMarker
}

/** `createCoroutine`: runs the block once when resumed, completing the completion with its result. */
internal class SafeStarter(completion: Continuation<Any?>, private val block: (Continuation<Any?>) -> Any?) : BaseContinuation(completion) {
    private var started = false

    @Suppress("UNCHECKED_CAST")
    public override fun invokeSuspend(result: Any?): Any? {
        if (started) {
            val res = result as Result<Any?>
            res.throwOnFailure()
            return res.value
        }
        // resumeWithException on a coroutine that was never started fails it without running the block
        (result as Result<Any?>).throwOnFailure()
        started = true
        return block(this)
    }
}

/** The continuation `createCoroutine` returns: it can be resumed once (a second resume is an IllegalStateException, like SafeContinuation). */
internal class OnceContinuation(private val inner: Continuation<Any?>) : Continuation<Any?> {
    private var used = false

    public override val context: CoroutineContext get() = inner.context

    public override fun resumeWith(result: Result<Any?>) {
        if (used) throw IllegalStateException("Already resumed")
        used = true
        inner.resumeWith(result)
    }
}

private object Undecided
private object Done

internal class SafeContinuation<T>(private val delegate: Continuation<T>) : Continuation<T> {
    // Undecided, then a result (resumed before the block returned), SUSPENDED (the block returned first) or Done
    private var state: Any? = Undecided
    private var failure: Throwable? = null

    public override val context: CoroutineContext get() = delegate.context

    public override fun resumeWith(result: Result<T>) {
        if (state === Undecided) {
            val ex = result.exceptionOrNull()
            if (ex != null) {
                failure = ex
                state = Done
            } else {
                state = result.value
            }
            return
        }
        if (state === COROUTINE_SUSPENDED) {
            state = Done
            delegate.resumeWith(result)
            return
        }
        throw IllegalStateException("Already resumed")
    }

    public fun getOrThrow(): Any? {
        if (state === Undecided) {
            state = COROUTINE_SUSPENDED
            return COROUTINE_SUSPENDED
        }
        val f = failure
        if (f != null) throw f
        return state
    }
}
