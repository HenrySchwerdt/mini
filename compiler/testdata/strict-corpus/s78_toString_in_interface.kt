// ACCEPT: calling toString/hashCode through an interface-typed borrowed value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Named { val name: String }
@AllocatedBy(Heap::class)
class A(override val name: String) : Named

fun show(n: Named) {
    println(n.name + " " + n.toString().isNotEmpty() + " " + (n.hashCode() == n.hashCode()))
}

fun main() {
    val a = A("a")
    show(a)
    a.free()
}
