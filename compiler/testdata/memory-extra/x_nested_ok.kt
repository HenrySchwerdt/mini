// ACCEPT: an owned field tree is released with its root
@file:MemoryMode(Mode.Strict)

package memory.extra.x_nested_ok

import mini.memory.*

class Leaf(val v: Int)
class Holder(var leaf: Leaf?)

context(h: Heap)
fun run() {
    val holder = Holder(Leaf(1))
    println(holder.leaf?.v)
    holder.free()
}

fun main() {
    context(Heap()) { run() }
}
