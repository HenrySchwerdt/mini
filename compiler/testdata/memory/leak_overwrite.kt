// REJECT M0102: the first value is lost when the variable is overwritten. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_overwrite

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun churn() {
    var b = Box(1)
//          ^ M0102
    b = Box(2)
    b.free()
}

fun main() {
    context(Heap()) { churn() }
}
