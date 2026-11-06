var order = ""

// a type parameter substituted by Double in an inline expansion: equals, not IEEE 754
inline fun <reified T : Comparable<Double>> eqReified(a: T, b: T): Boolean = a == b

// defaults of an inline function are evaluated after the arguments
fun foo() { order += "foo " }
fun bar() { order += "bar " }
inline fun baz(x: Unit = foo(), y: Unit) {}

// arguments with spread varargs keep their source order
open class A(val value: String)
inline fun orderFun(vararg cs: A, receiver: String = { order += "default "; "D" }(), init: String): String =
    cs.map { it.value }.joinToString() + "," + receiver + "," + init

// a data class does not generate members the superclass declares final
abstract class Base {
    final override fun toString() = "base"
    final override fun hashCode() = 42
    final override fun equals(other: Any?) = false
}
data class Data(val field: String) : Base()

enum class E {
    A;
    init { order += "E-init " }
}

class V { fun target(): String = "" }
private fun captureString(f: (V) -> String): Any = f
private fun captureUnit(f: (V) -> Unit): Any = f
private fun captureUnitBound(f: () -> Unit): Any = f

class Range2(val from: Int, val to: Int) { operator fun iterator() = (from..to).iterator() }
class Pair2(val i: Int) {
    operator fun component1() = i + 1
    operator fun component2() = i + 2
}

fun main() {
    println(eqReified(-0.0, 0.0))
    println(eqReified(1.5, 1.5))
    baz(y = bar())
    println(order.trim())
    order = ""
    val r = orderFun(*arrayOf({ order += "spread "; A("C") }()), init = { order += "init "; "I" }(), receiver = { order += "recv "; "R" }())
    println(r + " " + order.trim())
    val d = Data("x")
    println(d.toString() + " " + d.hashCode() + " " + d.equals(d))
    order = ""
    println(E.A.name)
    println(order.isEmpty())
    println(::V.name)
    println(captureString(V::target) == captureString(V::target))
    println(captureUnit(V::target) == captureUnit(V::target))
    println(captureString(V::target) == captureUnit(V::target))
    val v0 = V()
    println(captureUnitBound(v0::target) == captureUnitBound(v0::target))
    println(captureUnitBound(v0::target) == captureUnitBound(V()::target))
    var s = ""
    for ((_, `_`) in listOf(Pair2(1), Pair2(2))) {
        s += "$`_`;"
    }
    println(s)
}
