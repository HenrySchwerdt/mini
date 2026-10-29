@file:MemoryMode(Mode.Strict)

package strict.heap_list

import mini.memory.*

// Heap-allocated singly linked list with a reference-counted String payload (released by the free glue),
// owned parameters, explicit moves, borrowed traversal, early returns and try/finally.

class Node(val name: String, val value: Int, var next: Node?)

context(h: Heap)
fun push(list: @Owned Node?, name: String, value: Int): @Owned Node = Node(name, value, list)

fun length(list: Node?): Int {
    var n = 0
    @Borrowed var cur = list
    while (cur != null) {
        n++
        cur = cur.next
    }
    return n
}

fun sum(list: Node?): Int {
    var s = 0
    @Borrowed var cur = list
    while (cur != null) {
        s += cur.value
        cur = cur.next
    }
    return s
}

fun find(list: Node?, name: String): @Borrowed Node? {
    @Borrowed var cur = list
    while (cur != null) {
        if (cur.name == name) return cur
        cur = cur.next
    }
    return null
}

context(h: Heap)
fun build(n: Int): @Owned Node? {
    var list: Node? = null
    for (i in 1..n) list = push(list.move(), "item" + i, i * i)
    return list
}

context(h: Heap)
fun risky(n: Int): Int {
    val tmp = Node("tmp" + n, n, null)
    try {
        if (n < 0) throw IllegalStateException("negative")
        return tmp.value * 2
    } finally {
        tmp.free()
    }
}

fun consume(list: @Owned Node?): Int {
    val total = sum(list)
    list.free()
    return total
}

fun main() {
    context(Heap()) {
        println(risky(21))
        try {
            risky(-1)
        } catch (e: IllegalStateException) {
            println("caught " + e.message)
        }
        val list = build(5)
        println(length(list))
        println(sum(list))
        println(find(list, "item3")?.value)
        println(find(list, "nope")?.value)
        println(consume(list.move()))
        var total = 0
        for (round in 1..3) {
            val l = build(round)
            total += sum(l)
            l.free()
        }
        println(total)
    }
}
