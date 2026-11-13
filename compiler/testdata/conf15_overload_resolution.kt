// overload resolution: most specific, nullable vs non-null, vararg vs fixed, default vs explicit, generic vs concrete, Int/Long/Double literals
fun f(x: Int) = "Int"
fun f(x: Long) = "Long"
fun f(x: Double) = "Double"
fun f(x: Any) = "Any"
fun f(x: String) = "String"
fun g(x: Any) = "Any"
fun g(x: String) = "String"
fun g(x: CharSequence) = "CharSequence"
fun h(vararg x: Int) = "vararg"
fun h(x: Int) = "single"
fun h(x: Int, y: Int) = "two"
fun k(a: Int, b: Int = 0) = "default"
fun k(a: Int) = "exact"
fun <T> m(x: T) = "generic"
fun m(x: Int) = "int"
fun n(x: List<Int>) = "list"
fun n(x: Collection<Int>) = "collection"
fun n(x: Iterable<Int>) = "iterable"
fun o(f: () -> Unit) = "fn0"
fun o(f: (Int) -> Unit) = "fn1"


fun q(a: Int, b: Long) = "IL"
fun q(a: Long, b: Int) = "LI"
class C { fun r(x: Int) = "member"; }
fun C.r(x: Int) = "ext"
fun C.r(x: String) = "ext-str"
fun main() {
    println(f(1) + f(1L) + f(1.0) + f("s") + f(1f) + f('c') + f(true))
    println(g("s") + g(StringBuilder()) + g(1)); println(h(1) + h(1, 2) + h() + h(1, 2, 3)); println(k(1) + k(1, 2)); println(m(1) + m("s") + m(1L))
    println(n(listOf(1)) + n(setOf(1)) + n(1..3)); println(o(fun() {}) + o { _: Int -> }); println(q(1, 2L) + q(1L, 2)); println(C().r(1) + C().r("s"))
    val b: Byte = 1; val s: Short = 2; println(f(b.toInt()) + f(s.toInt()) + f(b.toLong())); println(f(1 + 1) + f(1L + 1) + f(1 + 1.0) + f(1.toDouble())); println(f(0x10) + f(0x10L) + f(1e1) + f(1_0))
    val any: Any = 1; println(f(any)); val str: String = "x"; println(f(str)); val cs: CharSequence = "x"; println(g(cs));
}
