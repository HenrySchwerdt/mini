fun f(a: Int, b: String = "d", vararg c: Int): String = "$a$b${c.toList()}"
fun v(vararg x: Int): Int = x.sum()
fun d(a: Int, b: Int = 10) = a + b
class P(val a: Int, val b: Int = 2)
fun <T> id(t: T) = t
fun u(): Int = 5
fun apply2(fn: (Int, Int) -> Int) = fn(1, 2)
fun app0(fn: () -> Unit) = fn()
fun app1(fn: (Int) -> Int) = fn(3)
fun appS(fn: (String) -> Unit) = fn("s")
fun printIt(s: String): Int { println(s); return 1 }
enum class E { A, B; fun nm(prefix: String = "") = prefix + name }
object O { fun q(x: Int, y: Int = 1) = x * y }
fun main() {
    println(apply2(::v)); println(apply2(::d)); println(app1(::d)); println(app1(::v))
    app0(::u); appS(::printIt)
    val pc: (Int) -> P = ::P
    println(pc(1).b)
    println(app1(O::q))
    val e: (E) -> String = E::nm
    println(e(E.B))
    val ids: (Int) -> Int = ::id
    println(ids(8))
    println(listOf(1, 2, 3).map(::id))
    println(listOf("a").map(String::length))
    val kf = ::d
    println(kf.name)
    println(listOf(1,2).fold(0, ::d))
}
