// ACCEPT: repeat / forEach over a range with a Strict object in the body
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Counter(var n: Int)

fun main() {
    val c = Counter(0)
    repeat(3) { c.n += it }
    (1..3).forEach { c.n += it }
    for (i in 0 until 2) c.n++
    println(c.n)
    c.free()
}
