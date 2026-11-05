import kotlin.coroutines.*

var saved: Continuation<Int>? = null

suspend fun waitInt(): Int = suspendCoroutine { c -> saved = c }

suspend fun sum3(): Int {
    var s = 0
    for (i in 0 until 3) {
        val v = waitInt()
        println("got " + v)
        s += v
    }
    return s
}

suspend fun safe(): String {
    try {
        val v = waitInt()
        if (v < 0) throw IllegalStateException("neg")
        return "ok " + v
    } catch (e: IllegalStateException) {
        return "caught " + e.message
    } finally {
        println("finally")
    }
}

fun run(block: suspend () -> String) {
    block.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<String>) { println("done " + result.getOrNull() + " " + result.exceptionOrNull()) }
    })
}

fun main() {
    run { "sum=" + sum3() }
    saved!!.resume(1)
    saved!!.resume(2)
    saved!!.resume(3)
    run { safe() }
    saved!!.resume(5)
    run { safe() }
    saved!!.resume(-5)
    run { safe() }
    saved!!.resumeWithException(IllegalStateException("boom"))
    run { safe() }
    saved!!.resumeWithException(RuntimeException("other"))
    saved = null
}
