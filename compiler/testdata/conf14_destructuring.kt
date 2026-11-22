data class P(val x: Int, val y: Int, val name: String)

fun pair(): Pair<String, Int> = "a" to 1
fun triple() = Triple(1, "two", 3.0)

fun main() {
    val (a, b) = pair()
    println("$a$b")
    val (i, s, d) = triple()
    println("$i $s $d")
    val ps = listOf(P(1, 2, "p"), P(3, 4, "q"))
    for ((x, y, n) in ps) println("$n:${x + y}")
    for ((idx, p) in ps.withIndex()) println("$idx ${p.name}")
    println(ps.map { (x, _, n) -> "$n$x" })
    val m = mapOf(1 to "one", 2 to "two")
    for ((k, v) in m) println("$k=$v")
    println(m.map { (k, v) -> "$v$k" })
    println(m.entries.sumOf { (k, _) -> k })
    val (first, second) = listOf(10, 20, 30)
    println(first + second)
    val (_, only) = pair()
    println(only)
    val f: (Pair<Int, Int>) -> Int = { (p, q) -> p * q }
    println(f(3 to 4))
    var (u, w) = 1 to 2
    u = u + w
    println(u)
}
