// ACCEPT: owners built by if/when expressions, an elvis, a loop that replaces its owner, nullable owners
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Cell(val n: Int)

fun pick(k: Int): Cell = when (k) {
    0 -> Cell(10)
    1 -> Cell(20)
    else -> Cell(30)
}

fun maybe(k: Int): Cell? = if (k > 0) Cell(k) else null

fun main() {
    for (k in 0..2) {
        val c = pick(k)
        println(c.n)
    }
    val d = maybe(0) ?: Cell(-1)
    println(d.n)
    val e = maybe(5)
    if (e != null) println(e.n)
    var cur: Cell? = null
    for (i in 1..3) {
        val next = Cell(i)
        cur = next.move()
    }
    println(cur?.n)
    val x = if (d.n < 0) Cell(100) else Cell(200)
    println(x.n)
}
