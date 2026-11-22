// ACCEPT: a list of default-allocated owners returned from a function; the list outlives the owners, which released their counts
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun build(n: Int): List<Box> {
    val out = mutableListOf<Box>()
    for (i in 1..n) {
        val b = Box(i * 10)
        out.add(b)
    }
    return out
}

fun sum(xs: List<Box>): Int {
    var s = 0
    for (x in xs) s += x.v
    return s
}

fun main() {
    val xs = build(4)
    println(sum(xs))
    println(xs.size)
    val ys = build(2) + build(3)
    println(sum(ys))
}
