@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun peek(b: Box): Int = b.v               // parameters are borrowed by default

fun consume(b: @Owned Box) {              // @Owned: this function takes over the obligation
    println("consuming ${b.v}")
    b.free()
}

fun main() {
    context(Heap()) {
        val b = Box(7)
        println(peek(b))                  // lend: b is still ours afterwards
        consume(b)                        // move: b is gone after this call
    }
}
