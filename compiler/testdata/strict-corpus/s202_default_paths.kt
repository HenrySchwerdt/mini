// ACCEPT: an owner of the default allocator on every kind of path: branches, a move on one path only, loops with break and continue, an early return, an exception
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun consume(@Owned b: Box) {
    println("consumed " + b.v)
    b.free()
}

fun pass(@Owned b: Box): Box = b

fun choose(n: Int): Int {
    val b = Box(n)
    if (n > 5) {
        return b.v * 10
    }
    if (n > 3) consume(b)
    return n
}

fun loops(): Int {
    var total = 0
    for (i in 1..6) {
        val b = Box(i)
        if (i == 2) continue
        if (i == 5) break
        total += b.v
    }
    return total
}

fun thrower(n: Int): Int {
    val b = Box(n)
    if (n == 2) throw IllegalStateException("boom")
    return b.v
}

fun main() {
    println(choose(1))
    println(choose(4))
    println(choose(9))
    println(loops())
    println(thrower(1))
    try {
        println(thrower(2))
    } catch (e: IllegalStateException) {
        println("caught " + e.message)
    }
    val x = Box(8)
    val y = pass(x)
    println(y.v)
    var z = Box(1)
    z = Box(2)
    z = Box(3)
    println(z.v)
    val w = Box(4)
    w.free()
    val q = Box(5)
    val r = q.move()
    println(r.v)
}
