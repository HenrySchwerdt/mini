import geom.*
import mini.memory.*

fun main() {
    val a = Vec(1, 2)
    val b = Vec(3, -4)
    val c = sum(a, b)
    println(describe(c))
    println(a.dot(b))
    println(b.len2())
    println(b.manhattan())
    val d = scale(c, 3)
    println(describe(d))
    val box = boundingBox(a, b)
    println("box " + box.w + "x" + box.h + " area " + box.area() + " perimeter " + box.perimeter())
    println(box.contains(a))
    val area = measure("area") { box.area() * 2 }
    println(area)
    d.free()
    c.free()
    box.free()
    b.free()
    a.free()
}
