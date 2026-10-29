@file:MemoryMode(Mode.Strict)

import mini.memory.*

// A node owns the rest of the list through its `next` field.
class Node(val v: Int, var next: Node?)

context(h: Heap)
fun push(list: @Owned Node?, v: Int): @Owned Node = Node(v, list)   // moves `list` into the new node

fun length(n: Node?): Int {
    var c = 0
    @Borrowed var cur = n
    while (cur != null) {
        c++
        cur = cur.next
    }
    return c
}

fun main() {
    context(Heap()) {
        var list: Node? = null
        for (i in 1..5) list = push(list, i)
        println(length(list))
        list.free()            // frees the head and, through its owned field, the whole chain
    }
}
