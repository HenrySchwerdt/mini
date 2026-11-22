fun main() {
    var i = 0
    while (i < 3) { i++; if (i == 2) continue; print(i) }
    println()
    do { i-- } while (i > 0)
    println(i)
    val x = if (i == 0) "zero" else "nz"
    println(x)
    val y = when { i > 0 -> 1; i < 0 -> -1; else -> 0 }
    println(y)
    for (c in 'a'..'c') for (n in 1..2) if (n == 2) print("$c$n ")
    println()
    val r = (1..3).map { n -> if (n % 2 == 0) "e" else "o" }
    println(r)
    var s = 0
    for (k in 1..100) { if (k % 2 == 0) continue; if (k > 10) break; s += k }
    println(s)
    val t = try { 1 / 0 } catch (e: Exception) { -1 }
    println(t)
    val z = run { val a = 1; val b = 2; a + b }
    println(z)
    println(if (z > 2) { "gt" } else { "le" })
    val nested = if (z == 3) if (y == 1) "a" else "b" else "c"
    println(nested)
    val arr = IntArray(3)
    for (idx in arr.indices) arr[idx] = idx * idx
    println(arr.toList())
    for (idx in arr.indices.reversed()) print(arr[idx])
    println()
    repeat(2) { print("r$it ") }
    println()
    val e = when (val q = z % 3) { 0 -> "zero"; 1 -> "one"; else -> "two$q" }
    println(e)
    println(when (z) { 1, 2 -> "low"; in 3..5 -> "mid"; else -> "hi" })
    println(when ("b") { "a" -> 1; "b" -> 2; else -> 3 })
    println(when (z as Any) { is String -> "s"; is Int -> "i"; else -> "?" })
    println(1.let { if (it > 0) "pos" else "neg" })
    println((1..4).any { it > 3 } && (1..4).none { it > 4 })
}
