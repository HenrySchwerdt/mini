// ACCEPT: balanced per iteration, also on `break` and `continue`. Spec 5.7
@file:MemoryMode(Mode.Strict)

package memory.ok_loops

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run(): Int {
    var total = 0
    for (i in 0 until 10) {
        val b = Box(i)
        if (i % 2 == 0) {
            b.free()
            continue
        }
        if (i > 6) {
            b.free()
            break
        }
        total += b.v
        b.free()
    }
    return total
}

fun main() {
    context(Heap()) { println(run()) }
}
