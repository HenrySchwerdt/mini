// ACCEPT: a class with an owning nullable field filled later and released by its owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Part(val v: Int)

@AllocatedBy(Heap::class)
class Machine {
    var part: Part? = null
    fun install(p: @Owned Part) { part = p }
}

fun main() {
    val m = Machine()
    m.install(Part(4))
    println(m.part?.v)
    m.free()
}
