// ACCEPT: default-allocated values in a map, a set and a pair
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val m = HashMap<String, Item>()
    m["a"] = Item(1)
    m.put("b", Item(2))
    val old = m.put("a", Item(3))
    println(old?.id)
    println(m["a"]?.id)
    val removed = m.remove("b")
    println(removed?.id)
    println(m.size)
    val p = Pair(Item(5), Item(6))
    println(p.first.id + p.second.id)
    val s = HashSet<Item>()
    val x = Item(9)
    s.add(x)
    s.add(x)
    println(s.size)
}
