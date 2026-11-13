// REJECT M0101: an element of a list held in a field (`kids[0]`, `first()`) is a loan: `add` on the field ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Tree {
    val kids = mutableListOf<Tree>()

    fun f(): Int {
        val k = kids.first()
        kids.add(Tree())
        return k.kids.size
    }
}

fun main() {
    val t = Tree()
    t.kids.add(Tree())
    println(t.f())
}
