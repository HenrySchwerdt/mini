import kotlin.coroutines.*

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

suspend fun id(s: String) = s
suspend fun String.idExt() = this
suspend inline fun Long.longArgs(a: Long, b: Long, c: Long): Long = this + a + b + c
suspend fun run2(block: suspend () -> String) = block()

suspend fun ok(o: String): String {
    return o + run2 {
        if (o != "K") {
            (::ok).let { it("K") }
        } else ""
    }
}

fun main() {
    builder {
        // a reference to a suspend function without an expected function type is a suspend function value
        val f = ::id
        println(f("A"))
        println((::id).let { it("B") })
        val g = "C"::idExt
        println(g())
        val ref = 1L::longArgs
        println(ref(1L, 1L, 1L))
        println(ok("O"))
    }
}
