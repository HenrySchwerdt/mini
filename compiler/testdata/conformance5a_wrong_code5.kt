// More regressions found by the Kotlin box tests (docs/conformance.md, fifth round, a).

// ---- IEEE comparison of Double / Double? ----
fun eq1(a: Double, b: Double?) = a == b
fun eq2(a: Double?, b: Double?) = a == b
fun eq3(a: Any?, b: Any?) = if (a is Double && b is Double?) a == b else null!!
fun eq4(a: Float?, b: Float) = a == b

// ---- the minimum Int literal ----
fun minInt(x: Int): String = when (x) {
    2_147_483_647 -> "MAX"
    -2_147_483_648 -> "MIN"
    else -> "else"
}

// ---- labels of infix and constructor lambda arguments ----
infix fun Int.myMap(x: () -> Unit) {}

fun infixLabel(x: Int): Int {
    x myMap {
        return@myMap
    }
    return x
}

fun arrayLabel(): String = Array<String>(1) { return@Array "from array" }[0]

// ---- references to inherited members and to members of an object ----
open class P {
    protected val v = "protected"
    protected var w = ""
}

class Q : P() {
    fun foo(): String {
        Q::w.set(this, "K")
        return ::v.get() + Q::w.get(this)
    }
}

object Holder {
    val a: String = "object"
}

inline fun callIt(s: () -> String): String = s()

// ---- return as an argument of an inlined function ----
inline fun alwaysOk(s: String, fn: (String) -> String): String = fn(return "returned")

// ---- the Unit value as Any, break as an operand ----
fun unitAsAny(): String {
    val x = println("side effect") as Any
    return if (x == Unit) "unit" else "other"
}

// ---- the literal of a super constructor call takes the instantiated type parameter (Long) ----
open class Base<T>(val value: T)
class LongBox : Base<Long>(-1)

// ---- `operator fun not` that does not return Boolean ----
class NA
var holder = 0

operator fun NA.not(): NA {
    holder++
    return this
}

fun main() {
    println(LongBox().value == -1L)
    !!!!!NA()
    println(holder)
    println(eq1(-0.0, 0.0))
    println(eq1(Double.NaN, Double.NaN))
    println(eq2(null, null))
    println(eq2(0.0, null))
    println(eq2(-0.0, 0.0))
    println(eq3(-0.0, 0.0))
    println(eq4(0.0f, -0.0f))
    println(minInt(0) + " " + minInt(Int.MAX_VALUE) + " " + minInt(Int.MIN_VALUE))
    println(infixLabel(5))
    println(arrayLabel())
    println(Q().foo())
    println(callIt(Holder::a))
    println(alwaysOk("x") { it })
    println(unitAsAny())
}
