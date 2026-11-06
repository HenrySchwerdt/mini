// REJECT M0101: the function value `::peek` reads the captured owner when it is called, after the owner was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Item(val v: Int)

fun main() {
    val a = Item(1)
    fun peek(): Int = a.v
    val f: () -> Int = ::peek
    a.free()
    println(f())
}
