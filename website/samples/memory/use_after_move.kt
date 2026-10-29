@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a.move()
    println(a.v)               // `a` was moved into `b`
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
