// ACCEPT: an anonymous object implementing an interface, used locally
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Greeter { fun greet(): String }

@AllocatedBy(Heap::class)
class Name(val s: String)

fun main() {
    val n = Name("Ann")
    val s = n.s
    val g = object : Greeter { override fun greet(): String = "hi " + s }
    println(g.greet())
    n.free()
}
