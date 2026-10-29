@file:MemoryMode(Mode.Strict)

import kotlinx.cinterop.*
import mini.memory.*

fun dangling(): CPointer<IntVar> {
    val p = memScoped {
        val x = alloc<IntVar>()
        x.value = 1
        x.ptr          // the memory is freed when memScoped ends
    }
    return p
}

fun main() {
    println(dangling().pointed.value)
}
