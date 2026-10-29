// A tiny program used to show what the compiler produces at each stage (MIR and C).

class Counter(var n: Int) {
    fun inc(): Int {
        n += 1
        return n
    }
}

fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun main() {
    val c = Counter(0)
    println(fib(10))
    println(c.inc())
}
