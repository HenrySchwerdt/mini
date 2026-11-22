// REJECT M0204: an object of the default allocator cannot be moved into a thread (a thread needs a ThreadSafe allocator). Spec 2.2a, 7.15
@file:MemoryMode(Mode.Strict)

package memory.default_into_thread

import mini.memory.*

class Box(val v: Int)

fun main() {
    val b = Box(1)
    val t = Thread { println(b.v) }
//                   ^ M0204
    t.start()
    t.join()
}
