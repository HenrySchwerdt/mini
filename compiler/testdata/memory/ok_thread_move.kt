// ACCEPT: an owned Heap value is moved into a thread, which frees it. Spec 5.9
@file:MemoryMode(Mode.Strict)

package memory.ok_thread_move

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(41)
    val t = spawn {
        println(b.v + 1)
        b.free()
    }
    t.join()
}

fun main() {
    context(Heap()) { run() }
}
