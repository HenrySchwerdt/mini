tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
tailrec fun sumTo(n: Int, acc: Long = 0): Long = if (n == 0) acc else sumTo(n - 1, acc + n)
tailrec fun <T> lastOf(l: List<T>, i: Int = 0): T = if (i == l.size - 1) l[i] else lastOf(l, i + 1)

fun fibs(n: Int): List<Long> {
    val out = ArrayList<Long>()
    fun go(a: Long, b: Long, k: Int) {
        if (k == 0) return
        out.add(a)
        go(b, a + b, k - 1)
    }
    go(0, 1, n)
    return out
}

fun counter(): () -> Int {
    var c = 0
    fun inc(): Int { c += 1; return c }
    return ::inc
}

fun outer(x: Int): Int {
    val y = x * 2
    fun mid(z: Int): Int {
        fun inner(w: Int) = w + y + x
        return inner(z) * 2
    }
    return mid(1)
}

fun main() {
    println(gcd(1071, 462))
    println(sumTo(1_000_000))
    println(lastOf(listOf("a", "b", "c")))
    println(fibs(10))
    val c = counter()
    c(); c()
    println(c())
    println(outer(3))
    fun fact(n: Int): Long = if (n <= 1) 1 else n * fact(n - 1)
    println(fact(15))
    val memo = HashMap<Int, Long>()
    fun fib(n: Int): Long = if (n < 2) n.toLong() else memo.getOrPut(n) { fib(n - 1) + fib(n - 2) }
    println(fib(80))
    tailrec fun collatz(n: Long, steps: Int = 0): Int = if (n == 1L) steps else collatz(if (n % 2 == 0L) n / 2 else 3 * n + 1, steps + 1)
    println(collatz(27))
}
