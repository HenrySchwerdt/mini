import kotlin.coroutines.*

// `recv.p(args)` where p is a member property of type Recv.(..) -> R: p comes from `this`, recv is the first argument
class Holder<T>(private val block: T.(Int) -> String, val name: String) {
    fun run(x: T): String = x.block(3) + "/" + name
}

class Box(val v: Int)

fun interface Collector<in T> {
    suspend fun emit(value: T)
}

class Source<T>(private val block: suspend Collector<T>.() -> Unit) {
    suspend fun collect(c: Collector<T>) {
        c.block()
    }
}

fun main() {
    val h = Holder<Box>({ n -> "v" + (v + n) }, "h")
    println(h.run(Box(10)))
    val got = ArrayList<Int>()
    val s = Source<Int> { emit(1); emit(2) }
    val f: suspend () -> Unit = { s.collect { got.add(it) } }
    f.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
    println(got)
}
