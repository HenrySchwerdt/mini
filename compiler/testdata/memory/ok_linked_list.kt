// ACCEPT: owned fields; freeing the head releases the whole list (field glue). Spec 5.3
@file:MemoryMode(Mode.Strict)

package memory.ok_linked_list

import mini.memory.*

class Node(val v: Int, var next: Node?)

context(h: Heap)
fun push(list: @Owned Node?, v: Int): @Owned Node = Node(v, list)

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
        println(list?.v)
        list.free()
    }
}
