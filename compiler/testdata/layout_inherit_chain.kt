// A three-level chain whose subclasses fill the tail padding of their superclasses: A ends with an Int (4 bytes of tail padding
// before the Long-aligned size), B adds Boolean/Short/Byte (fits that gap), C adds an Int, a Long and a reference. All fields of
// all levels must stay independent, also through virtual calls and a superclass-typed variable.
open class A(var a1: Long, var a2: Int) {
    open fun sum(): Long = a1 + a2
    override fun toString(): String = "A($a1,$a2)"
}

open class B(a1: Long, a2: Int, var b1: Boolean, var b2: Short, var b3: Byte, var b4: String) : A(a1, a2) {
    override fun sum(): Long = super.sum() + (if (b1) 1000 else 0) + b2 + b3
    override fun toString(): String = "B($a1,$a2,$b1,$b2,$b3,$b4)"
}

class C(a1: Long, a2: Int, b1: Boolean, b2: Short, b3: Byte, b4: String, var c1: Int, var c2: Long, var c3: A?, var c4: Boolean) :
    B(a1, a2, b1, b2, b3, b4) {
    override fun sum(): Long = super.sum() + c1 + c2 + (c3?.sum() ?: 0L) + (if (c4) 5 else 0)
    override fun toString(): String = "C($a1,$a2,$b1,$b2,$b3,$b4,$c1,$c2,$c3,$c4)"
}

fun main() {
    val a = A(1L shl 33, 7)
    val b = B(2L shl 33, 8, true, -9, 10, "bee")
    val c = C(3L shl 33, 9, false, 11, -12, "sea", 13, 14L shl 33, a, true)
    println(a)
    println(b)
    println(c)
    println(a.sum())
    println(b.sum())
    println(c.sum())
    val xs: List<A> = listOf(a, b, c)
    println(xs.map { it.sum() })
    c.a2 = -1
    c.b1 = true
    c.b3 = 127
    c.c1 = Int.MIN_VALUE
    c.c4 = false
    println(c)
    b.b2 = 32000
    b.a2 = 100
    println(b)
    println(a)
    (c as B).b4 = "renamed"
    (c as A).a1 = 5
    println(c)
    println(xs.sumOf { it.sum() })
}
