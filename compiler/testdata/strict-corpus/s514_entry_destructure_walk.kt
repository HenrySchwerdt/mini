// ACCEPT: an entry destructured from `entries.first()` in a loop (`val (c, child) = n.children.entries.first(); n = child`): the entry is a view, rebinding it each round does not end the loan of `child`
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node(val tag: Char) {
    val children = sortedMapOf<Char, Node>()
}

fun commonPrefix(root: Node): String {
    val sb = StringBuilder()
    var n = root
    while (n.children.size == 1) {
        val (c, child) = n.children.entries.first()
        sb.append(c)
        n = child
    }
    return sb.toString()
}

fun main() {
    val root = Node('^')
    val a = Node('a')
    val b = Node('b')
    a.children['b'] = b
    root.children['a'] = a
    println(commonPrefix(root))
}
