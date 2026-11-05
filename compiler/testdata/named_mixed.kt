// Named arguments in their own position may be followed by positional ones; deprecated case conversions;
// explicit type arguments are the expected type of unsigned literals; withIndex of Byte arrays.
fun foo(p1: Int, p2: String, p3: Double) = "$p1 $p2 ${p3.toInt()}"

fun main() {
    println(foo(p1 = 1, "2", 3.0))
    println(foo(1, "2", p3 = 3.0))
    println(foo(p1 = 1, p2 = "2", 3.0))
    println("abc".toUpperCase() + 'x'.toUpperCase() + "ABC".toLowerCase())
    println(byteArrayOf(5, 6).withIndex().map { it.index + it.value })
    println(listOf<ULong>(8u, 6u, 4u))
    println(arrayOf("a", "b").withIndex().joinToString { it.index.toString() + it.value })
}
