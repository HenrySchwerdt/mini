// REJECT M0102: the early return skips the release (the example of plan 4.3). Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_early_return

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() {
        println("close")
    }
}

fun fill(b: Buffer): Boolean = b.size > 100

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
//            ^ M0102
    if (!fill(buf)) return null
    return buf
}

fun main() {
    context(Heap()) { load(8).free() }
}
