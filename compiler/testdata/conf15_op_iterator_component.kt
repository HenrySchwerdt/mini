// iterator convention for for-loops, component functions for destructuring, in extension form too
class Countdown(val from: Int) { operator fun iterator(): Iterator<Int> = object : Iterator<Int> { var c = from; override fun hasNext() = c > 0; override fun next() = c-- } }
class Pair3(val a: Int, val b: String, val c: Boolean) { operator fun component1() = a; operator fun component2() = b; operator fun component3() = c }
class Range2(val lo: Int, val hi: Int)
operator fun Range2.iterator() = (lo..hi).iterator()
operator fun Range2.component1() = lo
operator fun Range2.component2() = hi
class Grid(val rows: List<List<Int>>) : Iterable<List<Int>> { override fun iterator() = rows.iterator() }
class Tokens(val s: String) { operator fun iterator() = s.split(" ").iterator() }
fun main() {
    for (i in Countdown(3)) print(i); println()
    val (a, b, c) = Pair3(1, "x", true); println("$a $b $c"); val (_, y, _) = Pair3(2, "y", false); println(y)
    for (v in Range2(2, 4)) print(v); println(); val (lo, hi) = Range2(5, 9); println(lo + hi)
    for (row in Grid(listOf(listOf(1, 2), listOf(3)))) println(row.sum()); println(Grid(listOf(listOf(1))).map { it.size }); println(Grid(listOf(listOf(1, 2))).first())
    for (t in Tokens("a b c")) print("[$t]"); println(); val lst = Pair3(1, "q", false).let { (x, z) -> "$x$z" }; println(lst)
    for ((i, ch) in "abc".withIndex()) print("$i$ch "); println(); for ((k, v) in mapOf(1 to "one", 2 to "two")) print("$k=$v "); println(); for ((i, p) in listOf(Pair3(1, "a", true)).withIndex()) { val (n, s) = p; println("$i $n $s") }
    val it = Countdown(2).iterator(); println(it.next()); println(it.next()); println(it.hasNext())
    println(Countdown(4).iterator().asSequence().toList()); val arr = arrayOf(Pair3(7, "z", true)); for ((n, s, f) in arr) println("$n$s$f")
    var sum = 0; for (x in Countdown(5)) { if (x == 3) continue; sum += x }; println(sum); val (q, r) = 17.let { it / 5 to it % 5 }; println("$q $r")
}
