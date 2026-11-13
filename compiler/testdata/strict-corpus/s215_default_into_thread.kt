// REJECT M0204: an object of the default allocator cannot be moved into a thread (it needs a ThreadSafe allocator)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val b = Box(1)
    val t = Thread { println(b.v) }
    t.start()
    t.join()
}
