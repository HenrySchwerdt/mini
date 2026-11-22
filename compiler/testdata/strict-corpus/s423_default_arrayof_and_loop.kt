// ACCEPT: arrayOf and listOf of fresh objects, loops over them, a list of lists
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Shape(val sides: Int)

fun main() {
    val shapes = arrayOf(Shape(3), Shape(4))
    var n = 0
    for (s in shapes) n += s.sides
    println(n)
    val groups = listOf(listOf(Shape(5), Shape(6)), listOf(Shape(7)))
    var m = 0
    for (g in groups) for (s in g) m += s.sides
    println(m)
    val idx = shapes.indices.sum()
    println(idx)
}
