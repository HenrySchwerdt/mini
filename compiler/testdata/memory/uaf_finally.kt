// REJECT M0101: freed in `finally`, used after the try. Spec 5.6, 7.1
@file:MemoryMode(Mode.Strict)

package memory.uaf_finally

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    try {
        println(b.v)
    } finally {
        b.free()
    }
    println(b.v)
//          ^ M0101
}

fun main() {
    context(Heap()) { run() }
}
