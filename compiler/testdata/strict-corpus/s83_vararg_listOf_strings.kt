// ACCEPT: listOf / joinToString over Managed values produced from Strict fields
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class User(val name: String)

fun main() {
    val a = User("a")
    val b = User("b")
    println(listOf(a.name, b.name).joinToString(","))
    a.free()
    b.free()
}
