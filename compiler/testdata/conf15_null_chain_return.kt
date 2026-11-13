// ?.let / ?: with return, throw, break, continue; safe calls chains; !! messages
class Node(val next: Node?, val v: Int)
fun depth(n: Node?): Int { val x = n ?: return 0; return 1 + depth(x.next) }
fun lastV(n: Node?): Int? = n?.next?.next?.v
fun firstNeg(l: List<Int?>): Int { for (x in l) { val y = x ?: continue; if (y < 0) return y }; return 0 }
fun until(l: List<String?>): String { val sb = StringBuilder(); for (s in l) { s ?: break; sb.append(s) }; return sb.toString() }
fun t(x: Int?): Int = x ?: throw IllegalArgumentException("x is null")
fun lets(s: String?): String = s?.let { it.uppercase() + "!" } ?: "nothing"
fun alsoRun(s: String?): Int { var c = 0; s?.also { c += 1 }?.run { c += length }; return c }
fun chain(s: String?): Int? = s?.trim()?.takeIf { it.isNotEmpty() }?.length
fun main() {
    println(depth(Node(Node(Node(null, 1), 2), 3))); println(depth(null))
    println(lastV(Node(Node(Node(null, 7), 2), 3))); println(lastV(Node(null, 1)))
    println(firstNeg(listOf(1, null, -4, -5))); println(firstNeg(listOf(null, 2)))
    println(until(listOf("a", "b", null, "c")))
    println(t(5)); try { t(null) } catch (e: IllegalArgumentException) { println(e.message) }
    println(lets("x")); println(lets(null)); println(alsoRun("abc")); println(alsoRun(null))
    println(chain("  hi ")); println(chain("   ")); println(chain(null))
    val s: String? = null
    try { println(s!!.length) } catch (e: NullPointerException) { println("npe") }
    val m = mapOf("a" to 1); println(m["a"]!! + 1); try { m["b"]!! } catch (e: NullPointerException) { println("npe2") }
    println(s?.length); println(s?.length ?: -1); println((s ?: "dflt").length); println(null?.toString()); println(s.toString()); println(s + "x")
    val x: Int? = 5; println(x?.plus(1)); println(x?.let { it * 2 } ?: 0)
    println(listOfNotNull(1, null, 2)); println(listOf(1, null).filterNotNull()); println(arrayOf("a", null).map { it ?: "-" })
}
