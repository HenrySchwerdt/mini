@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Point(val x: Int, val y: Int)

context(a: Arena)
fun diagonal(n: Int): Point = Point(n, n)

fun main() {
    // The Int leaves the arena scope; the Points do not. That is allowed.
    val total = Arena().use { arena ->
        context(arena) {
            var t = 0
            for (i in 1..3) t += diagonal(i).x + diagonal(i).y
            t
        }
    }
    println(total)
}
