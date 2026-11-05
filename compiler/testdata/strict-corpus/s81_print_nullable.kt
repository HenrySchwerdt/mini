// ACCEPT: printing a nullable Strict value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int)

fun main() {
    val p: P? = P(2)
    println(p)
    println("v=$p")
    val n: P? = null
    println(n)
    p?.free()
}
