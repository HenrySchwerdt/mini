// REJECT M0105: a pointer allocated in the memScoped scope is stored in a global that outlives it; also stored in a field
@file:MemoryMode(Mode.Strict)

package memory.extra.x_cinterop_scope_escape_global

import kotlinx.cinterop.*
import mini.memory.*

var saved: CPointer<IntVar>? = null

class Holder {
    var p: CPointer<IntVar>? = null
}

fun main() {
    val h = Holder()
    memScoped {
        val x = alloc<IntVar>()
        x.value = 1
        saved = x.ptr
        h.p = x.ptr
    }
    println(h.p == null)
}
