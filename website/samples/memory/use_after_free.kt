@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    context(Heap()) {
        val b = Box(1)
        b.free()
        println(b.v)           // use after free
    }
}
