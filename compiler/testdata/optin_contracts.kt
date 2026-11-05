// Opt-in markers of the stdlib, the contract DSL (accepted, no effect), `Continuation(context) { }` and
// an inline function whose receiver is a lambda literal in a super constructor call.
import kotlin.contracts.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.experimental.ExperimentalTypeInference

@OptIn(ExperimentalContracts::class, ExperimentalStdlibApi::class)
fun isString(x: Any?): Boolean {
    contract { returns(true) implies (x is String) }
    return x is String
}

@OptIn(ExperimentalTypeInference::class)
fun <T> build(block: (T) -> Unit, v: T): T {
    block(v)
    return v
}

open class A(val s: Int)

infix fun Int.plusTwice(s: Int): Int = this + s + s

open class B : A({ 1 plusTwice 2 }.let { it() })

suspend fun twice(x: Int): Int = suspendCoroutineUninterceptedOrReturn { c ->
    c.resume(x * 2)
    COROUTINE_SUSPENDED
}

fun main() {
    println(isString("a"))
    println(isString(3))
    println(build({ x: Int -> println("got $x") }, 7))
    println(B().s)
    var result = ""
    val block: suspend () -> String = { "v=" + twice(21) }
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
    println(result)
}
