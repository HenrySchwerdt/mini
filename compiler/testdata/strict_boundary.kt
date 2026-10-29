@file:MemoryMode(Mode.Strict)

package strict.boundary

import mini.memory.*

// Strict <-> Managed boundary (docs/memory-model.md 6.2): a Managed class borrows Strict values, creates Strict values
// of its own (reference counted there), Strict code uses Managed objects and collections, a Strict class holds a
// borrowed Managed reference, and a Strict function returns a borrowed value to Managed code.

class Vec(val x: Int, val y: Int)

class Pair2(val a: Vec, @Borrowed val label: String)

fun len2(v: Vec): Int = v.x * v.x + v.y * v.y

fun pick(p: Pair2): @Borrowed Vec = p.a

@MemoryMode(Mode.Managed)
class Stats {
    var calls = 0
    val log = ArrayList<String>()

    fun record(v: @Borrowed Vec): Int {
        calls++
        log.add("rec" + calls)
        return v.x * v.y
    }

    fun makeAndMeasure(i: Int): Int {
        val local = Vec(i, 2 * i)
        calls++
        return len2(local) + record(local)
    }

    fun peek(p: @Borrowed Pair2): Int {
        val v = pick(p)
        val again = v
        return again.x + len2(again)
    }
}

fun main() {
    val stats = Stats()
    var acc = 0
    for (i in 1..4) {
        val v = Vec(i, i + 1)
        acc += stats.record(v) + len2(v)
        acc += stats.makeAndMeasure(i)
    }
    println(acc)
    println(stats.calls)
    println(stats.log.joinToString(","))

    context(Heap()) {
        val h = Vec(3, 4)
        val pair = Pair2(Vec(5, 6), "label" + stats.calls)
        println(stats.record(h))
        println(stats.peek(pair))
        println(pair.label)
        pair.free()
        h.free()
    }
    Arena().use { arena ->
        context(arena) {
            val p = Pair2(Vec(1, 1), "arena")
            println(stats.peek(p) + len2(pick(p)))
        }
    }
}
