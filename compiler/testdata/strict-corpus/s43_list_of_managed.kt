// ACCEPT: Managed classes can live in Kotlin collections
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@MemoryMode(Mode.Managed)
class Tag(val name: String)

fun main() {
    val xs = mutableListOf(Tag("b"), Tag("a"))
    println(xs.sortedBy { it.name }.map { it.name })
    println(xs.groupBy { it.name.length }.size)
}
