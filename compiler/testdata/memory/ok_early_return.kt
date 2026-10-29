// ACCEPT: every path releases before leaving. Spec 5.6
@file:MemoryMode(Mode.Strict)

package memory.ok_early_return

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() {
        println("close " + size)
    }
}

fun fill(b: Buffer): Boolean = b.size > 4

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) {
        buf.free()
        return null
    }
    return buf
}

fun main() {
    context(Heap()) {
        val none = load(2)
        println(none == null)
        none.free()
        val ok = load(8)
        println(ok?.size)
        ok.free()
    }
}
