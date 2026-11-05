// ACCEPT: is-checks and smart casts on a borrowed value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Shape
@AllocatedBy(Heap::class)
class Circle(val r: Int) : Shape
@AllocatedBy(Heap::class)
class Square(val s: Int) : Shape

fun area(s: Shape): Int = when (s) {
    is Circle -> 3 * s.r * s.r
    is Square -> s.s * s.s
    else -> 0
}

fun main() {
    val c = Circle(2)
    val q = Square(3)
    println(area(c))
    println(area(q))
    c.free()
    q.free()
}
