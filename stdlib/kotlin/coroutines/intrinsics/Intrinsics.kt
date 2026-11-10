package kotlin.coroutines.intrinsics

import kotlin.coroutines.*

/**
 * What a suspend function returns instead of its value when it has suspended: COROUTINE_SUSPENDED, or an object of a
 * subclass that carries a request to the code that resumes the coroutine (the sequence builders hand the yielded value
 * up this way, so that no object points back from the coroutine to its driver: there are no reference cycles).
 */
public abstract class SuspendMarker

private object CoroutineSuspendedMarker : SuspendMarker() {
    override fun toString(): String = "COROUTINE_SUSPENDED"
}

/** The value a suspend function returns when it has suspended. */
public val COROUTINE_SUSPENDED: Any = CoroutineSuspendedMarker

public fun <T> Continuation<T>.intercepted(): Continuation<T> {
    if (this is BaseContinuation) {
        @Suppress("UNCHECKED_CAST")
        return interceptedContinuation() as Continuation<T>
    }
    return this
}

@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (Continuation<T>) -> Any?)(c as Continuation<T>) }) as Continuation<Unit>

@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(receiver: R, completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (R, Continuation<T>) -> Any?)(receiver, c as Continuation<T>) }) as Continuation<Unit>

/** Runs the block as a suspend function with [completion] as its continuation; the result is the value or COROUTINE_SUSPENDED. */
@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any? =
    (this as (Continuation<T>) -> Any?)(completion)

@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(receiver: R, completion: Continuation<T>): Any? =
    (this as (R, Continuation<T>) -> Any?)(receiver, completion)

@Suppress("UNCHECKED_CAST")
internal fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(receiver: R, param: P, completion: Continuation<T>): Any? =
    (this as (R, P, Continuation<T>) -> Any?)(receiver, param, completion)

/** The compiler lowers a call of this function to `block(<current continuation>)`, which may return COROUTINE_SUSPENDED. */
public suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T {
    throw IllegalStateException("suspendCoroutineUninterceptedOrReturn is lowered by the compiler")
}
