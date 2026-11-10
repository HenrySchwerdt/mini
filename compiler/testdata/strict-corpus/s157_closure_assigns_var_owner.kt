// REJECT M0112: a closure that assigns the `var` owner replaces the owner behind the checker's back (the old Box is never freed)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    var b = Box(1)
    val reset = { b = Box(2) }
    reset()
    println(b.v)
    b.free()
}
