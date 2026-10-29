// REJECT M0104: the lambda of a thread boundary takes the value with it. Spec 5.9, 7.4
@file:MemoryMode(Mode.Strict)

package memory.move_thread

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    val t = spawn {
        println(b.v)
        b.free()
    }
    println(b.v)
//          ^ M0104
    t.join()
}

fun main() {
    context(Heap()) { run() }
}
