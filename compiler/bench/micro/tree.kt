// Recursive tree building and traversal (allocation heavy, binary-trees style), plus a linked list.
class Tree(val left: Tree?, val right: Tree?)

fun make(depth: Int): Tree? {
    if (depth == 0) return Tree(null, null)
    return Tree(make(depth - 1), make(depth - 1))
}

fun check(t: Tree?): Int {
    if (t == null) return 0
    if (t.left == null) return 1
    return 1 + check(t.left) + check(t.right)
}

class Cell(val value: Int, val next: Cell?)

fun main() {
    var total = 0
    for (round in 0 until 12) {
        val t = make(16)
        total += check(t)
    }
    var s = 0L
    for (r in 0 until 30) {
        var list: Cell? = null
        for (i in 0 until 20000) list = Cell(i, list)
        var p = list
        while (p != null) {
            s += p.value
            p = p.next
        }
    }
    println(total)
    println(s)
}
