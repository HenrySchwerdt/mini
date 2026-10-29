@file:MemoryMode(Mode.Strict)

package strict.arena_tree

import mini.memory.*

// Binary trees in arenas: no reference counting, no individual frees, everything dies with the arena.
// Also a Heap object that lives longer than the arena and is filled from it, and nested arenas.

class Tree(val value: Int, val left: Tree?, val right: Tree?)

class Counter(var n: Int)

context(a: Arena)
fun make(depth: Int, seed: Int): Tree? {
    if (depth == 0) return null
    return Tree(seed, make(depth - 1, seed * 2), make(depth - 1, seed * 2 + 1))
}

fun sum(t: Tree?): Int {
    if (t == null) return 0
    return t.value + sum(t.left) + sum(t.right)
}

fun height(t: Tree?): Int {
    if (t == null) return 0
    val l = height(t.left)
    val r = height(t.right)
    return 1 + if (l > r) l else r
}

fun count(t: Tree?, c: Counter) {
    if (t == null) return
    c.n++
    count(t.left, c)
    count(t.right, c)
}

fun main() {
    var grand = 0
    for (round in 1..3) {
        Arena().use { arena ->
            context(arena) {
                val t = make(round + 3, 1)
                grand += sum(t) + height(t)
            }
        }
    }
    println(grand)

    context(Heap()) {
        val c = Counter(0)
        Arena().use { outer ->
            context(outer) {
                val big = make(6, 1)
                count(big, c)
                Arena().use { inner ->
                    context(inner) {
                        val small = make(3, 5)
                        count(small, c)
                        println(sum(small))
                    }
                }
                println(sum(big))
            }
        }
        println(c.n)
        c.free()
    }
}
