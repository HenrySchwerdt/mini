import kotlin.coroutines.*

// `recv.f(args)` where f is a value of type Recv.(A, B) -> R: literal arguments take the type of their own parameter
fun foo5(c: Int.(Long, Int) -> String) = 1.c(56L, 55)
fun foo7(c: Int.(Int, Long) -> String) = c(1, 56, 55)
suspend fun foo8(c: suspend String.(Long, Int) -> String) = "r".c(56L, 55)

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

fun main() {
    println(foo5 { i, j -> "$this#$i#$j" })
    println(foo7 { i, j -> "$this#$i#$j" })
    builder {
        println(foo8 { l, i -> "$this#$l#$i" })
    }
}
