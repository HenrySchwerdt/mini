// ACCEPT: extension functions borrow their receiver
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun Box.doubled(): Int = v * 2
fun Box.describe(): String = "Box(" + v + ")"

fun main() {
    val b = Box(4)
    println(b.doubled())
    println(b.describe())
    b.free()
}
