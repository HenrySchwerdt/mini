// REJECT M0112: a Managed registry keeps a Managed wrapper of the closure, so the closure outlives the owner it borrows
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Step(val by: Int)

@MemoryMode(Mode.Managed)
class Handler(val f: () -> Int)

@MemoryMode(Mode.Managed)
class Registry {
    val hs = ArrayList<Handler>()
    fun register(f: () -> Int) { hs.add(Handler(f)) }
}

fun main() {
    val reg = Registry()
    val s = Step(1)
    reg.register { s.by }
    s.free()
    println(reg.hs[0].f())
}
