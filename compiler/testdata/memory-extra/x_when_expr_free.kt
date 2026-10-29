// ACCEPT: all branches of a when expression release
@file:MemoryMode(Mode.Strict)

package memory.extra.x_when_expr_free

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run(n: Int): Int {
    val b = Box(n)
    val r = when {
        n > 0 -> { val x = b.v; b.free(); x }
        else -> { b.free(); 0 }
    }
    return r
}

fun main() {
    context(Heap()) { println(run(2)) }
}
