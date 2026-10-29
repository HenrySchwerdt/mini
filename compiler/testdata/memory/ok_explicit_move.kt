// ACCEPT: `.move()` (or an @Owned type annotation) moves ownership into a new variable. Spec 5.1
@file:MemoryMode(Mode.Strict)

package memory.ok_explicit_move

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a.move()
    val c = Box(2)
    val d: @Owned Box = c
    println(b.v + d.v)
    b.free()
    d.free()
}

fun main() {
    context(Heap()) { run() }
}
