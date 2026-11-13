import kotlin.coroutines.startCoroutine
fun f(a: Int, b: String = "d", vararg c: Int): String = "$a$b${c.toList()}"
fun g(a: Int): Int = a + 1
fun h(a: Int, b: Int): Unit { println(a + b) }
suspend fun sus(a: Int): Int = a * 3
fun takes(fn: (Int) -> String) = fn(1)
fun takes2(fn: (Int, String) -> String) = fn(1, "x")
fun takes3(fn: (Int, String, Int, Int) -> String) = fn(1, "x", 2, 3)
fun unit(fn: (Int) -> Unit) = fn(5)
fun unit2(fn: (Int, Int) -> Unit) = fn(5, 6)
fun susTake(fn: suspend (Int) -> Int) = kotlin.coroutines.EmptyCoroutineContext
class C { fun m(a: Int = 4): Int = a }
fun String.ext(x: Int = 1) = this + x
fun main() {
    println(takes(::f)); println(takes2(::f)); println(takes3(::f))
    unit(::g); unit2(::h)
    val c = C()
    val r: () -> Int = c::m
    println(r())
    println(run { val q: String.() -> String = String::ext; "a".q() })
    var res = 0
    val cont = object : kotlin.coroutines.Continuation<Int> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(r: Result<Int>) { res = r.getOrThrow() }
    }
    val s: suspend (Int) -> Int = ::sus
    s.startCoroutine2(4, cont)
    println(res)
}
fun <T> (suspend (Int) -> T).startCoroutine2(a: Int, c: kotlin.coroutines.Continuation<T>) {
    val f = this
    (suspend { f(a) }).startCoroutine(c)
}
