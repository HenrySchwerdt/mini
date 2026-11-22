// ACCEPT: default-allocated owners in loops with try/catch/finally, continue and break, a nullable owner freed and reset, an early return from an inline lambda, use{} on an AutoCloseable
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(var v: Int)
class Res(val n: Int) : AutoCloseable {
    override fun close() { println("close " + n) }
}

fun risky(n: Int): Box {
    val b = Box(n)
    if (n % 3 == 0) throw IllegalArgumentException("three")
    return b
}

fun loopy(): Int {
    var total = 0
    for (i in 1..7) {
        try {
            val b = risky(i)
            if (i == 5) continue
            if (i == 7) break
            total += b.v
        } catch (e: IllegalArgumentException) {
            total += 100
        } finally {
            total += 1
        }
    }
    return total
}

fun nullable(flag: Boolean): Int {
    var b: Box? = Box(1)
    if (flag) {
        b?.free()
        b = null
    }
    val c = b
    return if (c != null) c.v else -1
}

fun inlineEarly(n: Int): Int {
    val b = Box(n)
    return run {
        if (b.v > 2) return 10
        b.v
    }
}

fun main() {
    println(loopy())
    println(nullable(true))
    println(nullable(false))
    println(inlineEarly(1))
    println(inlineEarly(5))
    Res(1).use { println("in use") }
    val r = Res(2)
    r.use { println("body " + it.n) }
}
