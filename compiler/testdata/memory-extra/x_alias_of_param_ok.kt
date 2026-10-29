// ACCEPT: aliases of a borrowed parameter
@file:MemoryMode(Mode.Strict)

package memory.extra.x_alias_of_param_ok

import mini.memory.*

class Node(val v: Int, var next: Node?)

fun sum(n: Node?): Int {
    var total = 0
    var cur = n
    while (cur != null) {
        total += cur.v
        cur = cur.next
    }
    return total
}

fun main() {
    println(sum(null))
}
