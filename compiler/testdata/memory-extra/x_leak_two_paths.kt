// REJECT M0102: only `b` leaks on the early return
@file:MemoryMode(Mode.Strict)

package memory.extra.x_leak_two_paths

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run(n: Int): Int {
    val a = Box(1)
    val b = Box(2)
    if (n > 0) {
        a.free()
        return 1
    }
    a.free()
    b.free()
    return 0
}

fun main() {
    context(Heap()) { println(run(1)) }
}
