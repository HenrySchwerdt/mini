// REJECT M0105: `return` carries a pointer allocated in the memScoped scope out of it
@file:MemoryMode(Mode.Strict)

package memory.extra.x_cinterop_scope_escape_return

import kotlinx.cinterop.*
import mini.memory.*

fun leak(): CPointer<IntVar>? {
    memScoped {
        val x = alloc<IntVar>()
        x.value = 1
        return x.ptr
    }
}

fun main() {
    println(leak() == null)
}
