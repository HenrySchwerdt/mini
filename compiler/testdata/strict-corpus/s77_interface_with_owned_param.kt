// ACCEPT: an interface method that takes ownership; implementation frees
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Sink { fun accept(b: @Owned Box) }
@AllocatedBy(Heap::class)
class Box(val v: Int)
class Printer : Sink {
    override fun accept(b: @Owned Box) { println(b.v); b.free() }
}

fun main() {
    val s: Sink = Printer()
    s.accept(Box(9))
}
