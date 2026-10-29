// REJECT M0105: the value of memScoped carries a pointer into the scope out of it
@file:MemoryMode(Mode.Strict)

package memory.extra.x_cinterop_scope_escape_value

import kotlinx.cinterop.*
import mini.memory.*

fun leak(): CPointer<IntVar> {
    val p = memScoped {
        val x = alloc<IntVar>()
        x.value = 1
        x.ptr
    }
    return p
}

fun main() {
    println(leak().pointed.value)
}
