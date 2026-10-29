fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun fibIter(n: Int): Long {
    var a = 0L
    var b = 1L
    for (i in 0 until n) {
        val t = a + b
        a = b
        b = t
    }
    return a
}

fun factorial(n: Int): Long {
    if (n <= 1) return 1L
    return n * factorial(n - 1)
}

fun main() {
    println(fib(0))
    println(fib(1))
    println(fib(10))
    println(fib(25))
    println(fibIter(50))
    println(fibIter(90))
    println(factorial(20))
    println(factorial(21))
}
