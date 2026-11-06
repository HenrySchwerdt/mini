// Suspend conversion: a reference to a non-suspend function where a suspend function type is expected.
import kotlin.coroutines.*

fun plain(x: Int): Int = x + 1
fun twice(a: Int, b: Int = 2): Int = a * b

suspend fun run1(f: suspend (Int) -> Int, v: Int): Int = f(v)
suspend fun run0(f: suspend () -> String): String = f()

fun hello(): String = "hello"

fun launch(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
    })
}

fun main() {
    launch {
        println(run1(::plain, 41))
        println(run0(::hello))
        println(run1(::twice, 21))
    }
}
