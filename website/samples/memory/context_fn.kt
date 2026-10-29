@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node(val value: Int, val next: Node?)

// The allocator is a context parameter: the caller decides where the nodes live.
context(a: Arena)
fun range(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)
    return head
}

fun sum(list: Node?): Int {
    var total = 0
    @Borrowed var cur = list
    while (cur != null) {
        total += cur.value
        cur = cur.next
    }
    return total
}

fun main() {
    Arena().use { arena ->
        context(arena) {
            println(sum(range(4)))
        }
    }
}
