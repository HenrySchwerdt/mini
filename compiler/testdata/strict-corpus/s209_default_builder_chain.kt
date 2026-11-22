// ACCEPT: the fluent builder and apply/also/let around objects of the default allocator
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Builder {
    var parts = 0
    var total = 0
    fun add(n: Int): Builder {
        parts += 1
        total += n
        return this
    }
    fun result(): Summary = Summary(parts, total)
}

class Summary(val parts: Int, val total: Int)

fun main() {
    val s = Builder().add(1).add(2).add(3).result()
    println(s.parts.toString() + " " + s.total)
    val b = Builder().apply { add(10) }
    println(b.total)
    val t = Summary(1, 2).also { println(it.total) }
    println(t.parts)
    val n = Summary(3, 4).let { it.parts + it.total }
    println(n)
}
