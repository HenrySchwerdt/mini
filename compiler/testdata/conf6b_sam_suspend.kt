import kotlin.coroutines.*

fun interface SuspendFun {
    suspend fun method(): String
}

fun interface SuspendFun1 {
    suspend fun method(x: Int): String
}

fun <T> runBlocking(c: suspend () -> T): T {
    var res: T? = null
    c.startCoroutine(Continuation(EmptyCoroutineContext) { res = it.getOrThrow() })
    return res!!
}

fun main() {
    // a function value that is not a suspend function, converted to a fun interface with a suspend method
    val impl: () -> String = { "OK" }
    val s = SuspendFun(impl)
    println(runBlocking { s.method() })
    val impl1: (Int) -> String = { "n" + it }
    val s1 = SuspendFun1(impl1)
    println(runBlocking { s1.method(7) })
    // a suspend function value
    val simpl: suspend () -> String = { "S" }
    val s2 = SuspendFun(simpl)
    println(runBlocking { s2.method() })
}
