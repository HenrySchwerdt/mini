// finally and side effects, exceptions in finally, exceptions in lambdas, nested functions throwing through
var log = StringBuilder()
fun a() { try { b() } finally { log.append("A") } }
fun b() { try { c() } finally { log.append("B") } }
fun c() { log.append("C"); throw IllegalStateException("from c") }
fun g(): Int { try { return compute() } finally { log.append("G") } }
fun compute(): Int { log.append("K"); return 42 }
fun h() { try { throw RuntimeException("one") } finally { throw IllegalArgumentException("two") } }
inline fun <T> guarded(f: () -> T): T { try { return f() } finally { log.append("!") } }
fun main() {
    try { a() } catch (e: IllegalStateException) { log.append("E") }; println(log); log.clear()
    println(g()); println(log); log.clear()
    try { h() } catch (e: Exception) { println(e::class.simpleName + " " + e.message) }
    println(guarded { 5 }); println(log); log.clear()
    try { guarded { throw Exception("x") } } catch (e: Exception) { log.append("c") }; println(log); log.clear()
    val l = listOf(1, 2, 3)
    try { l.forEach { if (it == 2) throw RuntimeException("stop at " + it); log.append(it) } } catch (e: RuntimeException) { log.append(e.message) }; println(log); log.clear()
    println(l.map { try { 10 / (it - 2) } catch (e: ArithmeticException) { -1 } })
    val r = runCatching { l.first { it > 5 } }; println(r.exceptionOrNull() is NoSuchElementException)
    fun local(n: Int): Int { if (n == 0) throw UnsupportedOperationException("zero"); return n }
    println(listOf(1, 0).map { runCatching { local(it) }.getOrDefault(-1) })
    var count = 0; while (true) { try { count++; if (count == 3) break } finally { log.append("w") } }; println(log); println(count)
}
