// values of type Nothing / Nothing? in unusual positions: a safe call on null, string concatenation with break, elvis on Nothing?
fun f(): String {
    try {
        return "OK"
    } finally {
        null?.toString()
    }
}

fun g(): String {
    OUTER@ while (true) {
        var x = ""
        try {
            do {
                x = x + break@OUTER
            } while (true)
        } finally {
            return "OK2"
        }
    }
}

fun expectFail(f: () -> Unit): Nothing? {
    try {
        f()
    } catch (e: ArithmeticException) {
        return null
    }
    throw AssertionError("Expected ArithmeticException to be thrown")
}

fun test() {
    null?.run { return }
}

fun eqeqN(a: Nothing?, b: Nothing?) = a == b

fun main() {
    println(f())
    println(g())
    val a2 = expectFail { 1 / 0 } ?: 0
    println(a2)
    test()
    println("done")
    println(eqeqN(null, null))
}
