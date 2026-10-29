// REJECT M0112: an owned value captured by a lambda that escapes. Spec 5.5, 7.12
@file:MemoryMode(Mode.Strict)

package memory.capture_escaping_lambda

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun later(): () -> Int {
    val b = Box(3)
    return { b.v }
//         ^ M0112
}

fun main() {
    context(Heap()) { println(later()()) }
}
