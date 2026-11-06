// super.p / super<C>.p and super.p = v call the superclass accessor, not the overriding one.
open class M() { open var b: Int = 0 }
class N() : M() {
    val a: Int
        get() { super.b = super.b + 1; return super.b + 1 }
    override var b: Int = a + 1
    val superb: Int get() = super.b
}

open class C { open val p1 = "<C>" }
class C1 : C() { override val p1 = super<C>.p1 + "<C1>" }
open class C2 : C()
class C3 : C2() { override val p1 = super<C2>.p1 + "<C3>" }

fun main() {
    val n = N()
    n.a; n.b; n.superb
    println("${n.b} ${n.a} ${n.superb}")
    println(C1().p1)
    println(C3().p1)
}
