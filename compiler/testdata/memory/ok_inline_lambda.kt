// ACCEPT: inline lambdas (repeat, run, forEach) are transparent to the analysis. Spec 5.5
@file:MemoryMode(Mode.Strict)

package memory.ok_inline_lambda

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    repeat(2) { i ->
        val b = Box(i)
        println(b.v)
        b.free()
    }
    val c = Box(9)
    run {
        println(c.v)
        c.free()
    }
    val d = Box(5)
    listOf(1, 2).forEach { println(d.v + it) }
    d.free()
}

fun main() {
    context(Heap()) { run() }
}
