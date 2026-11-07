// ACCEPT: a local function used as a value while its captured owner is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Item(val v: Int)

fun main() {
    val a = Item(7)
    fun peek(): Int = a.v
    val f: () -> Int = ::peek
    println(f())
    println(f() + 1)
    a.free()
}
