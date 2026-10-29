// Used on "How it works: Exceptions" to show the generated error-return code.

fun half(n: Int): Int {
    if (n < 0) throw IllegalArgumentException("negative")
    return n / 2
}

fun main() {
    try {
        println(half(8))
        println(half(-1))
    } catch (e: IllegalArgumentException) {
        println("caught: ${e.message}")
    }
}
