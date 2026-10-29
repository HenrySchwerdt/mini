// REJECT M0101: read after free. Spec 7.1
@file:MemoryMode(Mode.Strict)

package memory.uaf_simple

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    b.free()
    println(b.v)
//          ^ M0101
}

fun main() {
    context(Heap()) { run() }
}
