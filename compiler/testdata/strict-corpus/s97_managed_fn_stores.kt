// REJECT M0110: an unannotated Managed library function that stores its argument (inferred from its body)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

@MemoryMode(Mode.Managed)
class Keeper { var kept: Box? = null }

@MemoryMode(Mode.Managed)
object Lib { fun keep(k: Keeper, b: Box) { k.kept = b } }

fun main() {
    val k = Keeper()
    val b = Box(1)
    Lib.keep(k, b)
    b.free()
}
