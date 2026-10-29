// REJECT M0102: `continue` skips the release
@file:MemoryMode(Mode.Strict)

package memory.extra.x_leak_continue

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    for (i in 0 until 3) {
        val b = Box(i)
        if (i == 1) continue
        b.free()
    }
}

fun main() {
    context(Heap()) { run() }
}
