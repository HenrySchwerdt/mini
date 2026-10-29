@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(41)
    val t = spawn {            // b is moved into the thread
        println(b.v + 1)
        b.free()               // ... which must free it
    }
    t.join()
}

fun main() {
    context(Heap()) { run() }
}
