// ACCEPT: allocation through a Heap context
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun make(v: Int): Box = Box(v)

fun main() {
    context(Heap()) {
        val b = make(21)
        println(b.v * 2)
        b.free()
    }
}
