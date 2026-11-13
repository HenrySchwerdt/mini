sealed interface Shape
data class Circle(val r: Int) : Shape
data class Rect(val w: Int, val h: Int) : Shape
object Dot : Shape

fun classify(n: Int): String = when (val m = n % 5) {
    0 -> "zero"
    1, 2 -> "low$m"
    in 3..4 -> "high$m"
    else -> "?"
}

fun area(s: Shape): Int = when (s) {
    is Circle -> 3 * s.r * s.r
    is Rect -> s.w * s.h
    Dot -> 0
}

fun parse(s: String): Int? = when (val v = s.toIntOrNull()) {
    null -> null
    in 0..9 -> v
    else -> v / 10
}

fun main() {
    for (i in 0..7) println(classify(i))
    println(listOf(Circle(2), Rect(3, 4), Dot).map { area(it) })
    println(listOf("5", "42", "x").map { parse(it) })
    val t = when {
        classify(1) == "low1" && area(Dot) == 0 -> "both"
        else -> "none"
    }
    println(t)
    val v: Any = 3.5
    println(when (v) { is Int -> "int"; is Double -> "double ${v * 2}"; else -> "other" })
}
