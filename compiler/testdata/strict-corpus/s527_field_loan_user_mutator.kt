// REJECT M0101: a call of a function of the program on the object may change its collections: it ends the loans of their elements
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node {
    val children = mutableMapOf<Char, Node>()
    fun reset() { children.clear() }
}

fun f(n: Node) {
    val c = n.children['a']
    n.reset()
    println(c?.children?.size)
}

fun main() {
    val n = Node()
    n.children['a'] = Node()
    f(n)
}
