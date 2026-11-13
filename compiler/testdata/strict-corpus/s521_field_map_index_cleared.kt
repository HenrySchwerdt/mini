// REJECT M0101: an element read with `n.children['a']` from a map held in a field is a loan of that field: `n.children.clear()` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { val children = sortedMapOf<Char, Node>() }

fun f(n: Node) {
    val c = n.children['a']
    n.children.clear()
    println(c?.children?.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
