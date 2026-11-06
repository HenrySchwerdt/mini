// A finally block that throws after `return` / `break` runs exactly once.
var count = 0

fun f(): Int {
    try {
        return 1
    } finally {
        count++
        throw IllegalStateException("from finally")
    }
}

fun g(): Int {
    var n = 0
    while (true) {
        try {
            try {
                break
            } finally {
                n++
                throw RuntimeException("inner")
            }
        } catch (e: RuntimeException) {
            println("caught " + e.message + " n=" + n)
            return n
        }
    }
}

fun h(): String {
    val log = StringBuilder()
    try {
        try {
            return "x"
        } finally {
            log.append("a")
            throw IllegalStateException("a")
        }
    } finally {
        log.append("b")
        println(log.toString())
    }
}

fun main() {
    try {
        f()
    } catch (e: IllegalStateException) {
        println("caught " + e.message + " count=" + count)
    }
    println(g())
    try {
        h()
    } catch (e: IllegalStateException) {
        println("h caught " + e.message)
    }
}
