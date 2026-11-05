// REJECT M0101: the comparator returned over a borrowed parameter is used after the owner was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Limit(val v: Int)

fun above(l: Limit): Comparator<Int> = Comparator { a, b -> (a - l.v).compareTo(b - l.v) }

fun main() {
    val l = Limit(2)
    val c = above(l)
    l.free()
    val xs = mutableListOf(3, 1, 2)
    xs.sortWith(c)
    println(xs)
}
