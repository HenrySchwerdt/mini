// ACCEPT: free of T? (null is a no-op) and a null branch carries no obligation. Spec 5.10
@file:MemoryMode(Mode.Strict)

package memory.ok_nullable_free

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run(flag: Boolean) {
    var b: Box? = null
    if (flag) b = Box(1)
    println(b?.v)
    b.free()
    var c: Box? = Box(2)
    if (c != null) {
        println(c.v)
        c.free()
        c = null
    }
    c?.free()
}

fun main() {
    context(Heap()) {
        run(true)
        run(false)
    }
}
