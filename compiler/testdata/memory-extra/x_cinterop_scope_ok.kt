// ACCEPT: memScoped is a Scoped allocator; pointers into it are used inside the scope and only plain values leave it
@file:MemoryMode(Mode.Strict)
@file:Suppress("WRONG_MODIFIER_TARGET")

package memory.extra.x_cinterop_scope_ok

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.memory.*

@CStruct("pt")
external class Pt : CStructVar {
    var x: Int
    var y: Int
}

@CName("strlen")
external fun strlen(s: CPointer<ByteVar>): Long

@CName("getenv")
external fun getenv(name: CPointer<ByteVar>): CPointer<ByteVar>?

fun fill(p: CPointer<IntVar>, n: Int) {
    for (i in 0 until n) p[i] = i * 2
}

fun sum(p: CPointer<IntVar>, n: Int): Int {
    var s = 0
    for (i in 0 until n) s += p[i]
    return s
}

fun length(text: String): Long = memScoped { strlen(text.cstr.ptr) }

fun first(p: CPointer<IntVar>): @Borrowed CPointer<IntVar> = p

fun main() {
    val total = memScoped {
        val a = allocArray<IntVar>(10)
        fill(a, 10)
        val v = alloc<IntVar>()
        v.value = sum(a, 10)
        val pt = alloc<Pt>()
        pt.x = 3
        pt.y = v.value
        val second = (a + 1)!!
        val home = getenv("HOME".cstr.ptr)
        println(home == null || strlen(home) >= 0L)
        v.value + pt.x + second[0] + first(a)[1]
    }
    println(total)
    println(length("hello"))
    val heap = nativeHeap.alloc<IntVar>()
    heap.value = 5
    println(heap.value)
    nativeHeap.free(heap)
}
