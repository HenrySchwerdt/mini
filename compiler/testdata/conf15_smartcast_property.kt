// smart casts on val properties (same module, no custom getter), local vals, vars captured/unchanged
class A(val s: String?, var t: String?) {
    val len: Int get() = s?.length ?: 0
    fun f(): Int { if (s != null) return s.length; return -1 }
    fun g(): Int { if (t != null) { val l = t!!.length; return l }; return -1 }
    fun h(): String { val local = t; if (local != null) return local.uppercase(); return "none" }
    fun i(): String { return if (s is String) s.reversed() else "" }
}
open class B { open val v: String? = "b" }
class C : B() { override val v: String? = null }
fun viaOpen(b: B): Int { return b.v?.length ?: -1 }
data class D(val x: Any?)
fun d(d: D): String { if (d.x is Int) return "int" + (d.x + 1); if (d.x is String) return d.x.lowercase(); return "?" }
fun lazyCast(x: Any?): Int { val y = x; if (y is CharSequence) return y.length; return 0 }
fun main() {
    val a = A("abc", "xyz")
    println(a.f()); println(a.g()); println(a.h()); println(a.i()); println(a.len)
    a.t = null; println(a.g()); println(a.h())
    println(A(null, null).f())
    println(viaOpen(B())); println(viaOpen(C()))
    println(d(D(4))); println(d(D("ABC"))); println(d(D(2.0)))
    println(lazyCast(StringBuilder("hello"))); println(lazyCast(5))
    var v: Any = "s"
    if (v is String) println(v.length)
    v = 5
    if (v is Int) println(v + 1)
}
