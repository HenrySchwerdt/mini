// tailrec, infix functions, operator precedence of infix, extension infix, Nothing, Unit
tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
tailrec fun sumTo(n: Long, acc: Long = 0): Long = if (n == 0L) acc else sumTo(n - 1, acc + n)
tailrec fun <T> lastOf(l: List<T>, i: Int = 0): T = if (i == l.size - 1) l[i] else lastOf(l, i + 1)
infix fun Int.pow(e: Int): Int { var r = 1; repeat(e) { r *= this }; return r }
infix fun String.times(n: Int) = repeat(n)
infix fun <A, B> A.pairedWith(b: B) = Pair(this, b)
class V(val x: Int) { infix fun plus2(o: V) = V(x + o.x); infix fun shl2(n: Int) = V(x shl n); override fun toString() = "V$x" }
fun fail(msg: String): Nothing = throw IllegalStateException(msg)
fun orFail(x: Int?): Int = x ?: fail("no value")
fun unitFun(): Unit { }
fun unitExpr() = println("side")
fun main() {
    println(gcd(48, 18)); println(sumTo(1000000)); println(lastOf(listOf(1, 2, 3))); println(2 pow 10); println("ab" times 3); println(1 pairedWith "x")
    println(1 + (2 pow 3)); println(2 pow 2 + 1); println(V(1) plus2 V(2) plus2 V(3)); println(V(1) shl2 3); println(1 shl 3 + 1); println(1 shl 2 or 1); println(5 and 3 shl 1)
    println(3 in 1..5 && 3 !in 4..6); println((1 until 5) step 2); println(true || fail("not evaluated")); println(false && fail("not evaluated"))
    try { orFail(null) } catch (e: IllegalStateException) { println(e.message) }; println(orFail(4)); println(unitFun()); println(unitExpr()); val u: Unit = Unit; println(u)
    val x = if (true) 1 else fail("never"); println(x); val y: Int = run { if (x > 0) return@run 5; fail("n") }; println(y)
    println(1 to 2 to 3); println(listOf(1, 2) + 3 - 1); println(1 + 2 * 3 - 4 / 2 % 3); println(-2 pow 2); println(!true or true); println(1 < 2 == true)
}
