// ACCEPT: if/else with release on one side and a move on the other. Spec 5.8
@file:MemoryMode(Mode.Strict)

package memory.ok_conditional_free_paths

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    println("consume " + b.v)
    b.free()
}

context(h: Heap)
fun run(n: Int) {
    val b = Box(n)
    if (n > 0) b.free() else consume(b)
}

fun main() {
    context(Heap()) {
        run(1)
        run(-1)
    }
}
