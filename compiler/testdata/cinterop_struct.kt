// C interop: structs. The layout is computed by the compiler from the field types with the C rules and checked
// against the C compiler's (rec_size / rec_offset_*), nested structs, in-place arrays, pointer fields, arrays of structs.
@file:Suppress("WRONG_MODIFIER_TARGET")
@file:CInclude("cinterop_lib.h")
@file:CInclude("time.h")

import kotlin.native.CName
import kotlinx.cinterop.*

@CStruct("point_t")
external class Point : CStructVar {
    var x: Short
    var y: Short
}

@CStruct("record_t")
external class Record : CStructVar {
    var id: Int
    var score: Double
    @CArray(16)
    val name: CPointer<ByteVar>
    val pos: Point
    var flag: Byte
    var big: Long
    var label: CPointer<ByteVar>?
    var ratio: Float
}

@CStruct("node_t")
external class Node : CStructVar {
    var value: Int
    var next: CPointer<Node>?
}

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

@CStruct("timespec")
external class Timespec : CStructVar {
    var tv_sec: Long
    var tv_nsec: Long
}

@CName("rec_fill")
external fun rec_fill(r: CPointer<Record>, id: Int)

@CName("rec_size")
external fun rec_size(): Int

@CName("rec_offset_pos")
external fun rec_offset_pos(): Int

@CName("rec_offset_label")
external fun rec_offset_label(): Int

@CName("node_push")
external fun node_push(head: CPointer<Node>?, value: Int): CPointer<Node>?

@CName("node_free_all")
external fun node_free_all(head: CPointer<Node>?)

@CName("gmtime_r")
external fun gmtime_r(t: CPointer<LongVar>, out: CPointer<Tm>): CPointer<Tm>?

@CName("timegm")
external fun timegm(tm: CPointer<Tm>): Long

@CName("clock_gettime")
external fun clock_gettime(clk: Int, ts: CPointer<Timespec>): Int

fun describe(r: Record): String =
    "#" + r.id + " " + r.name.toKString() + " score=" + r.score + " pos=(" + r.pos.x + "," + r.pos.y + ") flag=" + r.flag + " big=" + r.big +
        " label=" + r.label!!.toKString() + " ratio=" + r.ratio

fun main() {
    // the layout the compiler computes is the one the C compiler uses
    println(sizeOf<Record>() == rec_size().toLong())
    println(sizeOf<Point>())
    println(sizeOf<Record>())
    println(rec_offset_pos())
    println(rec_offset_label())

    memScoped {
        val r = alloc<Record>()
        rec_fill(r.ptr, 7)
        println(describe(r))
        r.id = 8
        r.score = r.score + 0.25
        r.pos.x = 123
        r.flag = 0
        r.big = r.big * 2
        r.ratio = 0.5f
        println(describe(r))
        println(r.name[0].toInt())

        // an array of structs: elements are views into the memory, not copies
        val recs = allocArray<Record>(3)
        for (i in 0 until 3) rec_fill(recs[i].ptr, i + 1)
        recs[1].score = 100.0
        var total = 0.0
        for (i in 0 until 3) total += recs[i].score
        println(total)
        println(recs[2].name.toKString())
    }

    // a linked list built by C, walked and freed
    var head: CPointer<Node>? = null
    for (v in 1..4) head = node_push(head, v * 11)
    var cur = head
    var sum = 0
    while (cur != null) {
        sum += cur.pointed.value
        println(cur.pointed.value)
        cur = cur.pointed.next
    }
    println(sum)
    node_free_all(head)

    // libc structs: struct tm / timespec with the layout of this platform
    memScoped {
        val t = alloc<LongVar>()
        t.value = 86400L * 366L + 3661L
        val tm = alloc<Tm>()
        val res = gmtime_r(t.ptr, tm.ptr)
        println(res != null)
        println("" + (tm.tm_year + 1900) + "-" + (tm.tm_mon + 1) + "-" + tm.tm_mday + " " + tm.tm_hour + ":" + tm.tm_min + ":" + tm.tm_sec + " wday=" + tm.tm_wday + " yday=" + tm.tm_yday)
        tm.tm_mday = 1
        tm.tm_mon = 0
        tm.tm_hour = 0
        tm.tm_min = 0
        tm.tm_sec = 0
        println(timegm(tm.ptr))
        val ts = alloc<Timespec>()
        println(clock_gettime(0, ts.ptr))
        println(ts.tv_sec > 1000000000L && ts.tv_nsec >= 0L && ts.tv_nsec < 1000000000L)
    }
}
