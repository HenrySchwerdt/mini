// inline functions: non-local return, crossinline, noinline, reified, inline properties, default lambda args
inline fun <T> measure(label: String, block: () -> T): T { println("start $label"); val r = block(); println("end $label"); return r }
inline fun repeat2(n: Int, action: (Int) -> Unit) { for (i in 0 until n) action(i) }
inline fun withCross(crossinline f: () -> Int): () -> Int = { f() * 2 }
inline fun mixed(noinline a: () -> String, crossinline b: () -> String): String = listOf(a, { b() }).joinToString { it() }
inline fun <reified T> isType(x: Any?) = x is T
inline val String.shout get() = uppercase() + "!"
inline fun orElse(x: Int?, default: () -> Int = { -1 }) = x ?: default()
fun findFirstNeg(l: List<Int>): Int? { l.forEach { if (it < 0) return it }; return null }
fun nested(): String { measure("outer") { measure("inner") { return "returned from nested" } }; return "unreachable" }
fun loopReturn(): Int { repeat2(5) { if (it == 3) return it * 10 }; return -1 }
fun main() {
    println(measure("calc") { 6 * 7 }); println(findFirstNeg(listOf(1, -2, -3))); println(findFirstNeg(listOf(1))); println(nested()); println(loopReturn())
    val f = withCross { 21 }; println(f()); println(mixed({ "A" }, { "B" })); println(isType<String>("s")); println(isType<Int>("s")); println(isType<List<*>>(listOf(1))); println("hey".shout)
    println(orElse(null)); println(orElse(5)); println(orElse(null) { 99 }); var acc = 0; repeat2(4) { acc += it }; println(acc)
    val l = listOf(1, 2, 3); val r = l.map { x -> if (x == 2) return@map 0; x }; println(r); l.forEachIndexed { i, v -> if (v == 3) println("last at $i") }
    run { println("in run"); if (acc > 0) return@run; println("skipped") }; println(l.any { it > 2 }); println(l.sumOf { if (it > 1) it else 0 })
    fun local(): Int { listOf(1, 2, 3).forEach { if (it == 2) return it }; return 0 }; println(local()); println(l.firstOrNull { it > 1 }.let { it ?: -1 }); println(synchronized2 { 5 })
}
inline fun <T> synchronized2(block: () -> T): T = block()
