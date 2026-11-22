// ACCEPT: many default-allocated objects in a list, replaced, removed and cleared in loops (leak and use-after-free checked by the sanitizers)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node(val id: Int)

fun main() {
    val xs = ArrayList<Node>()
    for (round in 1..3) {
        for (i in 1..200) xs.add(Node(i))
        var total = 0
        for (n in xs) total += n.id
        println(total)
        var i = 0
        while (i < xs.size) {
            if (xs[i].id % 2 == 0) {
                val gone = xs.removeAt(i)
                total -= gone.id
            } else {
                i++
            }
        }
        println(total)
        println(xs.size)
        xs.clear()
    }
    val m = HashMap<Int, Node>()
    for (i in 1..100) m[i % 10] = Node(i)
    println(m.size)
    var k = 0
    for ((key, v) in m) k += v.id
    println(k)
}
