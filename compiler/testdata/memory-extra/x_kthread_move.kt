// REJECT M0104: kotlin.concurrent.thread is a thread boundary
@file:MemoryMode(Mode.Strict)

package memory.extra.x_kthread_move

import mini.memory.*

import kotlin.concurrent.thread

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    val t = thread { b.free() }
    println(b.v)
    t.join()
}

fun main() {
    context(Heap()) { run() }
}
