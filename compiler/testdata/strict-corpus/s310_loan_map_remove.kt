// REJECT M0101: an element read from a map (`m[key]`) is a loan: `m.remove` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(m: MutableMap<String, Box>) {
    val x = m["a"]
    m.remove("a")
    println(x?.v)
}

fun main() {
    f(mutableMapOf())
}
