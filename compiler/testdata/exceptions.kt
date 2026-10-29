// Exceptions: throw, try/catch/finally, hierarchy, cause, try as expression, exceptions across frames
// (with reference-counted locals released on the exceptional path), runtime failures as exceptions.

class AppException(val code: Int, message: String) : Exception(message)
class NotFound(val key: String) : RuntimeException("not found: " + key)
open class BaseError(msg: String) : Exception(msg)
class DerivedError(msg: String) : BaseError(msg)

class Res(val name: String) {
    init { log += "open $name;" }
    fun close() { log += "close $name;" }
}

var log = ""

class Holder(var value: Int)

fun mightFail(n: Int): Int {
    if (n < 0) throw IllegalArgumentException("negative: " + n)
    if (n == 0) throw AppException(404, "zero")
    return n * 2
}

fun level3(n: Int): Int {
    val h = Holder(n)          // reference-counted local that must be released while unwinding
    val s = "level3:" + n
    return mightFail(h.value) + s.length
}

fun level2(n: Int): Int {
    val h = Holder(n + 1)
    try {
        return level3(h.value - 1)
    } finally {
        log += "f2;"
    }
}

fun level1(n: Int): Int {
    val h = Holder(n)
    val tag = "tag" + n
    return level2(h.value) + tag.length
}

fun tryExpr(n: Int): String {
    val r = try {
        mightFail(n).toString()
    } catch (e: IllegalArgumentException) {
        "iae:" + e.message
    } catch (e: AppException) {
        "app:" + e.code + ":" + e.message
    }
    return r
}

fun finallyOrder(): Int {
    var x = 0
    try {
        x += 1
        try {
            x += 10
            throw IllegalStateException("inner")
        } finally {
            x += 100
            log += "inner-finally;"
        }
    } catch (e: IllegalStateException) {
        x += 1000
        log += "caught " + e.message + ";"
    } finally {
        x += 10000
        log += "outer-finally;"
    }
    return x
}

fun returnInFinally(n: Int): Int {
    try {
        if (n > 0) return n
        throw IllegalStateException("neg")
    } catch (e: IllegalStateException) {
        return -1
    } finally {
        log += "rf" + n + ";"
    }
}

fun loopWithFinally(): Int {
    var sum = 0
    for (i in 1..5) {
        try {
            if (i == 2) continue
            if (i == 4) break
            sum += i
        } finally {
            log += "L" + i + ";"
        }
    }
    return sum
}

fun rethrow(): String {
    try {
        try {
            throw DerivedError("d")
        } catch (e: BaseError) {
            log += "base-handler;"
            throw e
        }
    } catch (e: DerivedError) {
        return "derived " + e.message
    }
}

fun wrap(): String {
    try {
        try {
            mightFail(-5)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("wrapped", e)
        }
    } catch (e: IllegalStateException) {
        val c = e.cause
        return e.message + " <- " + (if (c == null) "none" else c.message)
    }
    return "unreachable"
}

fun useResource(fail: Boolean): String {
    val r = Res("r" + (if (fail) "1" else "0"))
    try {
        if (fail) throw NotFound("res")
        return "ok"
    } catch (e: NotFound) {
        return e.message ?: "?"
    } finally {
        r.close()
    }
}

fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))

interface Task { fun run(): Int }
class Boom(val n: Int) : Task {
    override fun run(): Int {
        val h = Holder(n)
        if (h.value > 1) throw AppException(h.value, "boom")
        return h.value
    }
}

fun runTask(t: Task): Int {
    val s = "task"
    return t.run() + s.length
}

class Gate(val ok: Boolean) {
    init {
        if (!ok) throw IllegalStateException("gate closed")
    }
}

fun main() {
    println(level1(3))
    try {
        level1(0)
    } catch (e: AppException) {
        println("AppException " + e.code + " " + e.message)
    }
    println(log)
    log = ""
    try {
        level1(-1)
    } catch (e: Exception) {
        println(e.message)
        println(e is IllegalArgumentException)
        println(e is RuntimeException)
        println(e is AppException)
        println(e)
    }
    println(log)
    log = ""

    println(tryExpr(4))
    println(tryExpr(-2))
    println(tryExpr(0))

    println(finallyOrder())
    println(log)
    log = ""

    println(returnInFinally(5))
    println(returnInFinally(-5))
    println(log)
    log = ""
    println(loopWithFinally())
    println(log)
    log = ""
    println(rethrow())
    println(log)
    log = ""
    println(wrap())
    println(useResource(false))
    println(useResource(true))
    println(log)
    log = ""

    // exceptions through lambdas and virtual calls
    try {
        println(applyTwice({ if (it > 5) throw NotFound("big") else it * 3 }, 1))
        println(applyTwice({ if (it > 5) throw NotFound("big") else it * 3 }, 2))
    } catch (e: NotFound) {
        println(e.message + " key=" + e.key)
    }
    println(runTask(Boom(1)))
    try {
        println(runTask(Boom(2)))
    } catch (e: AppException) {
        println("task failed " + e.code)
    }
    try {
        val g = Gate(true)
        println("gate ok")
        val g2 = Gate(false)
        println("not reached " + g2.ok + g.ok)
    } catch (e: IllegalStateException) {
        println(e.message)
    }

    // runtime failures are ordinary exceptions
    var zero = 0
    try {
        println(10 / zero)
    } catch (e: ArithmeticException) {
        println("arith: " + e.message)
    }
    try {
        println(10 % zero)
    } catch (e: Exception) {
        println("rem: " + e.message)
    }
    val maybe: String? = if (zero == 0) null else "x"
    try {
        println(maybe!!.length)
    } catch (e: NullPointerException) {
        println("npe")
    }
    val any: Any = "str"
    try {
        val n = any as Int
        println(n)
    } catch (e: ClassCastException) {
        println("cce")
    }
    val safe = any as? Int
    println(safe)

    // error / require / check / requireNotNull
    try {
        error("custom state")
    } catch (e: IllegalStateException) {
        println(e.message)
    }
    try {
        require(zero > 0) { "zero must be positive, was " + zero }
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        require(zero > 0)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        check(zero > 0)
    } catch (e: IllegalStateException) {
        println(e.message)
    }
    try {
        check(zero > 0) { "lazy check" }
    } catch (e: IllegalStateException) {
        println(e.message)
    }
    try {
        requireNotNull(maybe)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        requireNotNull(maybe) { "needed a value" }
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    val present: String? = "here"
    println(requireNotNull(present).length)
    try {
        TODO("later")
    } catch (e: NotImplementedError) {
        println(e.message)
    }
    try {
        throw DerivedError("as base")
    } catch (e: BaseError) {
        println("BaseError caught: " + e.message)
    }
    try {
        throw Exception()
    } catch (e: Exception) {
        println(e.message)
    }
    println("done")
}
