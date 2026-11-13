// nullable generics, Any?, equality of nulls, nullable receivers, elvis precedence, safe-cast
fun String?.orEmpty2() = this ?: ""
fun <T : Any> T?.orDefault(d: T): T = this ?: d
fun <T> T.describe() = if (this == null) "null" else "v" + this
fun Any?.str() = "[" + this + "]"
fun main() {
    val a: String? = null; val b: String? = "b"
    println(a.orEmpty2().length); println(b.orEmpty2()); println(a.orDefault("d")); println(b.orDefault("d"))
    println(a.describe()); println(5.describe()); println(a.str()); println(b.str()); println(null.str())
    println(a == null); println(a == b); println(a?.equals(b)); println(b?.equals("b")); println(null == null); println(a?.hashCode()); println(a.hashCode())
    println((a ?: b)?.length); println(1 + (a?.length ?: 0) * 2); println(a ?: b ?: "c"); println(a ?: "x" + "y")
    val l: List<Int?> = listOf(1, null, 3); println(l.sumOf { it ?: 0 }); println(l.count { it == null }); println(l.indexOf(null)); println(l.contains(null))
    val m: Map<String, String?> = mapOf("k" to null); println(m["k"]); println(m.containsKey("k")); println(m["z"]); println(m.getOrDefault("k", "d")); println(m.getOrElse("k") { "e" })
    val o: Any? = 5; println(o as? String); println(o as? Int); println(o is Int?); println((o as Int?)?.plus(1))
    val n: Int? = null; println(n as Int?); try { println(n as Int) } catch (e: NullPointerException) { println("npe") } catch (e: ClassCastException) { println("cce") }
    var v: String? = "q"; v = null; println(v); v = v ?: "again"; println(v)
    val arr = arrayOfNulls<String>(2); arr[0] = "z"; println(arr.toList()); println(arr.filterNotNull())
    println(listOf<String?>(null, "a").map { it?.length }); println(if (a != null) a.length else -1)
    println("${a}" + "${b}"); println("v=$a")
}
