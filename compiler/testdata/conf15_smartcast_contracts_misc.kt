// smart casts after require/check/isNullOrEmpty/let and elvis-throw
fun a(s: String?): Int { require(s != null); return s.length }
fun b(s: String?): Int { checkNotNull(s); return s.length }
fun c(s: String?): String { if (s.isNullOrEmpty()) return "empty"; return s.uppercase() }
fun d(s: String?): String { if (s.isNullOrBlank()) return "blank"; return s.trim() }
fun e(x: Int?): Int { val y = x ?: throw IllegalStateException("null x"); return y + 1 }
fun f(x: Any?): Int = (x as? Int)?.plus(1) ?: 0
fun g(l: List<String>?): Int = l?.size ?: 0
fun h(m: Map<String, Int>, k: String): Int { val v = m[k] ?: return -1; return v * 2 }
fun i(s: String?): Int { s?.let { return it.length }; return -1 }
fun j(x: Any): String { x as String; return x.lowercase() }
fun k(x: Any?): String { if (x is Int?) { return if (x == null) "nullint" else "int" + (x + 1) }; return "no" }
fun main() {
    println(a("abc")); println(b("ab"))
    try { a(null) } catch (e: IllegalArgumentException) { println("iae " + e.message) }
    try { b(null) } catch (e: IllegalStateException) { println("ise " + e.message) }
    println(c("") + c(null) + c("q")); println(d("  ") + d(" x "))
    println(e(1)); try { e(null) } catch (ex: IllegalStateException) { println(ex.message) }
    println(f(3) + f("s")); println(g(null) + g(listOf("a")))
    println(h(mapOf("a" to 4), "a")); println(h(mapOf("a" to 4), "b"))
    println(i("xyz")); println(i(null))
    println(j("ABC")); try { j(1) } catch (e: ClassCastException) { println("cce") }
    println(k(null) + k(3) + k("s"))
}
