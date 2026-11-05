// Parent <-> child trees: children keep a reference to their parent.
class TreeNode(val name: String, var parent: TreeNode?) {
    val children = ArrayList<TreeNode>()

    fun addChild(n: String): TreeNode {
        val c = TreeNode(n, this)
        children.add(c)
        return c
    }

    fun depth(): Int {
        var d = 0
        var p = parent
        while (p != null) { d++; p = p.parent }
        return d
    }

    fun count(): Int {
        var n = 1
        for (c in children) n += c.count()
        return n
    }

    fun path(): String = if (parent == null) name else parent!!.path() + "/" + name
}

fun build(root: TreeNode, depth: Int, fan: Int) {
    if (depth == 0) return
    for (i in 0 until fan) build(root.addChild("n$depth.$i"), depth - 1, fan)
}

fun main() {
    var total = 0
    for (round in 0 until 300) {
        val root = TreeNode("root", null)
        build(root, 4, 3)
        total += root.count()
        total += root.children[0].children[0].children[0].depth()
    }
    println(total)
    val keep = TreeNode("keep", null)
    build(keep, 5, 3)
    println(keep.count())
    println(keep.children[2].children[1].children[0].path())
    // a subtree is detached and dropped, the rest survives
    val sub = keep.children[1]
    keep.children.removeAt(1)
    sub.parent = null
    println(keep.count())
    println(sub.count())
    println("done")
}
