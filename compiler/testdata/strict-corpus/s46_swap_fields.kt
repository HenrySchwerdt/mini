// ACCEPT: a mutating method on a borrowed receiver
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun inc() { n += 1 }
}

fun bump(c: Counter, times: Int) {
    for (i in 1..times) c.inc()
}

fun main() {
    val c = Counter(0)
    bump(c, 3)
    println(c.n)
    c.free()
}
