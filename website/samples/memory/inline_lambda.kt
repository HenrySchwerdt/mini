@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    repeat(2) { i ->           // inline lambda: the checker reads the body like ordinary code
        val b = Box(i)
        println(b.v)
        b.free()
    }
    val c = Box(9)
    run {
        println(c.v)
        c.free()               // freeing an outer owner inside an inline lambda is fine
    }
}

fun main() {
    context(Heap()) { run() }
}
