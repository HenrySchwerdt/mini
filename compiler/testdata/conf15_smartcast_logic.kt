// smart casts through &&, ||, !, early return, when subjects, while conditions
fun len(x: Any?): Int = if (x is String && x.length > 2) x.length else -1
fun notStr(x: Any?): Int { if (x !is String) return -1; return x.length }
fun orCase(x: Any?): String = if (x !is Int || x < 0) "bad" else "nat" + x
fun elvis(x: Any?): Int { val s = x as? String ?: return -2; return s.length }
fun whenSubj(x: Any): String = when (x) { is Int -> "i" + (x + 1); is String -> "s" + x.uppercase(); is List<*> -> "l" + x.size; else -> "?" }
fun whenNoSubj(x: Any?): String = when { x == null -> "null"; x is Int && x > 5 -> "big" + x; x is Int -> "small" + x; else -> "other" }
fun loop(x: Any?): Int { var c = 0; var y = x; while (y is Int && y > 0) { c++; y = y - 1 }; return c }
fun negatedOr(a: String?, b: String?): Int = if (a == null || b == null) 0 else a.length + b.length
fun nestedIf(x: Any?): String { if (x is String) { if (x.isEmpty()) return "empty" ; return "s" + x[0] }; return "n" }
fun main() {
    println(len("abc")); println(len("a")); println(len(3))
    println(notStr("hey")); println(notStr(null))
    println(orCase(-3)); println(orCase(4)); println(orCase("x"))
    println(elvis("zz")); println(elvis(1))
    println(whenSubj(1) + whenSubj("a") + whenSubj(listOf(1, 2)) + whenSubj(2.0))
    println(whenNoSubj(null) + whenNoSubj(9) + whenNoSubj(1) + whenNoSubj("q"))
    println(loop(4)); println(loop("x"))
    println(negatedOr("ab", "c")); println(negatedOr(null, "c"))
    println(nestedIf("")); println(nestedIf("xy")); println(nestedIf(1))
}
