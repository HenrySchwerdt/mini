// throw, try/catch/finally, custom exceptions, try as an expression, runtime failures as exceptions.

class AppException(val code: Int, message: String) : Exception(message)

fun risky(n: Int): Int {
    if (n < 0) throw IllegalArgumentException("negative: $n")
    if (n == 0) throw AppException(404, "zero")
    return 100 / n
}

fun attempt(n: Int): String {
    return try {
        "ok ${risky(n)}"
    } catch (e: IllegalArgumentException) {
        "bad argument (${e.message})"
    } catch (e: AppException) {
        "app error ${e.code}: ${e.message}"
    } finally {
        println("finally for $n")
    }
}

fun main() {
    println(attempt(4))
    println(attempt(-1))
    println(attempt(0))

    // Runtime failures are ordinary exceptions, as on the JVM.
    val zero = 0
    try {
        println(10 / zero)
    } catch (e: ArithmeticException) {
        println("caught: ${e.message}")
    }
    try {
        val s: String? = null
        println(s!!.length)
    } catch (e: NullPointerException) {
        println("caught NPE")
    }
    try {
        check(zero > 0) { "zero is not positive" }
    } catch (e: IllegalStateException) {
        println(e.message)
    }
}
