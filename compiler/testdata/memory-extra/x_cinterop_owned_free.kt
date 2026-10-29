// REJECT M0102 / M0103 / M0101 / M0107: `@Owned(free = "fn")` on an external function makes its result a C allocation
// the caller must release by calling `fn`; pointers from other C functions are externally managed
@file:MemoryMode(Mode.Strict)

package memory.extra.x_cinterop_owned_free

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.memory.*

@Owned(free = "free")
@CName("malloc")
external fun cMalloc(n: Long): CPointer<ByteVar>?

@CName("free")
external fun cFree(p: CPointer<ByteVar>?)

@CName("getenv")
external fun getenv(name: CPointer<ByteVar>): CPointer<ByteVar>?

fun leak() {
    val p = cMalloc(16L)
    p!![0] = 1
}

fun released() {
    val p = cMalloc(16L)
    p!![0] = 1
    cFree(p)
}

fun twice() {
    val p = cMalloc(16L)
    cFree(p)
    cFree(p)
}

fun useAfter(): Byte {
    val p = cMalloc(16L)
    cFree(p)
    return p!![0]
}

fun external(): Boolean = memScoped {
    // not owned: nothing to release
    val home = getenv("HOME".cstr.ptr)
    home == null
}

fun scoped() {
    memScoped {
        val x = alloc<IntVar>()
        cFree(x.ptr.reinterpret())
    }
}

fun main() {
    leak()
    released()
    twice()
    println(useAfter())
    println(external())
    scoped()
}
