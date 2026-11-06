fun eq3(a: Double?, b: Double?) = a != null && b != null && a == b
fun eq4(a: Double?, b: Double?) = if (a is Double && b is Double) a == b else null!!
fun eq5(a: Any?, b: Any?) = if (a is Double && b is Double) a == b else null!!
fun lt(x: Any, y: Any) = x is Double && y is Float && x < y
fun ne(x: Any, y: Any) = x is Double && y is Float && x != y
fun eqN(a: Double?, b: Double?) = a == b

fun main() {
    println(eq3(-0.0, 0.0))
    println(eq4(-0.0, 0.0))
    println(eq5(-0.0, 0.0))
    println(eq5(Double.NaN, Double.NaN))
    println(eq3(Double.NaN, Double.NaN))
    println(ne(0.0, -0.0F))
    println(lt(0.0, -0.0F))
    println(eqN(-0.0, 0.0))
    println(eqN(null, null))
}
