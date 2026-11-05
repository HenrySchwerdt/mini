// ACCEPT: use {} closes (and frees) the resource
@file:MemoryMode(Mode.Strict)

import mini.memory.*


fun main() {
    Arena().use { a ->
        context(a) {
            val b = Box(1)
            println(b.v)
        }
    }
}
class Box(val v: Int)
