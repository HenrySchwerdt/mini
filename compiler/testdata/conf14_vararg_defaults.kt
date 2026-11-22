fun sum(vararg xs: Int, bias: Int = 0): Int = xs.sum() + bias
fun fmt(a: Int, b: String = "b", c: Double = a * 1.5, vararg rest: String) = "$a$b$c${rest.toList()}"
fun <T> listOf2(vararg xs: T): List<T> = xs.toList()
fun count(vararg s: String) = s.size
fun named(x: Int = 1, y: Int = 2, z: Int = 3) = "$x$y$z"

class K(val a: Int = 7, val b: String = "k") {
    fun m(p: Int = a * 2) = p + a
}

fun main() {
    println(sum())
    println(sum(1, 2, 3))
    println(sum(1, 2, bias = 10))
    val arr = intArrayOf(4, 5)
    println(sum(*arr, 6))
    println(fmt(2))
    println(fmt(2, "x"))
    println(fmt(2, c = 0.5))
    println(fmt(1, "q", 2.0, "r", "s"))
    println(listOf2("a", "b"))
    println(count(*arrayOf("x", "y"), "z"))
    println(named(z = 9))
    println(named(y = 5, x = 0))
    println(named(1, z = 4))
    println(K().a to K(b = "z").b)
    println(K(3).m() + K(1).m(10))
    val f = ::named
    println(f(7, 8, 9))
    println(listOf2<Int>().size)
    println(arrayOf(*arrayOf(1, 2), 3).toList())
}
