// multiple bounds, recursive bounds, generic extension functions
interface Named { val name: String }
interface Aged { val age: Int }
class P(override val name: String, override val age: Int) : Named, Aged
fun <T> describe(t: T): String where T : Named, T : Aged = t.name + ":" + t.age
fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T { var m = a; if (b > m) m = b; if (c > m) m = c; return m }
abstract class Node<T : Node<T>> { abstract fun self(): T; fun chain(): T = self() }
class Leaf : Node<Leaf>() { override fun self() = this; fun hi() = "leaf" }
fun <T, R : Comparable<R>> Iterable<T>.maxByOrNull2(sel: (T) -> R): T? {
    var best: T? = null; var bv: R? = null
    for (x in this) { val v = sel(x); if (bv == null || v > bv) { best = x; bv = v } }
    return best
}
fun <K, V : Any> Map<K, V?>.nonNull(): Map<K, V> { val r = LinkedHashMap<K, V>(); for ((k, v) in this) if (v != null) r[k] = v; return r }
fun main() {
    println(describe(P("ann", 30)))
    println(maxOf3(3, 9, 4)); println(maxOf3("b", "a", "c")); println(maxOf3(1.5, 0.5, 1.0))
    println(Leaf().chain().hi())
    println(listOf("aa", "b", "cccc", "dd").maxByOrNull2 { it.length })
    println(mapOf("a" to 1, "b" to null, "c" to 3).nonNull())
}
