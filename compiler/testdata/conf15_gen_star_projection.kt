// star projections
interface Source<out T> { fun next(): T }
class Counter : Source<Int> { var i = 0; override fun next() = i++ }
class Holder<T : Comparable<T>>(var v: T) { fun cmp(o: T) = v.compareTo(o) }
fun describe(l: List<*>): String = l.joinToString(",") { it?.toString() ?: "null" }
fun first(s: Source<*>): Any? = s.next()
fun sizeOf(m: Map<*, *>) = m.size
fun clear(l: MutableList<*>) { l.clear() }
fun isHolderOfInt(h: Holder<*>): Boolean = h.v is Int
fun main() {
    println(describe(listOf(1, "a", null, 2.5)))
    val c = Counter()
    println(first(c)); println(first(c))
    println(sizeOf(mapOf(1 to "a", 2 to "b")))
    val ml = mutableListOf(1, 2, 3)
    clear(ml)
    println(ml.size)
    println(isHolderOfInt(Holder(3)))
    println(isHolderOfInt(Holder("s")))
    val arr: Array<*> = arrayOf(1, "two", 3.0)
    println(arr.size)
    println(arr[1])
    val x: Any = listOf(1, 2)
    if (x is List<*>) println("list of " + x.size)
}
