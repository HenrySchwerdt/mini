// REJECT M0101: the closure over a `var` owner is called after the owner was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    var b = Box(1)
    val f = { b.v }
    b.free()
    println(f())
}
