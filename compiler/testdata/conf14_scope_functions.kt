data class Cfg(var a: Int = 0, var b: String = "")

fun main() {
    val c = Cfg().apply { a = 5; b = "x" }
    println(c)
    val n = c.also { println("also ${it.a}") }.a
    println(n)
    val r = c.let { it.a * 2 }
    println(r)
    val w = with(c) { a + b.length }
    println(w)
    val s = c.run { "$a$b" }
    println(s)
    val t = 5.takeIf { it > 3 }
    println(t)
    println(5.takeUnless { it > 3 })
    val nul: String? = null
    println(nul?.let { it.length } ?: -1)
    println(nul.orEmpty().length)
    val x: String? = "hey"
    x?.let { println(it.uppercase()) }
    println(run { 1 + 2 })
    println(listOf(1, 2, 3).also { println(it.size) }.map { it * 2 })
    val sb = StringBuilder().apply { append("a"); append("b") }.toString()
    println(sb)
    println(c.copy().apply { a = 9 }.a + c.a)
    println(with("abc") { length + first().code })
    val res = Cfg().let { cfg -> cfg.a = 3; cfg }.run { a }
    println(res)
    println(repeat(3) { print(it) })
    println(if (x != null && x.length > 2) "long" else "short")
    println(x?.takeIf { it.startsWith("h") }?.drop(1))
    println(listOf(1, 2).let { (a, b) -> a + b })
    lateinit var later: String
    later = "set"
    println(later)
    val (p, q) = Cfg(1, "z")
    println("$p$q")
}
