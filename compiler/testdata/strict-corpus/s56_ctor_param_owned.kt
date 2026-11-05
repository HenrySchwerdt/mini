// ACCEPT: a constructor takes ownership of a property parameter; the parent frees
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Leaf(val v: Int)

@AllocatedBy(Heap::class)
class Tree(val left: Leaf, val right: Leaf) {
    fun total(): Int = left.v + right.v
}

fun main() {
    val t = Tree(Leaf(1), Leaf(2))
    println(t.total())
    t.free()
}
