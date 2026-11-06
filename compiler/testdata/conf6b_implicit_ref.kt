import kotlin.coroutines.*

var result = ""

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

class A {
    suspend fun memberFunction() {
        result += "A.mf,"
    }

    fun withDefault(a: Int, b: Int = 5): Int = a + b + tag

    val tag = 100

    suspend fun test(): String {
        (::memberFunction).let { it() }
        (::aExtensionFunction).let { it() }
        val f: (Int) -> Int = ::withDefault
        result += f(1).toString() + ","
        return result
    }

    inner class B {
        suspend fun test(): String {
            (::memberFunction).let { it() }
            (::bExtensionFunction).let { it() }
            return result
        }
    }
}

suspend fun A.aExtensionFunction() {
    result += "A.ef,"
}

suspend fun A.B.bExtensionFunction() {
    result += "B.ef,"
}

fun main() {
    builder {
        println(A().test())
        result = ""
        println(A().B().test())
    }
}
