// ACCEPT: toString and string templates with expressions of a Strict receiver, nested calls
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun label(b: Box): String = "<" + b.v.toString() + ">"

fun main() {
    val b = Box(2)
    println(label(b) + label(b))
    println("${label(b)}!")
    b.free()
}
