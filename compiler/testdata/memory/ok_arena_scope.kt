// ACCEPT: a list in an arena, everything dies with the scope; no free needed. Spec 2.4
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_scope

import mini.memory.*

class Node(val v: Int, val next: Node?)

context(a: Arena)
fun build(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)
    return head
}

fun sum(n: Node?): Int {
    var s = 0
    @Borrowed var cur = n
    while (cur != null) {
        s += cur.v
        cur = cur.next
    }
    return s
}

fun main() {
    Arena().use { arena ->
        context(arena) {
            val list = build(4)
            println(sum(list))
        }
    }
}
