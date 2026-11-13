// REJECT M0101: what is read through the `values` view of a map in a field is a loan of that field: `put` on the field ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { val children = mutableMapOf<Char, Node>() }

fun f(n: Node) {
    val c = n.children.values.first()
    n.children['b'] = Node()
    println(c.children.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
