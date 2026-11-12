// REJECT M0101: the parameter of `forEach` is a loan of an element: the lambda clears the list and uses it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    xs.forEach { b ->
        xs.clear()
        println(b.v)
    }
}

fun main() {
    f(mutableListOf())
}
