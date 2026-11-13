// vararg and spread operator: copying, ordering, with primitives, with named, nested
fun sum(vararg xs: Int) = xs.sum()
fun names(vararg ns: String): String = ns.joinToString("+")
fun <T> listOf2(vararg xs: T): List<T> = xs.toList()
fun count(vararg xs: Any?) = xs.size
fun first(a: Int, vararg rest: Int) = a.toString() + ":" + rest.size
fun mid(a: Int, vararg v: Int, z: Int) = a + v.sum() + z
fun modify(vararg xs: Int): IntArray { xs[0] = 99; return xs }
fun main() {
    println(sum()); println(sum(1)); println(sum(1, 2, 3)); val arr = intArrayOf(4, 5, 6); println(sum(*arr)); println(sum(1, *arr, 7)); println(sum(*arr, *arr))
    println(names("a", "b")); println(names(*arrayOf("x", "y"), "z")); println(names(*emptyArray<String>()))
    println(listOf2(1, 2, 3)); println(listOf2("a", *arrayOf("b", "c"))); println(listOf2<Int>().size)
    println(count()); println(count(1, null, "x")); println(count(*arrayOf(1, 2), 3)); println(count(arrayOf(1, 2)))
    println(first(1)); println(first(1, 2, 3)); println(first(1, *intArrayOf(2, 3, 4)))
    println(mid(1, 2, 3, z = 4)); println(mid(1, z = 10))
    val orig = intArrayOf(1, 2); val m = modify(*orig); println(orig.toList()); println(m.toList())
    val m2 = modify(*orig, 3); println(orig.toList())
    val strs = arrayOf("p", "q"); println(listOf(*strs, "r").size); println(arrayOf(*strs, "r").toList()); println(intArrayOf(*arr, 0).toList()); println(setOf(*strs))
}
