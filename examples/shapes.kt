// Classes, interfaces, data classes, `when` with smart casts and null safety.
interface Shape {
    fun area(): Double
    val name: String get() = "shape"
}

data class Circle(val r: Double) : Shape {
    override fun area() = 3.141592653589793 * r * r
    override val name get() = "circle"
}

data class Rect(val w: Double, val h: Double) : Shape {
    override fun area() = w * h
    override val name get() = "rect"
}

fun describe(s: Shape?): String = when (s) {
    null -> "nothing"
    is Circle -> "a circle with radius ${s.r}"
    is Rect -> if (s.w == s.h) "a square" else "a ${s.w}x${s.h} rectangle"
    else -> "something else"
}

fun main() {
    val a = Circle(1.0)
    val b = Rect(2.0, 3.0)
    println(a)
    println("${b.name}: area ${b.area()}")
    println(describe(a))
    println(describe(Rect(2.0, 2.0)))
    println(describe(null))
    println(a == Circle(1.0))
    val sb = StringBuilder()
    sb.append("kotlin".uppercase()).append(' ').append("goes native ")
    println(sb.toString().trim())
}
