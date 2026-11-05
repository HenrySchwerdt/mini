// ACCEPT: a function returns a closure over a borrowed parameter; the caller uses it while the owner is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Limit(val v: Int)

fun above(l: Limit): Comparator<Int> = Comparator { a, b -> (a - l.v).compareTo(b - l.v) }

fun main() {
    val l = Limit(2)
    val xs = mutableListOf(3, 1, 2)
    xs.sortWith(above(l))
    println(xs)
    l.free()
}
