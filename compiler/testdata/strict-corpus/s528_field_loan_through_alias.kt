// REJECT M0101: a local that stands for the field's collection (`val m = n.children`) mutates the field: `m.clear()` ends the loans read through `n.children`
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { val children = mutableMapOf<Char, Node>() }

fun f(n: Node) {
    val c = n.children['a']
    val m = n.children
    m.clear()
    println(c?.children?.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
