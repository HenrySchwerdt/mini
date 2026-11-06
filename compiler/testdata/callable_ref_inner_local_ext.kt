// references to constructors of inner classes (`Outer::Inner`, `outer::Inner`, `::Inner`) and to local extension functions

class Outer(val tag: String) {
    inner class Inner(val n: Int) {
        override fun toString() = "$tag#$n"
    }

    inner class Pair2(val a: Int, val b: Int = 5) {
        override fun toString() = "$tag($a,$b)"
    }

    fun viaThis(): List<Inner> = listOf(1, 2).map(::Inner)
    fun ctorRef() = ::Inner
}

fun Outer.makeAll(): List<Outer.Inner> = listOf(7).map(::Inner)

class A(var x: String)

fun main() {
    val o = Outer("o")
    val unbound = Outer::Inner
    println(unbound(o, 3))
    val bound = o::Inner
    println(bound(4))
    println(o.viaThis())
    println(o.ctorRef()(9))
    println(listOf(1, 2).map(o::Inner))
    println(o.makeAll())
    // local extension functions
    fun A.localX(suffix: String) { x = x + suffix }
    val a = A("a")
    val f = A::localX
    f(a, "1")
    val g = a::localX
    g("2")
    println(a.x)
    fun Any.describe(): String = "<" + this + ">"
    println(listOf(1, 2).map(Int::describe))
    val d = "str"::describe
    println(d())
    a.apply { x = "" }
    fun A.reset() { x = "reset" }
    a.apply(A::reset)
    println(a.x)
}
