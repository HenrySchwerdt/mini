// REJECT M0101: an alias (`val b = a`) used after its owner was freed. Spec 5.1, 7.1
@file:MemoryMode(Mode.Strict)

package memory.borrow_use_after_free

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a
    println(b.v)
    a.free()
    println(b.v)
//          ^ M0101
}

fun main() {
    context(Heap()) { run() }
}
