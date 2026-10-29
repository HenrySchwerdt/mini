// REJECT M0104: the owner moved away inside its scope; an alias declared outside is dead. Spec 5.1, 7.4
@file:MemoryMode(Mode.Strict)

package memory.borrow_scope_moved

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run(n: Int) {
    var alias: Box? = null
    if (n > 0) {
        val a = Box(n)
        alias = a
        consume(a)
    }
    println(alias?.v)
//          ^ M0104
}

fun main() {
    context(Heap()) { run(1) }
}
