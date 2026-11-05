// ACCEPT: ?.let and ?: on nullable Strict values
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun find(b: Box?): Int = b?.let { it.v } ?: -1

fun main() {
    val a: Box? = Box(9)
    println(find(a))
    println(find(null))
    a?.free()
}
