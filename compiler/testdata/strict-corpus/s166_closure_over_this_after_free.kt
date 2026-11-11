// REJECT M0101: the closure returned from a method is a loan of the receiver; calling it after the receiver was freed is a use after free
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun peek(): () -> Int = { n }
}

fun main() {
    val c = Counter(1)
    val f = c.peek()
    c.free()
    println(f())
}
