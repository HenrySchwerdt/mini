// Conformance round 9 (a), coroutines: the interceptor gets back the continuation it made when the coroutine completes
// (`releaseInterceptedContinuation`), `a?.suspendUnitFun()` is the Unit object (not null) after the suspension, a Unit suspend function
// completes with the Unit object (`runBlocking { suspendUnit() }` with `res!!`), `x is T` for a reified suspend function type.
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

var c: Continuation<String>? = null
var interceptions = 0
var releases = 0

suspend fun suspendHere(): String = suspendCoroutineUninterceptedOrReturn { x ->
    c = x
    COROUTINE_SUSPENDED
}

class Dispatcher : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = Wrapped(continuation)

    override fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        releases++
    }
}

class Wrapped<T>(val continuation: Continuation<T>) : Continuation<T> {
    init {
        interceptions++
    }

    override val context: CoroutineContext = continuation.context

    override fun resumeWith(result: Result<T>) {
        continuation.resumeWith(result)
    }
}

fun builder(block: suspend () -> String) {
    block.startCoroutineUninterceptedOrReturn(object : Continuation<String> {
        override val context: CoroutineContext get() = Dispatcher()
        override fun resumeWith(result: Result<String>) {
            println("result " + result.getOrThrow())
        }
    })
}

class A(val x: String) {
    suspend fun foo(): Unit {}
}

suspend fun suspendUnit() {}

fun <T> runBlocking(block: suspend () -> T): T {
    var res: T? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { res = it.getOrThrow() })
    return res!!
}

inline fun <reified T : suspend () -> Unit> isSuspendFn(noinline x: (Any?) -> Unit): Boolean = x is T

fun main() {
    builder { suspendHere() }
    println("interceptions=$interceptions releases=$releases")
    c = c?.intercepted()
    println("interceptions=$interceptions releases=$releases")
    c?.resumeWith(Result.success("OK"))
    println("interceptions=$interceptions releases=$releases")

    val a1: A? = A("x")
    val a2: A? = null
    runBlocking {
        println(a1?.foo())
        println(a2?.foo())
    }
    println(runBlocking { suspendUnit() })
    println(isSuspendFn<suspend () -> Unit> {})
}
