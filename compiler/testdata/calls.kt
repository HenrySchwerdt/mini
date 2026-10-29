fun isEven(n: Int): Boolean = if (n == 0) true else isOdd(n - 1)
fun isOdd(n: Int): Boolean = if (n == 0) false else isEven(n - 1)

fun add(a: Int, b: Int): Int = a + b
fun mul(a: Long, b: Long): Long = a * b
fun avg(a: Double, b: Double): Double = (a + b) / 2
fun shout(s: String): String = s + "!"
fun log(msg: String) {
    println("[log] " + msg)
}

fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x
}

fun early(n: Int): String {
    if (n < 0) return "negative"
    if (n == 0) {
        return "zero"
    }
    return "positive"
}

fun countdown(n: Int) {
    if (n < 0) return
    print(n)
    print(' ')
    countdown(n - 1)
}

fun main() {
    println(isEven(10))
    println(isOdd(7))
    println(add(add(1, 2), add(3, 4)))
    println(mul(100000L, 100000L))
    println(avg(1.0, 2.0))
    println(shout("hi"))
    log("started")
    println(gcd(48, 18))
    println(early(-1))
    println(early(0))
    println(early(5))
    countdown(5)
    println()
}
