@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val list = ArrayList<Box>()    // a Managed collection
    val b = Box(1)
    list.add(b)                    // collections of owned values are not supported yet
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
