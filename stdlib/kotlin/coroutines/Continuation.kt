/*
 * Mini coroutines (docs/coroutines.md). The shape of kotlin-stdlib kotlin.coroutines; the state machines of suspend
 * functions are built by the compiler (compiler/lower/src/SuspendLowering.kt) on top of CoroutineImpl.
 */
package kotlin.coroutines

public interface Continuation<in T> {
    public val context: CoroutineContext
    public fun resumeWith(result: Result<T>)
}

public fun <T> Continuation<T>.resume(value: T) {
    resumeWith(Result.success(value))
}

public fun <T> Continuation<T>.resumeWithException(exception: Throwable) {
    resumeWith(Result.failure<T>(exception))
}

public interface CoroutineContext {
    public operator fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E?
    public fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R
    public operator fun plus(context: CoroutineContext): CoroutineContext {
        if (context === EmptyCoroutineContext) return this
        return context.fold(this) { acc, element ->
            val removed = acc.minusKey(element.key)
            if (removed === EmptyCoroutineContext) {
                element
            } else {
                val interceptor = removed[ContinuationInterceptor.Key]
                if (interceptor == null) {
                    CombinedContext(removed, element)
                } else {
                    val left = removed.minusKey(ContinuationInterceptor.Key)
                    if (left === EmptyCoroutineContext) CombinedContext(element, interceptor)
                    else CombinedContext(CombinedContext(left, element), interceptor)
                }
            }
        }
    }
    public fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext

    public interface Key<E : CoroutineContext.Element>

    public interface Element : CoroutineContext {
        public val key: CoroutineContext.Key<*>

        @Suppress("UNCHECKED_CAST")
        public override operator fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
            if (this.key === key) this as E else null

        public override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(initial, this)

        public override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
            if (this.key === key) EmptyCoroutineContext else this
    }
}

public object EmptyCoroutineContext : CoroutineContext {
    public override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = null
    public override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = initial
    public override fun plus(context: CoroutineContext): CoroutineContext = context
    public override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = this
    public override fun hashCode(): Int = 0
    public override fun toString(): String = "EmptyCoroutineContext"
}

internal class CombinedContext(private val left: CoroutineContext, private val element: CoroutineContext.Element) : CoroutineContext {
    @Suppress("UNCHECKED_CAST")
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        if (element.key === key) return element as E
        return left[key]
    }

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(left.fold(initial, operation), element)

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        if (element.key === key) return left
        val newLeft = left.minusKey(key)
        return if (newLeft === left) this else if (newLeft === EmptyCoroutineContext) element else CombinedContext(newLeft, element)
    }

    override fun toString(): String = "[" + left.toString() + ", " + element.toString() + "]"
}

public abstract class AbstractCoroutineContextElement(public override val key: CoroutineContext.Key<*>) : CoroutineContext.Element

public interface ContinuationInterceptor : CoroutineContext.Element {
    public fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T>
    public fun releaseInterceptedContinuation(continuation: Continuation<*>) {}

    public companion object Key : CoroutineContext.Key<ContinuationInterceptor>
}

public fun <T> Continuation<T>.intercepted(): Continuation<T> {
    if (this is BaseContinuation) {
        @Suppress("UNCHECKED_CAST")
        return interceptedContinuation() as Continuation<T>
    }
    return this
}

@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (Continuation<T>) -> Any?)(c as Continuation<T>) }).intercepted() as Continuation<Unit>

@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (R, Continuation<T>) -> Any?)(receiver, c as Continuation<T>) }).intercepted() as Continuation<Unit>

public fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>) {
    createCoroutine(completion).resume(Unit)
}

public fun <R, T> (suspend R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>) {
    createCoroutine(receiver, completion).resume(Unit)
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

public suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T = suspendCoroutineUninterceptedOrReturn { c ->
    val safe = SafeContinuation(c.intercepted())
    block(safe)
    safe.getOrThrow()
}

/** The compiler lowers a call of this function to `block(<current continuation>)`, which may return COROUTINE_SUSPENDED. */
public suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T {
    throw IllegalStateException("suspendCoroutineUninterceptedOrReturn is lowered by the compiler")
}

public suspend fun currentCoroutineContext(): CoroutineContext = suspendCoroutineUninterceptedOrReturn { c -> c.context }
