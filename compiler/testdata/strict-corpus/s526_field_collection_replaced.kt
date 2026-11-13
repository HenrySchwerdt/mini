// REJECT M0101: assigning the field (`n.children = ...`) ends the loans of the elements of the old collection
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node { var children = mutableMapOf<Char, Node>() }

fun f(n: Node) {
    val c = n.children['a']
    n.children = mutableMapOf()
    println(c?.children?.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
