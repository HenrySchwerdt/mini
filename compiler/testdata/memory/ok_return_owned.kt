// ACCEPT: a factory returns an owned value, the caller frees. Spec 5.1
@file:MemoryMode(Mode.Strict)

package memory.ok_return_owned

import mini.memory.*

class Counter(var n: Int)

context(h: Heap)
fun counter(start: Int): @Owned Counter {
    val c = Counter(start)
    c.n += 1
    return c
}

fun main() {
    context(Heap()) {
        val c = counter(41)
        println(c.n)
        c.free()
    }
}
