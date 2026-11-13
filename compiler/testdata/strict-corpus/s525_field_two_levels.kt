// REJECT M0101: the collection is held two levels down (`n.a.children`): its mutation ends the loan of an element read through the same path
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { val children = mutableMapOf<Char, Node>() }
class Holder { val a = Node() }

fun f(h: Holder) {
    val c = h.a.children['x']
    h.a.children.clear()
    println(c?.children?.size)
}

fun main() {
    val h = Holder()
    h.a.children['x'] = Node()
    f(h)
}
