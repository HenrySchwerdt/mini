@file:Suppress("WRONG_MODIFIER_TARGET")
@file:CInclude("time.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("strlen")
external fun strlen(s: CPointer<ByteVar>): Long

@CName("qsort")
external fun qsort(base: COpaquePointer?, n: Long, size: Long, cmp: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>)

@CStruct("tm")
external class Tm : CStructVar {
    var tm_sec: Int
    var tm_min: Int
    var tm_hour: Int
    var tm_mday: Int
    var tm_mon: Int
    var tm_year: Int
    var tm_wday: Int
    var tm_yday: Int
    var tm_isdst: Int
    var tm_gmtoff: Long
    var tm_zone: CPointer<ByteVar>?
}

@CName("gmtime_r")
external fun gmtime_r(t: CPointer<LongVar>, out: CPointer<Tm>): CPointer<Tm>?

val ascending = staticCFunction { a: COpaquePointer?, b: COpaquePointer? ->
    a!!.reinterpret<IntVar>().pointed.value - b!!.reinterpret<IntVar>().pointed.value
}

fun main() {
    memScoped {
        println(strlen("héllo".cstr.ptr))

        val numbers = allocArray<IntVar>(5)
        val values = intArrayOf(42, 7, 19, 3, 88)
        for (i in 0 until 5) numbers[i] = values[i]
        qsort(numbers, 5L, 4L, ascending)
        for (i in 0 until 5) print("" + numbers[i] + " ")
        println()

        val seconds = alloc<LongVar>()
        seconds.value = 86400L * 365L
        val tm = alloc<Tm>()
        gmtime_r(seconds.ptr, tm.ptr)
        println("" + (tm.tm_year + 1900) + "-" + (tm.tm_mon + 1) + "-" + tm.tm_mday)
    }
}
