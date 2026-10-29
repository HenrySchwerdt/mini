// REJECT M0104: storing into an owned field moves. Spec 5.1, 5.3, 7.4
@file:MemoryMode(Mode.Strict)

package memory.move_into_field

import mini.memory.*

class Box(val v: Int)

class Slot(var item: Box?)

context(h: Heap)
fun run() {
    val s = Slot(null)
    val b = Box(1)
    s.item = b
    println(b.v)
//          ^ M0104
    s.free()
}

fun main() {
    context(Heap()) { run() }
}
