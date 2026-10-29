// ACCEPT: the callee takes over the obligation. Spec 5.1
@file:MemoryMode(Mode.Strict)

package memory.ok_ownership_transfer

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    println("consume " + b.v)
    b.free()
}

context(h: Heap)
fun run() {
    val b = Box(5)
    println("before")
    consume(b)
    println("after")
}

fun main() {
    context(Heap()) { run() }
}
