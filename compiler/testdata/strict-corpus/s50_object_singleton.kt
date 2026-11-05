// ACCEPT: object declarations are not tracked; a companion factory with an allocator
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Id(val n: Int) {
    companion object {
        fun of(n: Int): Id = Id(n)
    }
}

object Registry {
    var count = 0
    fun register() { count += 1 }
}

fun main() {
    Registry.register()
    println(Registry.count)
    val i = Id.of(3)
    println(i.n)
    i.free()
}
