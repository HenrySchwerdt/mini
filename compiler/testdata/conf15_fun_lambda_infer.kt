// lambda type inference: return type from body, implicit it, labeled returns, destructuring params, higher-order returns
fun <T> run2(f: () -> T): T = f()
fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { g(f(it)) }
fun adder(n: Int): (Int) -> Int = { it + n }
fun curry(f: (Int, Int) -> Int): (Int) -> (Int) -> Int = { a -> { b -> f(a, b) } }
fun repeatStr(n: Int, f: (Int) -> String) = (0 until n).joinToString("") { f(it) }
fun opt(f: ((Int) -> Int)?): Int = f?.invoke(3) ?: -1
fun main() {
    println(run2 { 42 }); println(run2 { "s" }); println(run2 { listOf(1) }); println(run2 { }); println(run2 { if (true) 1 else 2.0 })
    println(compose(adder(1), { it * 10 })(4)); println(curry { a, b -> a * b }(6)(7))
    println(repeatStr(3) { "x$it" }); println(opt(null)); println(opt { it * it })
    val l = listOf(1, 2, 3, 4)
    println(l.map { it * 2 }); println(l.filter { it > 2 }.map { "n$it" }); println(l.fold(0) { acc, x -> acc + x }); println(l.map { x -> if (x % 2 == 0) "e" else x })
    println(l.forEach { if (it == 2) return@forEach; print(it) }); println(l.first { it > 2 }); println(l.firstOrNull { it > 9 })
    val r = l.map { if (it == 3) return@map -1; it * 10 }; println(r)
    val outer = run { val x = l.map lbl@{ if (it == 1) return@lbl 0; it }; x }; println(outer)
    val pairs = mapOf(1 to "a", 2 to "b"); println(pairs.map { (k, v) -> "$k$v" }); println(pairs.entries.map { it.key }); println(pairs.mapValues { (_, v) -> v.uppercase() })
    val sq: (Int) -> Int = { it * it }; val add: (Int, Int) -> Int = { a, b -> a + b }; val unit: () -> Unit = { println("unit") }; unit(); println(sq(5) + add(1, 2))
    val nested = { x: Int -> { y: Int -> x + y } }; println(nested(1)(2)); println((fun(x: Int): Int { return x + 100 })(1)); println(listOf(1, 2).map(fun(x) = x * 3))
    val fs = listOf<(Int) -> Int>({ it + 1 }, { it * 2 }); println(fs.map { it(5) }); val withRecv: String.(Int) -> String = { repeat(it) }; println("ab".withRecv(2)); println(with("xy") { length + 1 })
    var counter = 0; val inc = { counter++ }; inc(); inc(); println(counter); val gen = sequence { yield(1); yield(2) }; println(gen.toList())
    println(listOf(1, 2, 3).sumOf { it.toLong() }); println(listOf("a", "bb").maxOf { it.length }); println(listOf(1, 2, 3).associate { it to it * it })
}
