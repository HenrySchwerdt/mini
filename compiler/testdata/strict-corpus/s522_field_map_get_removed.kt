// REJECT M0101: an element read with `get` from a map in a field is a loan: `remove` on the field ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { val children = mutableMapOf<Char, Node>() }

fun f(n: Node) {
    val c = n.children.get('a')
    n.children.remove('a')
    println(c?.children?.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
