// ACCEPT: a loop over the list that only reads it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun sum(xs: List<Box>): Int {
    var s = 0
    for (b in xs) s += b.v
    xs.forEach { s += it.v }
    val it = xs.iterator()
    while (it.hasNext()) s += it.next().v
    return s
}

fun main() {
    println(sum(emptyList()))
}
