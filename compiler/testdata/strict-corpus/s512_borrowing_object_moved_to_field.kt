// REJECT M0112: an object that borrows another owner cannot be stored into an owning field
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

class Holder(val r: Reader)

fun main() {
    val c = Counter(3)
    val h = Holder(c.reader())
    println(h.r.get())
    c.free()
}
