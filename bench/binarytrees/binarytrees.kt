// binary-trees: allocation heavy. Kotlin/JVM and Mini Managed compile this very file.
const val MAX_DEPTH = 18

class Node(val left: Node?, val right: Node?)

fun bottomUp(d: Int): Node = if (d == 0) Node(null, null) else Node(bottomUp(d - 1), bottomUp(d - 1))

fun check(n: Node): Int {
    val l = n.left
    return if (l == null) 1 else 1 + check(l) + check(n.right!!)
}

fun main() {
    val minDepth = 4
    val stretch = MAX_DEPTH + 1
    println("stretch tree of depth $stretch\t check: ${check(bottomUp(stretch))}")
    val longLived = bottomUp(MAX_DEPTH)
    var d = minDepth
    while (d <= MAX_DEPTH) {
        val iters = 1 shl (MAX_DEPTH - d + minDepth)
        var chk = 0
        for (i in 0 until iters) chk += check(bottomUp(d))
        println("$iters\t trees of depth $d\t check: $chk")
        d += 2
    }
    println("long lived tree of depth $MAX_DEPTH\t check: ${check(longLived)}")
}
