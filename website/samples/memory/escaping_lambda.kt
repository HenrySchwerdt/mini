@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun later(): () -> Int {
    val b = Box(3)
    return { b.v }             // the lambda escapes and would have to own `b`
}

fun main() {
    context(Heap()) { println(later()()) }
}
