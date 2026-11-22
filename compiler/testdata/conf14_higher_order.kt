fun apply2(f: (Int) -> Int, x: Int) = f(f(x))
fun adder(n: Int): (Int) -> Int = { it + n }
fun curry(f: (Int, Int) -> Int): (Int) -> (Int) -> Int = { a -> { b -> f(a, b) } }
fun <T> repeatApply(n: Int, x: T, f: (T) -> T): T { var r = x; repeat(n) { r = f(r) }; return r }
val sq: (Int) -> Int = { it * it }
val add: (Int, Int) -> Int = { a, b -> a + b }
val nullary: () -> String = { "none" }
val withRecv: String.(Int) -> String = { repeat(it) }
fun isEven(n: Int) = n % 2 == 0

fun main() {
    println(apply2(sq, 3))
    println(adder(5)(6))
    println(curry(add)(2)(3))
    println(repeatApply(4, 1) { it * 2 })
    println(nullary())
    println("ab".withRecv(3))
    println(listOf(1, 2, 3, 4).filter(::isEven))
    println(listOf("a", "bb").map(String::length))
    println(listOf(1, 2, 3).map(sq).map(adder(1)))
    val ops = mapOf<String, (Int, Int) -> Int>("+" to add, "*" to { a, b -> a * b })
    println(ops["*"]!!(3, 4) + ops.getValue("+")(1, 1))
    val compose = { f: (Int) -> Int, g: (Int) -> Int -> { x: Int -> g(f(x)) } }
    println(compose(sq, adder(1))(5))
    var counter = 0
    val inc = { counter++ }
    inc(); inc()
    println(counter)
    println(listOf(3, 1, 2).sortedBy { -it })
    println((1..5).map { it * it }.filter { it > 4 }.sum())
    val fns = listOf<() -> Int>({ 1 }, { 2 })
    println(fns.sumOf { it() })
    println(::isEven.invoke(4))
    val bound: (Int, Int) -> String = "hello"::substring
    println(bound(1, 3))
    println(listOf(1, 2, 3).fold(StringBuilder()) { sb, x -> sb.append(x) })
    fun local(f: () -> Unit) = f()
    local { println("lambda block") }
    println(sq.invoke(7))
    println((sq as (Int) -> Int)(2))
    val unit: () -> Unit = {}
    println(unit())
}
