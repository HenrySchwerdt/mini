import kotlin.coroutines.*

suspend fun twice(x: Int): Int = suspendCoroutine { c -> c.resume(x * 2) }
suspend inline fun <T> myInline(b: suspend () -> T): T = b()
inline fun <T> plain(b: () -> T): T = b()
class Box(val v: Int) { suspend fun get(): Int = twice(v) }
suspend fun fold(): Int {
    var s = 0
    (1..3).forEach { s += twice(it) }
    listOf(1, 2).forEachIndexed { i, x -> s += twice(x) * i }
    val m = mapOf(1 to 2).entries.map { twice(it.key + it.value) }
    s += m.sum()
    s += plain { twice(5) }
    s += myInline { twice(1) }
    s += listOf(Box(2), Box(3)).sumOf { it.get() }
    s += try { plain { twice(9) } } finally { twice(0) }
    s += listOf(1, 2, 3).filter { twice(it) > 2 }.size
    s += listOf(3, 1, 2).sortedBy { it }.map { twice(it) }.first()
    val r = lazy { 4 }.value
    s += "abc".map { twice(it.code) }.size
    s += buildList { for (i in 0..2) add(twice(i)) }.size
    return s + r
}
tailrec suspend fun down(n: Int, acc: Int = 0): Int = when { n == 0 -> acc; n % 2 == 0 -> down(n - 1, acc + 1); else -> down(n - 1, acc + twice(1)) }
suspend fun cc(): String = coroutineContext.toString().length.toString() + (coroutineContext === EmptyCoroutineContext)
fun <T> runIt(b: suspend () -> T): T {
    var r: Result<T>? = null
    b.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}
fun main() {
    println(runIt { fold() })
    println(runIt { down(1000001) })
    println(runIt { cc() })
    val f: suspend (Int) -> Int = ::twice
    println(runIt { f(4) })
    val g: suspend Box.() -> Int = Box::get
    println(runIt { Box(6).g() })
    val h: suspend () -> Int = Box(7)::get
    println(runIt { h() })
}
