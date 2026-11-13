fun f(n: Int): String {
    try {
        if (n == 0) throw IllegalStateException("zero")
        return "ok$n"
    } catch (e: IllegalStateException) {
        return "caught ${e.message}"
    } finally {
        println("finally $n")
    }
}

fun g(): Int {
    var x = 1
    try {
        return x
    } finally {
        x = 2
        println("g finally $x")
    }
}

fun h(): Int {
    for (i in 1..3) {
        try {
            if (i == 2) continue
            if (i == 3) break
            println("body $i")
        } finally {
            println("fin $i")
        }
    }
    return 0
}

class MyEx(msg: String, val code: Int) : Exception(msg)

fun nested() {
    try {
        try {
            throw MyEx("inner", 7)
        } finally {
            println("inner finally")
        }
    } catch (e: MyEx) {
        println("outer caught ${e.message} ${e.code}")
        try {
            throw RuntimeException("second", e)
        } catch (e2: RuntimeException) {
            println("${e2.message} cause=${e2.cause?.message}")
        }
    }
}

fun main() {
    println(f(0))
    println(f(1))
    println(g())
    println(h())
    nested()
    val r = try { Integer.parseInt("zz") } catch (e: NumberFormatException) { -1 } finally { println("done") }
    try {
        try { throw Exception("a") } finally { println("cleanup") }
    } catch (e: Exception) { println(e.message) }
    println(runCatching { throw MyEx("x", 1) }.exceptionOrNull() is MyEx)
    val e = Exception("top")
    println(e.toString())
    println(MyEx("m", 2).toString())
}
