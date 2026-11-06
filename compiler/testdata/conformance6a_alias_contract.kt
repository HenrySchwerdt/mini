// constructors behind generic typealiases, inner classes behind aliases, star projections through aliases, smart casts after require / check / user contracts
import kotlin.contracts.*

class Pair2<T1, T2>(val x1: T1, val x2: T2)

typealias ST<T> = Pair2<String, T>

class Outer(val x: String) {
    inner class Inner(val y: String) {
        val z = x + y
    }
}

typealias OI = Outer.Inner

open class Node(val s: String)
class Buf(s: String) : Node(s)
open class Desc2<out T : Node>(val node: T)
typealias Desc<T> = Desc2<T>

fun describe(): Desc<*> = object : Desc<Buf>(Buf("star")) {}

open class S
class P(val str: String = "P") : S()

@OptIn(ExperimentalContracts::class)
fun check2(actual: Boolean) {
    contract { returns() implies actual }
    if (!actual) throw IllegalStateException()
}

fun main() {
    val st = ST<String>("O", "K")
    println(st.x1 + st.x2)
    println(Outer("O").OI("K").z)
    println(describe().node.s)
    val s: S = P()
    require(s is P)
    println(s.str)
    val q: S = P("Q")
    check2(q is P)
    println(q.str)
    val n: String? = "abc"
    requireNotNull(n)
    println(n.length)
    val m: String? = "xy"
    check(m != null)
    println(m.length)
}
