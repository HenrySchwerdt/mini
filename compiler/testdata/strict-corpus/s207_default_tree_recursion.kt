// ACCEPT: a recursive builder of default-allocated nodes (owning fields, fresh call results moved into constructors) and a traversal; releasing the root releases the tree
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Tree(val left: Tree?, val right: Tree?, val value: Int)

fun build(depth: Int, v: Int): Tree? =
    if (depth == 0) null else Tree(build(depth - 1, v * 2), build(depth - 1, v * 2 + 1), v)

fun sum(t: Tree?): Int = if (t == null) 0 else t.value + sum(t.left) + sum(t.right)

fun depth(t: Tree?): Int = if (t == null) 0 else 1 + maxOf(depth(t.left), depth(t.right))

fun main() {
    val t = build(4, 1)
    println(sum(t))
    println(depth(t))
    val u = build(3, 10)
    println(sum(u))
    if (u != null) u.free()
}
