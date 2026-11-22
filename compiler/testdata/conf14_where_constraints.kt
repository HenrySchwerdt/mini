interface Named { val name: String }
interface Priced { val price: Int }
data class Item(override val name: String, override val price: Int) : Named, Priced

fun <T> cheapest(xs: List<T>): T? where T : Named, T : Priced = xs.minByOrNull { it.price }

fun <T> describe(x: T): String where T : Named, T : Priced = "${x.name}@${x.price}"

class Cache<K, V>(private val cap: Int) where K : Comparable<K>, V : Any {
    private val m = sortedMapOf<K, V>()
    fun put(k: K, v: V) { m[k] = v; while (m.size > cap) m.remove(m.firstKey()) }
    fun keys() = m.keys.toList()
}

fun <T : Comparable<T>> maxOf3(a: T, b: T, c: T): T {
    var m = a
    if (b > m) m = b
    if (c > m) m = c
    return m
}

fun <T, R : Comparable<R>> List<T>.maxBy2(sel: (T) -> R): T? {
    var best: T? = null
    var bv: R? = null
    for (x in this) {
        val v = sel(x)
        if (bv == null || v > bv) { best = x; bv = v }
    }
    return best
}

fun <N : Number> sumAll(xs: List<N>): Double = xs.sumOf { it.toDouble() }

fun <T : CharSequence> longest(xs: List<T>): T = xs.maxByOrNull { it.length }!!

fun main() {
    val items = listOf(Item("a", 5), Item("b", 2), Item("c", 9))
    println(cheapest(items))
    println(describe(items[2]))
    val c = Cache<String, Int>(2)
    c.put("z", 1); c.put("a", 2); c.put("m", 3)
    println(c.keys())
    println(maxOf3("b", "z", "a") + maxOf3(3, 1, 2))
    println(items.maxBy2 { it.name })
    println(sumAll(listOf(1, 2, 3)) + sumAll(listOf(1.5, 2.5)))
    println(longest(listOf("aa", "bbbb", "c")))
}
