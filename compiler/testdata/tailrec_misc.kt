// tailrec (compiled to a loop), operators (get/set with several indices, invoke, contains, rangeTo, componentN), destructuring,
// default and named arguments (also through overrides), varargs with spread, Unit?, Nothing.
tailrec fun sum(n: Int, acc: Long = 0): Long = if (n == 0) acc else sum(n - 1, acc + n)

tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

tailrec fun swapCount(a: Int, b: Int, n: Int): String {
    if (n == 0) return "$a,$b"
    return swapCount(b, a, n - 1)
}

tailrec fun walk(s: String, i: Int = 0, count: Int = 0): Int {
    when {
        i >= s.length -> return count
        s[i] == 'a' -> return walk(s, i + 1, count + 1)
        else -> return walk(s, i + 1, count)
    }
}

tailrec fun countDown(n: Int, log: StringBuilder) {
    if (n == 0) return
    if (n % 250000 == 0) log.append(n).append(' ')
    countDown(n - 1, log)
}

class Counter {
    tailrec fun up(n: Int, acc: Int = 0): Int = when (n) {
        0 -> acc
        else -> up(n - 1, acc + 2)
    }
}

class M {
    operator fun get(a: Int, b: Int) = a * b
    operator fun set(a: Int, b: Int, v: Int) { println("set $a $b $v") }
    operator fun invoke(x: Int) = x + 1
    operator fun contains(x: Int) = x == 3
}

data class Pt(val x: Int, val y: Int)

class Q(val a: Int, val b: String) {
    operator fun component1() = a
    operator fun component2() = b
}

class R(val lo: Int, val hi: Int) {
    operator fun rangeTo(o: R) = R(lo, o.hi)
    operator fun contains(x: Int) = x in lo..hi
    override fun toString() = "R($lo,$hi)"
}

infix fun Int.plus2(o: Int) = this + o + 2

fun v(vararg a: Int) = a.size
fun total(vararg a: Int): Int { var s = 0; for (x in a) s += x; return s }
fun d(a: Int, b: Int = 2, c: Int = 3) = a * 100 + b * 10 + c

open class Base(val x: Int = 1, val y: Int = 2) {
    open fun f(a: Int = 10, b: Int = 20) = a + b + x + y
}

class Der : Base(y = 5) {
    override fun f(a: Int, b: Int) = a * b
}

fun String.rep(n: Int = 2, sep: String = "") = (1..n).joinToString(sep) { this }

fun fail(): Nothing = throw IllegalStateException("boom")

fun log(s: String) { println(s) }

fun main() {
    println(sum(1000000))
    println(gcd(1071, 462))
    println(swapCount(1, 2, 3))
    println(swapCount(1, 2, 1000001))
    println(walk("banana"))
    val sb = StringBuilder()
    countDown(1000000, sb)
    println(sb.toString().trim())
    println(Counter().up(1000000))

    val m = M()
    println(m[2, 3])
    m[1, 2] = 3
    println(m(4))
    println(3 in m)
    println(4 !in m)
    println(1 plus2 3)
    val arr = intArrayOf(1, 2, 3)
    println(v(*arr, 4))
    println(total(*arr, 10, *arr))
    println(d(1, c = 5))
    println(d(b = 9, a = 1))
    for ((k, vv) in listOf(1 to "a", 2 to "b")) println("$k$vv")
    listOf(Pt(1, 2)).forEach { (x, y) -> println(x + y) }
    val (qa, qb) = Q(7, "z")
    println("$qa$qb")
    val (px, _) = Pt(5, 6)
    println(px)
    println(R(1, 2)..R(5, 9))
    println(Der().f())
    println(Der().f(b = 3))
    println(Base(x = 7).f(b = 1))
    println("ab".rep(3, "-"))
    println("ab".rep(sep = "+"))
    val u: Unit? = null
    println(u)
    val u2: Unit? = Unit
    println(u2)
    val a: String? = "x"
    val r = a?.let { log(it) }
    println(r == null)
    val n: String? = null
    println(n?.let { log(it) } == null)
    val x: Int = if (arr.size > 5) fail() else 1
    println(x)
    println((1..<3).contains(2))
    for (i in 0..<3) print(i)
    println()
    val xs = arrayOf("a", "b")
    println(listOf(*xs, "c"))
    try { fail() } catch (e: IllegalStateException) { println(e.message) }
    val nothing: Nothing? = null
    println(nothing)
    for (i in 1..3) { for (j in 1..3) { if (j == 2) continue; if (i == 2) break; println("$i$j") } }
}
