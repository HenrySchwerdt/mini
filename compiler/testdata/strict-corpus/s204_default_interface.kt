// ACCEPT: objects of the default allocator behind an interface and an open class: a factory returns them, the caller owns and releases them
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Shape {
    fun area(): Int
}

class Square(val side: Int) : Shape {
    override fun area() = side * side
}

class Rect(val w: Int, val h: Int) : Shape {
    override fun area() = w * h
}

fun shapeOf(n: Int): Shape = if (n % 2 == 0) Square(n) else Rect(n, n + 1)

fun total(n: Int): Int {
    var t = 0
    for (i in 1..n) {
        val s = shapeOf(i)
        t += s.area()
    }
    return t
}

fun main() {
    println(total(4))
    val s = shapeOf(3)
    println(s.area())
}
