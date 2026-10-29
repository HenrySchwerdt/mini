// ACCEPT: `val b = a` is a borrowed alias; reading through it while the owner is alive is fine. Spec 5.1
@file:MemoryMode(Mode.Strict)

package memory.ok_alias_borrow

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(5)
    val b = a
    @Borrowed val c = a
    println(a.v + b.v + c.v)
    a.free()
}

fun main() {
    context(Heap()) { run() }
}
