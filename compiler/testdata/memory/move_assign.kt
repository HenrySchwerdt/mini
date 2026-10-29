// REJECT M0104: `val b = a` moves; `a` is dead afterwards. Spec 5.1, 7.4
@file:MemoryMode(Mode.Strict)

package memory.move_assign

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a
    println(a.v)
//          ^ M0104
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
