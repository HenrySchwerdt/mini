// ACCEPT: swapping two owners with move()
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    var a = Box(1)
    var b = Box(2)
    val t = a.move()
    a = b.move()
    b = t.move()
    println(a.v)
    println(b.v)
    a.free()
    b.free()
}
