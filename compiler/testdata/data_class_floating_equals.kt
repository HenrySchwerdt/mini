data class T(val z1: Double, val z2: Double?, val f: Float)
fun main() {
    val x = T(Double.NaN, Double.NaN, Float.NaN); val y = T(Double.NaN, Double.NaN, Float.NaN)
    println(x == y)
    println(T(0.0, 0.0, 0f) == T(-0.0, -0.0, -0f))
    println(x.hashCode() == y.hashCode())
    println(Double.NaN.equals(Double.NaN)); println(0.0.equals(-0.0))
    val a: Any = Double.NaN; println(a == a); println(a.equals(Double.NaN))
    println(listOf(Double.NaN) == listOf(Double.NaN))
}
