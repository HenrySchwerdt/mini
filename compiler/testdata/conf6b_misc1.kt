data class A(val x: Array<Int>?, val y: IntArray?, val s: String)

object C { const val x = 42 }
var a = 12
fun getC(): C { a = 123; return C }

infix fun Int.rem(other: Int) = 10
infix operator fun Int.minus(other: Int): Int = 20

fun main() {
    println(A(Array(2) { it }, IntArray(3), "q"))
    println(A(null, null, "z"))
    val f = getC().x
    println(a)
    println(f)
    println(5 rem 2)
    println(5.rem(2))
    println(5 minus 3)
    println(5.minus(3))
}
