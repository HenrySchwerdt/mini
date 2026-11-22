// ACCEPT: removal returns the element with its count to the caller (a new owner); clear and removeAt release what the list held
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = ArrayList<Item>()
    for (i in 1..5) ys.add(Item(i))
    val gone = ys.removeAt(0)
    println(gone.id)
    println(ys.size)
    val replaced = ys.set(0, Item(20))
    println(replaced.id)
    val last = ys.removeLast()
    println(last.id)
    ys.clear()
    println(ys.size)
    val q = ArrayDeque<Item>()
    q.addLast(Item(7))
    q.addFirst(Item(6))
    val f = q.removeFirst()
    println(f.id)
    println(q.size)
}
