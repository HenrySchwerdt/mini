// Any members (toString/equals/hashCode) overridden in classes that also implement interfaces
// must stay in the fixed Any vtable slots.
interface I { fun f(): Int }
interface Named { val name: String }

data class Q(val y: Int) : I { override fun f() = y }

class Custom(override val name: String) : Named, I {
    override fun f() = name.length
    override fun toString() = "Custom($name)"
    override fun equals(other: Any?) = other is Custom && other.name == name
    override fun hashCode() = name.hashCode()
}

fun main() {
    val q = Q(2)
    println(q)
    println(q == Q(2))
    println(q.hashCode() == Q(2).hashCode())
    val i: I = q
    println(i)
    val c = Custom("x")
    val n: Named = c
    println(n)
    println(c == Custom("x"))
    println(c.hashCode() == "x".hashCode())
    println("$c and ${c.f()}")
}
