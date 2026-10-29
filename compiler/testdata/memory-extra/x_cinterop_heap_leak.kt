// REJECT M0102 / M0103 / M0101: nativeHeap is a Deallocating allocator: every allocation must be freed exactly once
@file:MemoryMode(Mode.Strict)

package memory.extra.x_cinterop_heap_leak

import kotlinx.cinterop.*
import mini.memory.*

fun leak() {
    val x = nativeHeap.alloc<IntVar>()
    x.value = 1
}

fun twice() {
    val x = nativeHeap.alloc<IntVar>()
    nativeHeap.free(x)
    nativeHeap.free(x)
}

fun after(): Int {
    val x = nativeHeap.alloc<IntVar>()
    nativeHeap.free(x)
    return x.value
}

fun oneBranch(c: Boolean) {
    val a = nativeHeap.allocArray<ByteVar>(8)
    if (c) nativeHeap.free(a)
}

fun fine(): Int {
    val x = nativeHeap.alloc<IntVar>()
    x.value = 7
    val r = x.value
    nativeHeap.free(x)
    return r
}

fun main() {
    leak()
    twice()
    println(after())
    oneBranch(true)
    println(fine())
}
