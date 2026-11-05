// ACCEPT: a visitor interface with a borrowed parameter implemented by a Managed collector
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Visitor { fun visit(n: Node) }
@AllocatedBy(Heap::class)
class Node(val v: Int, val next: Node?) {
    fun accept(vis: Visitor) {
        vis.visit(this)
        next?.accept(vis)
    }
}
@MemoryMode(Mode.Managed)
class Sum : Visitor {
    var total = 0
    override fun visit(n: Node) { total += n.v }
}

fun main() {
    val l = Node(1, Node(2, null))
    val s = Sum()
    l.accept(s)
    println(s.total)
    l.free()
}
