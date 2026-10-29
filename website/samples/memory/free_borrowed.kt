@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun drop(b: Box) {
    b.free()                   // `b` is only borrowed here
}

fun main() {
    context(Heap()) {
        val b = Box(1)
        drop(b)
        b.free()
    }
}
