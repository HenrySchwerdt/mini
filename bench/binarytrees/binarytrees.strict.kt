@file:MemoryMode(Mode.Strict)

// binary-trees, Mini Strict variant: nodes come from the Heap allocator (malloc), owned by exactly one variable and
// freed explicitly (one free() of the root walks the owned children). No reference counts.
import mini.memory.*

const val MAX_DEPTH = 18

class Node(val left: Node?, val right: Node?)

context(h: Heap)
fun bottomUp(d: Int): @Owned Node = if (d == 0) Node(null, null) else Node(bottomUp(d - 1), bottomUp(d - 1))

fun check(n: Node): Int {
    val l = n.left
    return if (l == null) 1 else 1 + check(l) + check(n.right!!)
}

fun main() {
    context(Heap()) {
        val minDepth = 4
        val stretch = MAX_DEPTH + 1
        val s = bottomUp(stretch)
        println("stretch tree of depth $stretch\t check: ${check(s)}")
        s.free()
        val longLived = bottomUp(MAX_DEPTH)
        var d = minDepth
        while (d <= MAX_DEPTH) {
            val iters = 1 shl (MAX_DEPTH - d + minDepth)
            var chk = 0
            for (i in 0 until iters) {
                val t = bottomUp(d)
                chk += check(t)
                t.free()
            }
            println("$iters\t trees of depth $d\t check: $chk")
            d += 2
        }
        println("long lived tree of depth $MAX_DEPTH\t check: ${check(longLived)}")
        longLived.free()
    }
}
