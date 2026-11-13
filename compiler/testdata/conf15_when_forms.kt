// when forms: ranges, commas, is, expressions as conditions, no subject, subject val, blocks, else, nested, with side effects
fun sign(x: Int) = when { x < 0 -> "neg"; x == 0 -> "zero"; else -> "pos" }
fun cls(x: Int) = when (x) { 0, 1 -> "bit"; in 2..9 -> "digit"; in 10..99 -> "two"; !in 0..999 -> "huge"; else -> "three" }
fun typ(x: Any?) = when (x) { null -> "null"; is Int, is Long -> "integral"; is Double -> "double"; is String -> if (x.isEmpty()) "empty" else "str" + x.length; is List<*> -> "list"; else -> "obj" }
fun expr(x: Int) = when (x % 3) { 0 -> "zero"; 1 -> { val y = x * 2; "one$y" }; else -> "two" }
fun side(x: Int): Int { println("eval $x"); return x }
fun order(x: Int) = when (x) { side(1), side(2) -> "a"; side(3) -> "b"; else -> "c" }
fun ch(c: Char) = when (c) { in 'a'..'z' -> "lower"; in 'A'..'Z' -> "upper"; '0', '1' -> "bin"; else -> "other" }
fun str(s: String) = when (s) { "a", "b" -> 1; "c" -> 2; else -> s.length + 10 }
fun nested(a: Int, b: Int) = when (a) { 1 -> when (b) { 1 -> "11"; else -> "1x" }; else -> "x" }
fun main() {
    println(sign(-5) + sign(0) + sign(7)); println(listOf(0, 1, 5, 42, 123, 5000, -3).map { cls(it) }); println(listOf(null, 1, 2L, 3.0, "", "ab", listOf(1), Any()).map { typ(it) })
    println((0..5).map { expr(it) }); println(order(2)); println(order(3)); println(order(9)); println(listOf('q', 'Q', '1', '#').map { ch(it) }); println(listOf("a", "c", "zzz").map { str(it) }); println(nested(1, 1) + nested(1, 2) + nested(2, 1))
    val v = when (val t = 5 * 2) { 10 -> "ten:$t"; else -> "no" }; println(v); val r = when { else -> "only else" }; println(r)
    val l = listOf(1, 2, 3); println(when (3) { in l -> "in"; else -> "out" }); println(when (l.size) { l.size -> "same"; else -> "diff" }); val u = when (1) { 1 -> println("unit branch"); else -> Unit }; println(u)
    println(when (1.0) { 1.0 -> "d"; else -> "x" }); println(when ("x" + "y") { "xy" -> "concat"; else -> "no" }); println(when (true) { true -> "t"; false -> "f" }); println(when (5) { 1 -> "a"; else -> if (true) "b" else "c" })
    val o: Any = 5; when (o) { is Int -> if (o > 3) println("big int") else println("small"); else -> println("other") }; println(if (o is String) "s" else if (o is Int) "i" else "?")
}
