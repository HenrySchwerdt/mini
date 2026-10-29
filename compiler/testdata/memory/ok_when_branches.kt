// ACCEPT: each `when` branch releases or moves. Spec 5.8
@file:MemoryMode(Mode.Strict)

package memory.ok_when_branches

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    println("consumed " + b.v)
    b.free()
}

context(h: Heap)
fun classify(n: Int) {
    val b = Box(n)
    when {
        n < 0 -> b.free()
        n == 0 -> {
            println("zero")
            b.free()
        }
        else -> consume(b)
    }
}

fun main() {
    context(Heap()) {
        classify(-1)
        classify(0)
        classify(7)
    }
}
