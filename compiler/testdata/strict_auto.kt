@file:MemoryMode(Mode.Strict)

package strict.auto

import mini.memory.*

// Objects without a visible allocator that do not escape ("stack" objects, docs/memory-model.md 2.1): no free, no
// obligations. They hold reference counted Strings (released at the end of the scope), in loops, with early returns,
// exceptions and aliases.

class Label(val text: String, val size: Int)

class Acc(var total: Int, var last: String)

fun describe(l: Label): String = l.text + ":" + l.size

fun classify(n: Int): Int {
    val l = Label("n" + n, n)
    if (n < 0) return -1
    if (n == 0) return 0
    val alias = l
    return describe(alias).length + alias.size
}

fun fail(n: Int): Int {
    val l = Label("fail" + n, n)
    if (n > 2) throw IllegalArgumentException("too big " + l.text)
    return l.size
}

fun main() {
    var sum = 0
    for (i in -1..4) sum += classify(i)
    println(sum)

    val acc = Acc(0, "start")
    for (i in 1..3) {
        val l = Label("item" + i, i * 3)
        acc.total += l.size
        acc.last = describe(l)
        if (i == 2) continue
        println(describe(l))
    }
    println(acc.total.toString() + " " + acc.last)

    var caught = 0
    for (i in 1..4) {
        try {
            caught += fail(i)
        } catch (e: IllegalArgumentException) {
            println("caught " + e.message)
        }
    }
    println(caught)
}
