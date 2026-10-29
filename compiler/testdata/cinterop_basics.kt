// C interop (docs/plan-kotlin.md section 7): external functions, C variables, pointers, memScoped, C strings.
@file:CInclude("string.h")
@file:CInclude("stdlib.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("strlen")
external fun strlen(s: CPointer<ByteVar>): Long

@CName("abs")
external fun cAbs(x: Int): Int

@CName("labs")
external fun cLabs(x: Long): Long

@CName("fabs")
external fun cFabs(x: Double): Double

@CName("sqrtf")
external fun cSqrtf(x: Float): Float

@CName("toupper")
external fun cToupper(c: Int): Int

@CName("strcmp")
external fun strcmp(a: CPointer<ByteVar>, b: CPointer<ByteVar>): Int

@CName("memset")
external fun memset(dst: COpaquePointer, value: Int, n: Long): COpaquePointer?

@CName("atoi")
external fun atoi(s: CPointer<ByteVar>): Int

@CName("getenv")
external fun getenv(name: CPointer<ByteVar>): CPointer<ByteVar>?

// the Kotlin name is the C symbol when there is no @CName
external fun strtol(s: CPointer<ByteVar>, end: CPointer<CPointerVar<ByteVar>>?, base: Int): Long

fun main() {
    // scalars through C
    println(cAbs(-7))
    println(cLabs(-5000000000L))
    println(cFabs(-2.5))
    println(cSqrtf(2.0f))
    println(cToupper('q'.code).toChar())

    memScoped {
        // C variables: .value, .ptr, .pointed
        val i = alloc<IntVar>()
        i.value = 41
        i.value = i.value + 1
        println(i.value)
        val p: CPointer<IntVar> = i.ptr
        p.pointed.value = 7
        println(i.value)

        val l = alloc<LongVar>()
        l.value = 1L shl 40
        val d = alloc<DoubleVar>()
        d.value = 1.5
        val f = alloc<FloatVar>()
        f.value = 0.1f
        val b = alloc<ByteVar>()
        b.value = (-3).toByte()
        val s = alloc<ShortVar>()
        s.value = 1234.toShort()
        val flag = alloc<BooleanVar>()
        flag.value = true
        println(l.value)
        println(d.value * 2)
        println(f.value)
        println(b.value)
        println(s.value + 1)
        println(flag.value)
        println(sizeOf<IntVar>() + sizeOf<LongVar>() + sizeOf<DoubleVar>() + sizeOf<ByteVar>() + sizeOf<ShortVar>() + sizeOf<FloatVar>() + sizeOf<CPointerVar<ByteVar>>())

        // arrays and pointer arithmetic
        val a = allocArray<IntVar>(5)
        for (k in 0 until 5) a[k] = k * k
        var sum = 0
        for (k in 0 until 5) sum += a[k]
        println(sum)
        val third = a + 2
        println(third!![0])
        println(third[1])
        println((a + 4)!! - a)
        val back = (a + 4)!! - 3
        println(back[0])

        // C strings
        val text = "hello, wörld"
        val cs = text.cstr.ptr
        println(strlen(cs))
        println(cs.toKString())
        println(strcmp("abc".cstr.ptr, "abd".cstr.ptr) < 0)
        println(strcmp("abc".cstr.ptr, "abc".cstr.ptr))
        println(atoi("1234".cstr.ptr) + 1)
        println(strtol("ff".cstr.ptr, null, 16))

        // bytes by hand
        val raw = allocArray<ByteVar>(8)
        memset(raw, 65, 7)
        raw[7] = 0
        println(raw.toKString())
        raw[3] = 'Z'.code.toByte()
        println(raw.toKString())

        // pointer variables
        val pp = alloc<CPointerVar<IntVar>>()
        println(pp.value == null)
        pp.value = a
        println(pp.value!![4])
        pp.value = null
        println(pp.value == null)

        val missing = getenv("MINI_CINTEROP_SURELY_NOT_SET".cstr.ptr)
        println(missing == null)
    }

    // the C heap: everything allocated must be freed
    val h = nativeHeap.alloc<IntVar>()
    h.value = 99
    println(h.value)
    nativeHeap.free(h)
    val arr = nativeHeap.allocArray<DoubleVar>(3)
    arr[0] = 0.5
    arr[1] = 1.5
    arr[2] = 2.5
    println(arr[0] + arr[1] + arr[2])
    nativeHeap.free(arr)
}
