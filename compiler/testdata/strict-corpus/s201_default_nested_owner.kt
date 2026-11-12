// ACCEPT: objects of the default allocator moved into owning fields: the owner of the tree releases all of it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)
class Holder(val item: Item, val tag: Int)

class Node(val value: Int, val next: Node?)

fun build(n: Int): Holder = Holder(Item(n), n + 100)

fun push(list: @Owned Node?, v: Int): Node = Node(v, list)

fun sum(n: Node?): Int {
    var s = 0
    var cur = n
    while (cur != null) {
        s += cur.value
        cur = cur.next
    }
    return s
}

fun main() {
    val h = build(7)
    println(h.item.id)
    println(h.tag)
    var list: Node? = null
    for (i in 1..5) list = push(list, i)
    println(sum(list))
    val h2 = Holder(Item(9), 1)
    val h3 = Holder(Item(h2.item.id + 1), 2)
    println(h2.item.id + h3.item.id)
}
