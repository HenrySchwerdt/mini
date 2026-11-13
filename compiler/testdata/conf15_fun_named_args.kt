// named arguments: order of evaluation, mixing positional and named, named with vararg, in constructors and extension functions
fun trace(s: String): String { print("[" + s + "]"); return s }
fun three(a: String, b: String, c: String) = a + b + c
fun mix(x: Int, y: Int = 10, z: Int = 100) = "$x/$y/$z"
class Pt(val x: Int, val y: Int, val label: String = "pt") { fun copyWith(x: Int = this.x, y: Int = this.y) = Pt(x, y) }
fun String.wrap(left: String = "(", right: String = ")") = left + this + right
fun va(sep: String, vararg parts: String) = parts.joinToString(sep)
data class D(val a: Int, val b: String, val c: Boolean = true)
fun main() {
    println(three(c = trace("c"), a = trace("a"), b = trace("b")))
    println(three(trace("x"), c = trace("z"), b = trace("y")))
    println(mix(1)); println(mix(1, z = 3)); println(mix(z = 3, x = 1)); println(mix(x = 5, y = 6))
    val p = Pt(y = 2, x = 1); println(p.x.toString() + p.y + p.label); val q = p.copyWith(y = 9); println(q.x.toString() + q.y)
    println("a".wrap()); println("a".wrap(right = "]")); println("a".wrap(right = ">", left = "<"))
    println(va("-", "a", "b", "c")); println(va(parts = arrayOf("x", "y"), sep = "+")); println(va(sep = ","))
    val d = D(b = "bee", a = 1); println(d); println(d.copy(b = "z")); println(d.copy(c = false, a = 5))
    println(listOf(3, 1, 2).sortedWith(compareBy { -it })); println(listOf("a", "b").joinToString(separator = "|", prefix = "<", postfix = ">")); println(listOf(1, 2, 3).joinToString(limit = 2, truncated = "..."))
    println(maxOf(a = 1, b = 5)); println(String.format("%s-%s", "a", "b")); println("x".padStart(length = 3, padChar = '*'))
    println(Triple(first = 1, second = 2, third = 3)); println(arrayOf(1, 2).let { it.size }); println(mapOf(1 to "a").getOrElse(2) { "d" })
}
