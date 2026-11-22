// use { }, close order, suppressed exceptions
class Res(val name: String, val failClose: Boolean = false) : AutoCloseable {
    init { println("open " + name) }
    override fun close() { println("close " + name); if (failClose) throw IllegalStateException("close " + name) }
}
fun main() {
    Res("a").use { println("using " + it.name) }
    val r = Res("b").use { it.name.length }; println(r)
    try { Res("c").use { throw RuntimeException("body") } } catch (e: RuntimeException) { println(e.message) }
    try { Res("d", true).use { throw RuntimeException("body2") } } catch (e: RuntimeException) { println(e.message + " suppressed=" + e.suppressedExceptions.size); println(e.suppressedExceptions[0].message) }
    try { Res("e", true).use { println("fine") } } catch (e: IllegalStateException) { println(e.message) }
    Res("o").use { o -> Res("i").use { i -> println(o.name + i.name) } }
    val ex = RuntimeException("main"); ex.addSuppressed(IllegalArgumentException("s1")); ex.addSuppressed(Exception("s2"))
    println(ex.suppressedExceptions.map { it.message })
    try { Res("x", true).use { Res("y", true).use { throw Exception("deep") } } } catch (e: Exception) { println(e.message); println(e.suppressedExceptions.map { it.message }) }
    val t = Exception("top", Exception("mid", Exception("root"))); var c: Throwable? = t; val names = ArrayList<String?>(); while (c != null) { names.add(c.message); c = c.cause }; println(names)
}
