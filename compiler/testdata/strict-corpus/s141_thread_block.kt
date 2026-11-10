// REJECT M0107: `Thread { }` takes over what it captures (it runs on its own), so the owner is not the caller's any more and freeing it is wrong
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    val t = Thread { println(b.v) }
    t.start()
    b.free()
    t.join()
}
