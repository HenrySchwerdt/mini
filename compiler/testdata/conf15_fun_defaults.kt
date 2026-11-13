// default arguments referring to earlier parameters, defaults with side effects, defaults in overrides and constructors
var calls = 0
fun next(): Int = ++calls
fun f(a: Int, b: Int = a * 2, c: Int = a + b, d: String = "$a-$b-$c") = d
fun g(x: Int = next(), y: Int = next()) = "$x,$y"
fun h(list: List<Int> = emptyList(), n: Int = list.size) = n
fun k(a: String, b: String = a.uppercase(), c: () -> String = { a + b }) = c()
open class Base { open fun m(x: Int = 1, y: Int = 2) = "Base " + x + y }
class Der : Base() { override fun m(x: Int, y: Int) = "Der " + x + y }
class Cfg(val host: String = "localhost", val port: Int = host.length, val url: String = "$host:$port")
fun withVararg(prefix: String = "p", vararg xs: Int) = prefix + xs.sum()
fun lambdaDefault(f: (Int) -> Int = { it + 1 }) = f(10)
fun nullDefault(s: String? = null, t: String = s ?: "none") = t
fun main() {
    println(f(1)); println(f(1, 5)); println(f(1, c = 0)); println(f(2, d = "x")); println(f(a = 3, c = 1))
    println(g()); println(g(10)); println(g(y = 7)); println(g(y = 1, x = 2)); println(calls)
    println(h()); println(h(listOf(1, 2))); println(h(n = 5)); println(k("ab")); println(k("ab", "X")); println(k("q") { "lam" })
    println(Base().m()); println(Der().m()); println(Der().m(5)); println((Der() as Base).m(y = 9))
    val c = Cfg(); println(c.url); println(Cfg("example").url); println(Cfg(port = 80).url); println(Cfg("a", 1, "z").url)
    println(withVararg()); println(withVararg("q", 1, 2, 3)); println(withVararg(xs = intArrayOf(4, 5))); println(lambdaDefault()); println(lambdaDefault { it * 3 })
    println(nullDefault()); println(nullDefault("s")); println(nullDefault(t = "t"))
}
