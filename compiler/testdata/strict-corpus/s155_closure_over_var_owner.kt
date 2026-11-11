// ACCEPT: a non-escaping closure that only reads a `var` owner borrows it like a `val`; the owner behind the variable may be replaced between calls
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun twice(f: () -> Int): Int = f() + f()

fun main() {
    var b = Box(1)
    val f = { b.v * 10 }
    println(f())
    println(twice { b.v })
    b.free()
    b = Box(2)
    println(f())
    var c: Box? = null
    val g = { c?.v ?: -1 }
    println(g())
    c = Box(3)
    println(g())
    c.free()
    b.free()
}
