// property initializers and init blocks run in textual order; secondary constructors
class A(val p: Int) {
    val a = log("a")
    init { log("init1 p=$p") }
    val b = log("b")
    constructor() : this(7) { log("secondary") }
    init { log("init2") }
    val c: Int
    init { c = p * 2; log("c=$c") }
    companion object { fun log(s: String): String { println(s); return s } }
    fun log(s: String) = Companion.log(s)
}
class Lazy { val x by lazy { println("computing x"); 42 }; val y = run { println("y init"); 1 } }
class Order { var s = ""; val a = s.also { s += "a" }; val b = s.also { s += "b" }; init { s += "i" }; val c = s }
fun main() {
    A(); println("--"); A(3); println("--")
    val l = Lazy(); println("created"); println(l.x); println(l.x)
    val o = Order(); println(o.s + " " + o.a + "|" + o.b + "|" + o.c)
}
