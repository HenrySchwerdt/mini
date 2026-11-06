// Regressions found by the Kotlin box tests (docs/conformance.md, fifth round, a): `field++`, `(label@ x)++`, createCoroutine.
import kotlin.coroutines.*

class TestObject {
    companion object {
        var prop: Int = 1
            get() = field++
        var pre: Int = 10
            get() = ++field
    }
}

fun labelledInc(): Int {
    var x = 1
    (foo@ x)++
    ++(foo@ x)
    return x
}

object Done : Continuation<Unit> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<Unit>) {}
}

fun twice(): String {
    var result = ""
    val c: suspend () -> Unit = { result = "ran" }
    val x = c.createCoroutine(Done)
    x.resume(Unit)
    try {
        x.resume(Unit)
        return "no exception"
    } catch (e: IllegalStateException) {
        return result + " " + e.message
    }
}

fun failFresh(): String {
    var exception: Throwable? = null
    var ran = false
    val c: suspend () -> Unit = { ran = true }
    c.createCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            exception = result.exceptionOrNull()
        }
    }).resumeWithException(RuntimeException("boom"))
    return exception?.message + " " + ran
}

fun main() {
    println(TestObject.prop)
    println(TestObject.prop)
    println(TestObject.pre)
    println(TestObject.pre)
    println(labelledInc())
    println(twice())
    println(failFresh())
}
