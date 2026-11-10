/*
 * Mini coroutines (docs/coroutines.md). The shape of kotlin-stdlib kotlin.coroutines; the state machines of suspend
 * functions are built by the compiler (compiler/lower/src/SuspendLowering.kt) on top of CoroutineImpl.
 */
package kotlin.coroutines

import kotlin.coroutines.intrinsics.*

public interface Continuation<in T> {
    public val context: CoroutineContext
    public fun resumeWith(result: Result<T>)
}

/** Creates a [Continuation] with the given [context] that passes the result to [resumeWith]. */
public fun <T> Continuation(context: CoroutineContext, resumeWith: (Result<T>) -> Unit): Continuation<T> =
    object : Continuation<T> {
        override val context: CoroutineContext = context
        override fun resumeWith(result: Result<T>) = resumeWith(result)
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
        val e = element[key]
        if (e != null) return e
        return left[key]
    }

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(left.fold(initial, operation), element)

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        @Suppress("UNCHECKED_CAST")
        if (element[key as CoroutineContext.Key<CoroutineContext.Element>] != null) return left
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

@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (Continuation<T>) -> Any?)(c as Continuation<T>) }).intercepted().let { OnceContinuation(it) as Continuation<Unit> }

@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit> =
    SafeStarter(completion as Continuation<Any?>, { c -> (this as (R, Continuation<T>) -> Any?)(receiver, c as Continuation<T>) }).intercepted().let { OnceContinuation(it) as Continuation<Unit> }

public fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>) {
    createCoroutine(completion).resume(Unit)
}

public fun <R, T> (suspend R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>) {
    createCoroutine(receiver, completion).resume(Unit)
}

public suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T = suspendCoroutineUninterceptedOrReturn { c ->
    val safe = SafeContinuation(c.intercepted())
    block(safe)
    safe.getOrThrow()
}

public suspend fun currentCoroutineContext(): CoroutineContext = suspendCoroutineUninterceptedOrReturn { c -> c.context }

/** The compiler replaces a call inside a suspend function by that function's continuation (used by [coroutineContext]). */
internal fun currentContinuationIntrinsic(): Continuation<Any?> = throw IllegalStateException("currentContinuationIntrinsic is lowered by the compiler")

/** The context of the coroutine that calls this suspend property. */
public suspend val coroutineContext: CoroutineContext
    get() = currentContinuationIntrinsic().context

/**
 * A key of a polymorphic element: [safeCast] converts an element with the key [baseKey] (or any key derived from it) to [E].
 */
public abstract class AbstractCoroutineContextKey<B : CoroutineContext.Element, E : B>(
    baseKey: CoroutineContext.Key<B>,
    private val safeCast: (element: CoroutineContext.Element) -> E?
) : CoroutineContext.Key<E> {
    private val topmostKey: CoroutineContext.Key<*> = if (baseKey is AbstractCoroutineContextKey<*, *>) baseKey.topmostKey else baseKey

    internal fun tryCast(element: CoroutineContext.Element): E? = safeCast(element)
    internal fun isSubKey(key: CoroutineContext.Key<*>): Boolean = key === this || topmostKey === key
}

/** The implementation of [CoroutineContext.Element.get] for elements with polymorphic keys. */
public fun <E : CoroutineContext.Element> CoroutineContext.Element.getPolymorphicElement(key: CoroutineContext.Key<E>): E? {
    if (key is AbstractCoroutineContextKey<*, *>) {
        @Suppress("UNCHECKED_CAST")
        return if (key.isSubKey(this.key)) key.tryCast(this) as? E else null
    }
    @Suppress("UNCHECKED_CAST")
    return if (this.key === key) this as E else null
}

/** The implementation of [CoroutineContext.Element.minusKey] for elements with polymorphic keys. */
public fun CoroutineContext.Element.minusPolymorphicKey(key: CoroutineContext.Key<*>): CoroutineContext {
    if (key is AbstractCoroutineContextKey<*, *>) {
        return if (key.isSubKey(this.key) && key.tryCast(this) != null) EmptyCoroutineContext else this
    }
    return if (this.key === key) EmptyCoroutineContext else this
}
