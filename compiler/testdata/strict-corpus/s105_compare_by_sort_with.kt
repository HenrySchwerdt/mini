// ACCEPT: a comparator from compareBy borrows the owned object its selector captures and is only passed to sortWith
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Limit(val v: Int)

fun main() {
    val l = Limit(2)
    val xs = mutableListOf(3, 1, 2)
    val cmp = compareBy<Int> { if (it > l.v) 100 - it else it }
    xs.sortWith(cmp)
    println(xs)
    println(xs.sortedWith(compareBy<Int> { -it + l.v }))
    l.free()
}
