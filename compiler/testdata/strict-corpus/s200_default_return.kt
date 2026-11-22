// ACCEPT: a class without @AllocatedBy and without a visible allocator uses the default allocator: the object may be returned and is released by whoever owns it when the owner ends
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun make(n: Int): Box = Box(n * 2)

fun makeTwice(n: Int): Box {
    val inner = make(n)
    return Box(inner.v + 1)
}

fun main() {
    val a = make(3)
    println(a.v)
    val b = makeTwice(4)
    println(b.v)
    println(make(5).v)
    make(6)
    for (i in 1..3) {
        val c = make(i)
        println(c.v)
    }
}
