// labeled break/continue, nested loops, do-while scoping, loops with ranges and modification, return from lambdas with labels
fun main() {
    outer@ for (i in 1..4) { for (j in 1..4) { if (j == 3) continue@outer; if (i == 3) break@outer; print("$i$j ") } }; println()
    var n = 0; loop@ while (true) { n++; do { n += 10; if (n > 50) break@loop } while (n % 7 != 0) }; println(n)
    var i = 0; do { val k = i * 2; i++ } while (k < 6); println(i)
    var count = 0; for (x in listOf(1, 2, 3)) { for (y in listOf(10, 20)) { if (y == 20 && x == 2) continue; count += x * y } }; println(count)
    val r = run { for (a in 1..5) { if (a == 3) return@run a * 100 }; -1 }; println(r)
    val found = listOf(1, 2, 3).firstOrNull { x -> var ok = false; for (d in 1..x) if (d * d == x) ok = true; ok && x > 1 }; println(found)
    var s = ""; for (c in "abc") { for (d in "xy") { if (d == 'y' && c == 'b') break; s += "$c$d," } }; println(s)
    var w = 0; while (w < 10) { w++; if (w % 2 == 0) continue; if (w > 7) break; print(w) }; println()
    for (idx in 5 downTo 1 step 2) print(idx); println(); for (e in listOf<Int>()) println("never"); for ((a, b) in listOf(1 to 2, 3 to 4)) print(a + b); println()
    val m = mutableListOf(1, 2, 3, 4); val it = m.iterator(); while (it.hasNext()) { if (it.next() % 2 == 0) it.remove() }; println(m)
    var total = 0; repeat(3) { a -> repeat(2) { b -> total += a * b } }; println(total); var z = 10; while (z > 0) z -= 3; println(z); for (q in 1..3) { val sq = q * q; if (sq > 4) { println("big $sq"); break } }
    run lbl@{ if (true) return@lbl; println("skipped") }; println("after"); val v = (1..5).map { if (it == 3) return@map 0 else it }; println(v)
    fun find(l: List<List<Int>>, t: Int): Pair<Int, Int>? { for ((a, row) in l.withIndex()) for ((b, x) in row.withIndex()) if (x == t) return a to b; return null }; println(find(listOf(listOf(1, 2), listOf(3, 4)), 3)); println(find(listOf(listOf(1)), 9))
}
