// C interop: callbacks (staticCFunction), calling C function pointers, all scalar C types in signatures, variadic calls.
@file:CInclude("cinterop_lib.h")
@file:CInclude("stdio.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("qsort")
external fun qsort(base: COpaquePointer?, n: Long, size: Long, cmp: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>)

@CName("bsearch")
external fun bsearch(key: COpaquePointer?, base: COpaquePointer?, n: Long, size: Long, cmp: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>): COpaquePointer?

@CName("apply_binop")
external fun apply_binop(f: CPointer<CFunction<(Int, Int) -> Int>>, a: Int, b: Int): Int

@CName("fold")
external fun fold(f: CPointer<CFunction<(Int, Int) -> Int>>, a: CPointer<IntVar>, n: Int, init: Int): Int

@CName("visit_range")
external fun visit_range(from: Int, to: Int, cb: CPointer<CFunction<(Int, COpaquePointer?) -> Unit>>, ctx: COpaquePointer?)

@CName("sum_ints")
external fun sum_ints(a: CPointer<IntVar>, n: Int): Int

@CName("scale_doubles")
external fun scale_doubles(a: CPointer<DoubleVar>, n: Int, k: Double)

@CName("scale_float")
external fun scale_float(x: Float, k: Float): Float

@CName("negate_byte")
external fun negate_byte(b: Byte): Byte

@CName("add_short")
external fun add_short(a: Short, b: Short): Short

@CName("is_even")
external fun is_even(x: Int): Byte

@CName("mix")
external fun mix(a: Int, b: Float, c: Double, d: Long): Double

@CName("counter_next")
external fun counter_next(): Int

@CVariadic(3)
@CName("snprintf")
external fun snprintf_i(buf: CPointer<ByteVar>, n: Long, fmt: CPointer<ByteVar>, v: Int): Int

@CVariadic(3)
@CName("snprintf")
external fun snprintf_dl(buf: CPointer<ByteVar>, n: Long, fmt: CPointer<ByteVar>, d: Double, l: Long): Int

@CVariadic(3)
@CName("snprintf")
external fun snprintf_sf(buf: CPointer<ByteVar>, n: Long, fmt: CPointer<ByteVar>, s: CPointer<ByteVar>, f: Float, b: Byte): Int

// a callback that is a top-level value
val byValue = staticCFunction { a: COpaquePointer?, b: COpaquePointer? ->
    val x = a!!.reinterpret<IntVar>().pointed.value
    val y = b!!.reinterpret<IntVar>().pointed.value
    if (x < y) -1 else if (x > y) 1 else 0
}

val descending = staticCFunction { a: COpaquePointer?, b: COpaquePointer? ->
    val x = a!!.reinterpret<IntVar>().pointed.value
    val y = b!!.reinterpret<IntVar>().pointed.value
    y - x
}

fun maxOf2(a: Int, b: Int): Int = if (a > b) a else b

fun collect(value: Int, ctx: COpaquePointer?) {
    val total = ctx!!.reinterpret<IntVar>()
    total.pointed.value = total.pointed.value + value
}

fun callTwice(f: CPointer<CFunction<(Int, Int) -> Int>>, x: Int): Int = f(f(x, x), x)

fun main() {
    memScoped {
        val n = 9
        val values = allocArray<IntVar>(n)
        val src = intArrayOf(42, 7, 19, 3, 88, 1, 56, 23, 7)
        for (i in 0 until n) values[i] = src[i]
        qsort(values, n.toLong(), 4L, byValue)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(values[i]).append(' ')
        println(sb.toString())
        qsort(values, n.toLong(), 4L, descending)
        sb.setLength(0)
        for (i in 0 until n) sb.append(values[i]).append(' ')
        println(sb.toString())
        qsort(values, n.toLong(), 4L, byValue)
        val key = alloc<IntVar>()
        key.value = 56
        val hit = bsearch(key.ptr, values, n.toLong(), 4L, byValue)
        println(hit != null)
        println(hit!!.reinterpret<IntVar>().pointed.value)
        key.value = 55
        println(bsearch(key.ptr, values, n.toLong(), 4L, byValue) == null)
        println(sum_ints(values, n))
        println(fold(staticCFunction(::maxOf2), values, n, 0))
        println(fold(staticCFunction { acc: Int, x: Int -> acc * 2 + x }, values, 3, 1))

        // a context pointer, as libuv callbacks get it
        val total = alloc<IntVar>()
        visit_range(1, 11, staticCFunction(::collect), total.ptr)
        println(total.value)

        val ds = allocArray<DoubleVar>(3)
        ds[0] = 1.0
        ds[1] = 2.5
        ds[2] = -4.0
        scale_doubles(ds, 3, 2.0)
        println(ds[0] + ds[1] + ds[2])
    }

    // function pointers are values: store them, pass them, call them
    val add = staticCFunction { a: Int, b: Int -> a + b }
    val mul = staticCFunction { a: Int, b: Int -> a * b }
    println(apply_binop(add, 20, 22))
    println(apply_binop(mul, 6, 7))
    println(add(1, 2) + mul(3, 4))
    println(callTwice(add, 5))
    var acc = 1
    acc = add(acc + 2, 3)
    acc = mul(acc + 2, 3)
    println(acc)
    val noArgs = staticCFunction { -> 99 }
    println(noArgs())
    val unit = staticCFunction { x: Int -> if (x < 0) println("negative") }
    unit(-1)

    // every scalar C type in a signature
    println(scale_float(1.5f, 4.0f))
    println(negate_byte(5))
    println(negate_byte((-128).toByte()))
    println(add_short(30000.toShort(), 30000.toShort()))
    println(is_even(10))
    println(is_even(7))
    println(mix(1, 0.5f, 0.25, 1L shl 33))
    println(counter_next() + counter_next())

    // variadic C functions
    memScoped {
        val buf = allocArray<ByteVar>(80)
        val n1 = snprintf_i(buf, 80L, "answer=%d".cstr.ptr, 42)
        println(n1.toString() + " " + buf.toKString())
        snprintf_dl(buf, 80L, "%.2f|%lld".cstr.ptr, 3.14159, 1L shl 40)
        println(buf.toKString())
        snprintf_sf(buf, 80L, "%s:%.1f:%d".cstr.ptr, "tag".cstr.ptr, 2.5f, (-5).toByte())
        println(buf.toKString())
    }
}
