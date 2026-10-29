@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val alias = a              // plain assignment only borrows: `a` stays the owner
    println(alias.v)
    val b = a.move()           // explicit move: `b` is the owner now, `a` is dead
    println(b.v)
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
