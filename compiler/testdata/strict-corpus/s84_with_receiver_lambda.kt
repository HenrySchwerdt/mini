// ACCEPT: a function with a lambda-with-receiver parameter that is only invoked
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun build(init: Box.() -> Unit): Box {
    val b = Box(0)
    b.init()
    return b
}

fun main() {
    val b = build { v = 5 }
    println(b.v)
    b.free()
}
