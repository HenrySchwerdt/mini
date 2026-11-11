// REJECT M0112: a closure over a `var` owner that is returned outlives the owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun make(): () -> Int {
    var b = Box(7)
    val f = { b.v }
    return f
}

fun main() {
    println(make()())
}
