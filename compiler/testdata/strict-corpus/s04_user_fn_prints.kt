// ACCEPT: a function that only prints its parameter borrows it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int)

fun describe(p: P) {
    println("describe: $p")
}

fun main() {
    val p = P(7)
    describe(p)
    describe(p)
    p.free()
}
