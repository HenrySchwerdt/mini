// CharSequence is an interface implemented by String, StringBuilder and user classes.
class Rope(val s: String) : CharSequence {
    override val length: Int get() = s.length
    override fun get(index: Int): Char = s[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Rope(s.substring(startIndex, endIndex))
    override fun toString() = s
}

fun len(c: CharSequence): Int = c.length
fun last(c: CharSequence): Char = c[c.length - 1]

fun describe(x: Any): String = if (x is CharSequence) "cs:" + x.length else "other"

fun main() {
    val sb = StringBuilder("xyz")
    val r = Rope("rope")
    println(len("hello"))
    println(len(sb))
    println(len(r))
    println(last("abc"))
    println(last(sb))
    println(last(r))
    val parts = listOf<CharSequence>("ab", sb, r)
    println(parts.map { it.length })
    println(parts[1].subSequence(1, 3))
    println(r.subSequence(1, 3))
    var s = ""
    for (c in sb as CharSequence) s += c
    println(s)
    for ((i, c) in (r as CharSequence).withIndex()) s += "$i$c"
    println(s)
    var n = 0
    for (i in (sb as CharSequence).indices) n += i
    println(n)
    println(describe("str"))
    println(describe(sb))
    println(describe(r))
    println(describe(42))
    sb.append("!")
    val view: CharSequence = sb
    println(view.length)
    println(view)
}
