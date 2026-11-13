// typealias (incl. generic and function types), fun interface SAM conversion, destructuring declarations, Nothing?, Any methods
typealias Pred<T> = (T) -> Boolean
typealias StrMap = Map<String, List<Int>>
typealias Point = Pair<Int, Int>
typealias Handler = (String, Int) -> String
fun interface Transformer { fun apply(s: String): String }
fun interface IntOp { fun op(a: Int, b: Int): Int }
fun runT(t: Transformer, s: String) = t.apply(s)
fun <T> filterBy(l: List<T>, p: Pred<T>) = l.filter(p)
fun origin(): Point = 0 to 0
fun total(m: StrMap) = m.values.sumOf { it.sum() }
object Upper : Transformer { override fun apply(s: String) = s.uppercase() }
fun main() {
    println(filterBy(listOf(1, 2, 3, 4)) { it % 2 == 0 }); println(total(mapOf("a" to listOf(1, 2), "b" to listOf(3)))); println(origin()); val h: Handler = { s, n -> s.repeat(n) }; println(h("ab", 2))
    println(runT({ it + "!" }, "hi")); println(runT(Upper, "x")); val t = Transformer { it.reversed() }; println(t.apply("abc")); val add = IntOp { a, b -> a + b }; println(add.op(1, 2)); println(IntOp { a, b -> a * b }.op(3, 4))
    val (a, b) = origin(); println(a + b); val (x, y) = Point(3, 4); println(x * y); val list = listOf(Point(1, 2), Point(3, 4)); println(list.map { (p, q) -> p * q }); println(list.sumOf { it.first })
    val n: Nothing? = null; println(n); println(n?.toString()); val anyN: Any? = n; println(anyN == null)
    val o: Any = "str"; println(o.toString()); println(o.hashCode() == "str".hashCode()); println(o.equals("str")); println(o::class.simpleName); println(o is Comparable<*>); println(o is CharSequence); println(1 as Any is Number)
    val fnType: (Int) -> (Int) -> Int = { a -> { b -> a - b } }; println(fnType(10)(3)); val nullableFn: ((Int) -> Int)? = null; println(nullableFn?.invoke(1)); val s: Pred<String> = { it.isEmpty() }; println(s("")); println(s("a"))
}
