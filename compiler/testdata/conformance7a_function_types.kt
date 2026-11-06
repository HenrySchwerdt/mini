// annotation classes instantiated as values, function types as supertypes (also suspend), suspend conversion of function values,
// coercion of callable references to Unit, `is Function<*>`

import kotlin.coroutines.*

annotation class Tag(val name: String, val n: Int = 1)
annotation class Holder(val tag: Tag)
annotation class Names(vararg val names: String)
annotation class Arr(val a: IntArray)

class Use(val h: Holder)

fun interface Run {
    fun run(): String
}

fun interface SuspendRun {
    suspend fun run(): String
}

var failure: String? = "not run"

class Sus : suspend () -> Int {
    override suspend fun invoke(): Int {
        failure = null
        return 42
    }
}

abstract class AbstractSus : suspend (String) -> Int
class SusImpl : AbstractSus() {
    override suspend fun invoke(p: String): Int = p.length
}

class Two : () -> String, (Int) -> String {
    override fun invoke(): String = "zero"
    override fun invoke(p: Int): String = "one$p"
}

class MyFun : Function<Any>

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
}

suspend fun useRun(r: SuspendRun): String = r.run()
suspend fun useFun(f: suspend (Int) -> String): String = f(5)

fun g(x: Int): Int = x
fun over(x: Int, y: Any): Int = x
fun over(x: Any, y: Int): Int = y

fun main() {
    println(Tag("a") == Tag("a"))
    println(Tag("a") == Tag("b"))
    println(Tag("a").hashCode() == Tag("a").hashCode())
    println(Tag("a", 2).n)
    println(Use(Holder(Tag("deep"))).h.tag.name)
    println(Names("p", "q").names.size)
    println(Names().names.size)
    println(Arr(intArrayOf(1, 2)) == Arr(intArrayOf(1, 2)))
    println(Arr(intArrayOf(1, 2)) == Arr(intArrayOf(1, 3)))

    builder {
        println(Sus().invoke())
    }
    println(failure)
    var len = 0
    builder { len = SusImpl().invoke("hello") }
    println(len)
    var res = ""
    val f: () -> String = { "fun" }
    builder {
        res = useRun(SuspendRun(f))
        res += useFun(Two())
    }
    println(res)

    println(Run { "x" } is Function<*>)
    println(f is Function<*>)
    val h: (Int) -> Unit = ::g
    h(1)
    val o: (Int, Any) -> Unit = ::over
    o(1, "")
    println("done")
}
