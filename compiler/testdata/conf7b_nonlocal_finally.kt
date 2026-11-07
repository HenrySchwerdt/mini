// a `return` in the finally of an inline function overrides a pending non-local return; the result type of
// `doCall(...)` is inferred from the lambdas that complete (the one that returns non-locally is Nothing)
var h = ""

inline fun <R> doCall(block: () -> R, finallyBlock: () -> R): R {
    try {
        block()
    } finally {
        return finallyBlock()
    }
}

fun test1(): String {
    val r = doCall({ h += "L"; "OK_LOCAL" }, { h += ",F"; return "OK_FINALLY" })
    return "FAIL $r"
}

fun test2(): String {
    val r = doCall({ h += "L"; "OK_LOCAL" }, { h += ",F"; "OK_FINALLY" })
    return r
}

fun test3(): String {
    val r = doCall({ h += "L"; return "NL" }, { h += ",F"; return "OK_FINALLY" })
    return "FAIL $r"
}

fun test4(): String {
    val r = doCall({ h += "L"; return "NL" }, { h += ",F"; "OK_FINALLY" })
    return r
}

fun <R> pick(a: () -> R, b: () -> R): R = b()

fun test5(): String {
    val r = pick({ throw IllegalStateException() }, { "OK5" })
    return r
}

fun main() {
    for (t in listOf(::test1, ::test2, ::test3, ::test4)) {
        h = ""
        println(t() + " " + h)
    }
    println(test5())
}
