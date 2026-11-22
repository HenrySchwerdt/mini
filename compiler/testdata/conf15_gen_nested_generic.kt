// generic classes, nested generics, generic methods shadowing, type inference with expected types
class Pair2<A, B>(val a: A, val b: B) { fun <C> with(c: C) = Triple(a, b, c); fun swap() = Pair2(b, a) }
class Tree<T>(val v: T, val kids: List<Tree<T>> = emptyList()) {
    fun <R> fold(init: R, f: (R, T) -> R): R { var acc = f(init, v); for (k in kids) acc = k.fold(acc, f); return acc }
    fun depth(): Int = 1 + (kids.maxOfOrNull { it.depth() } ?: 0)
}
fun <T> listOfNotNull2(vararg xs: T?): List<T> { val r = ArrayList<T>(); for (x in xs) if (x != null) r.add(x); return r }
fun <T> T.also2(f: (T) -> Unit): T { f(this); return this }
fun <T> id(t: T): T = t
fun main() {
    val p = Pair2(1, "x")
    println(p.with(2.5).toString()); println(p.swap().a)
    val t = Tree(1, listOf(Tree(2, listOf(Tree(4))), Tree(3)))
    println(t.fold(0) { a, b -> a + b }); println(t.fold("") { a, b -> a + b }); println(t.depth())
    println(listOfNotNull2(1, null, 3))
    println(id(listOf(1, 2)).also2 { println(it.size) })
    val m: Map<String, List<Int>> = mapOf("a" to listOf(1), "b" to emptyList())
    println(m)
    val e = emptyList<String>(); println(e.size)
    val s: Set<Long> = setOf(1, 2, 3); println(s)
    val arr: Array<Array<Int>> = Array(2) { i -> Array(2) { j -> i * 2 + j } }
    println(arr.joinToString { it.joinToString("") })
}
