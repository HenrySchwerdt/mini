// binary-trees, Mini Arena variant: every tree lives in its own Arena (context parameter), freed in one go on close.
import mini.memory.*

const val MAX_DEPTH = 18

class Node(val left: Node?, val right: Node?)

context(a: Arena)
fun bottomUp(d: Int): Node = if (d == 0) Node(null, null) else Node(bottomUp(d - 1), bottomUp(d - 1))

fun check(n: Node): Int {
    val l = n.left
    return if (l == null) 1 else 1 + check(l) + check(n.right!!)
}

fun main() {
    val minDepth = 4
    val stretch = MAX_DEPTH + 1
    Arena().use { a ->
        context(a) { println("stretch tree of depth $stretch\t check: ${check(bottomUp(stretch))}") }
    }
    Arena().use { longArena ->
        context(longArena) {
            val longLived = bottomUp(MAX_DEPTH)
            var d = minDepth
            while (d <= MAX_DEPTH) {
                val iters = 1 shl (MAX_DEPTH - d + minDepth)
                var chk = 0
                for (i in 0 until iters) {
                    Arena().use { a -> context(a) { chk += check(bottomUp(d)) } }
                }
                println("$iters\t trees of depth $d\t check: $chk")
                d += 2
            }
            println("long lived tree of depth $MAX_DEPTH\t check: ${check(longLived)}")
        }
    }
}
