@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Vec(val x: Int, val y: Int)

fun len2(v: Vec): Int = v.x * v.x + v.y * v.y

@MemoryMode(Mode.Managed)      // this one class opts back out of Strict
class Counter {
    var count = 0
    fun inc() {
        count++
    }

    fun report(v: @Borrowed Vec) {
        println("len2=" + len2(v) + " count=" + count)
    }
}

fun main() {
    val counter = Counter()
    val names = ArrayList<String>()
    names.add("a")
    names.add("b")
    for (n in names) counter.inc()
    counter.report(Vec(1, 2))
    println(names.joinToString(","))
}
