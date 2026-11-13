// ACCEPT: collections held in fields: reads without a change in between, other fields changed, a re-read after a change, a loan taken in a loop, the trie walk
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node(val tag: Char) {
    val children = sortedMapOf<Char, Node>()
    val notes = mutableListOf<Node>()
    var hits = 0
}

class Trie {
    private val root = Node('^')

    fun insert(word: String) {
        var n = root
        for (c in word) {
            val next = n.children[c]
            if (next == null) {
                val fresh = Node(c)
                n.children[c] = fresh
                n = n.children.getValue(c)
            } else {
                n = next
            }
            n.hits++
        }
    }

    fun commonPrefix(): String {
        val sb = StringBuilder()
        var n = root
        while (n.children.size == 1) {
            val (c, child) = n.children.entries.first()
            sb.append(c)
            n = child
        }
        return sb.toString()
    }

    fun count(word: String): Int {
        var n: Node? = root
        for (c in word) n = n?.children?.get(c)
        return n?.hits ?: 0
    }
}

fun walk(n: Node, depth: Int): Int {
    var total = depth
    for (child in n.children.values) total += walk(child, depth + 1)
    return total
}

fun main() {
    val t = Trie()
    t.insert("tree")
    t.insert("trie")
    t.insert("tr")
    println(t.commonPrefix())
    println(t.count("tr") + t.count("tri") + t.count("x"))

    val n = Node('x')
    n.children['a'] = Node('a')
    val a = n.children['a']
    n.notes.add(Node('z'))             // another field changes: the loan of an element of `children` stays
    println(a?.tag)
    n.children['b'] = Node('b')        // a change, then a re-read
    val b = n.children['b']
    println(b?.tag)
    println(walk(n, 0))
}
