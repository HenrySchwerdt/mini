// ACCEPT: arena objects hold primitives, string literals and other objects of the same arena. Spec 2.4
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_plain_data

import mini.memory.*

class Leaf(val id: Int, val tag: String)

class Tree(val left: Leaf, val right: Leaf)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val t = Tree(Leaf(1, "left"), Leaf(2, "right"))
            println(t.left.tag + " " + t.right.tag + " " + (t.left.id + t.right.id))
        }
    }
}
