@file:MemoryMode(Mode.Strict)

package strict.close

import mini.memory.*

// free() runs close() first (AutoCloseable), then releases the owned fields and the memory; use {} on Heap and
// Pool objects; close() of an arena object is allowed (it does not free); null is accepted.

class Conn(val name: String, var open: Boolean) : AutoCloseable {
    override fun close() {
        open = false
        println("close " + name)
    }
}

class Plain(val tag: String)

context(h: Heap)
fun connect(i: Int): @Owned Conn = Conn("conn" + i, true)

context(h: Heap)
fun maybe(flag: Boolean): @Owned Conn? = if (flag) Conn("maybe", true) else null

fun main() {
    context(Heap()) {
        val a = connect(1)
        println(a.name + " " + a.open)
        a.free()

        connect(2).use { println("using " + it.name) }

        val m1 = maybe(true)
        m1.free()
        val m2 = maybe(false)
        m2.free()

        val p = Plain("plain")
        println(p.tag)
        p.free()
    }
    Pool<Conn>(2).use { pool ->
        context(pool) {
            val c = Conn("pooled", true)
            println(c.open)
            c.free()
        }
    }
    Arena().use { arena ->
        context(arena) {
            val c = Conn("arena", true)
            println(c.open)
            c.close()
            println(c.open)
        }
    }
}
