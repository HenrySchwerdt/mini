// The long tail of the kotlin.collections functions for Iterable, List, MutableList and Map, including Map.withDefault.
import kotlin.properties.ReadOnlyProperty

class Holder(map: Map<String, String>) {
    val a by map
    val e: String by map.withDefault { "default" }
    val f: String? by map.withDefault { null }
}

fun main() {
    val l = listOf(1, 2, 3, 4, 5)
    println(l.single { it == 3 })
    println(l.singleOrNull { it > 3 })
    println(l.singleOrNull { it == 9 })
    try {
        l.single { it > 3 }
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        l.single { it > 9 }
    } catch (e: NoSuchElementException) {
        println(e.message)
    }
    println(l.windowed(2, 2, true) { it.sum() })
    println(l.windowed(3) { it.joinToString("") })
    println(l.chunked(2) { it.size })
    println(l.zipWithNext { a, b -> a * b })
    println(l.reduceOrNull { a, b -> a + b })
    println(emptyList<Int>().reduceOrNull { a, b -> a + b })
    println(l.reduceIndexedOrNull { i, a, b -> a + b * i })
    println(l.filterTo(ArrayList()) { it % 2 == 1 })
    println(l.filterNotTo(HashSet()) { it % 2 == 1 }.sorted())
    println(l.filterIndexedTo(ArrayList()) { i, _ -> i < 2 })
    println(l.zip(arrayOf("a", "b")))
    println(l.zip(arrayOf(10, 20, 30)) { x, y -> x + y })
    val (x, y, z) = l
    println("$x $y $z ${l.component4()} ${l.component5()}")
    println(l.sumBy { it * 2 })
    println(l.sumByDouble { it / 2.0 })
    println(listOf(1.5f, 2.5f).sum())
    println(listOf<Short>(1, 2).sum())
    println(l.binarySearch(4))
    println(l.binarySearch(6))
    println(l.binarySearch(3, 0, 2))
    println(l.binarySearch { it - 2 })
    println(listOf("a", "c", "e").binarySearch("c", naturalOrder()))
    try {
        l.binarySearch(1, 3, 2)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        l.binarySearch(1, 0, 9)
    } catch (e: IndexOutOfBoundsException) {
        println(e.message)
    }
    try {
        l.windowed(0)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        l.windowed(2, 0)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }

    val m = mutableListOf(3, 1, 2)
    m.fill(7)
    println(m)
    val n = mutableListOf(3, 1, 2)
    n.sortDescending()
    println(n)
    n.sortByDescending { -it }
    println(n)
    n.addFirst(0)
    n.addLast(9)
    println(n)
    println(n.addAll(arrayOf(5, 6)))
    println(n.addAll(sequenceOf(7)))
    println(n.removeAll(arrayOf(5, 7)))
    println(n.retainAll(listOf(1, 2, 3, 0, 6)))
    println(n)
    println(n.retainAll(setOf(1, 2).asSequence()))
    println(n)
    println(buildList(4) { add(1); add(2) })
    println(buildSet(4) { add(1); add(1) })
    println(buildMap(4) { put(1, "a") })
    println(setOfNotNull(1, null, 2))
    println(setOfNotNull<Int>(null))

    val map = mapOf("a" to 1, "b" to 2, "c" to 3)
    println(map.mapTo(ArrayList()) { it.key + it.value })
    println(map.onEach { print(it.key) })
    println(map.onEachIndexed { i, e -> print(i + e.value) })
    println(map.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value })?.key)
    println(map.minWith(compareBy<Map.Entry<String, Int>> { it.value }).key)
    println(map.toMap(LinkedHashMap()))
    println(listOf("x" to 1, "y" to 2).toMap(HashMap()).toSortedMap())
    val mm = mutableMapOf<String, Int>()
    mm.putAll(arrayOf("p" to 1, "q" to 2))
    mm.putAll(sequenceOf("r" to 3))
    println(mm)

    val d = map.withDefault { it.length * 100 }
    println(d.getValue("b"))
    println(d.getValue("zz"))
    println(d["zz"])
    println(d.size)
    println(d == map)
    println(d)
    val md = mutableMapOf("a" to 1).withDefault { -1 }
    md["b"] = 2
    println(md.getValue("b"))
    println(md.getValue("nope"))
    println(md)
    val h = Holder(hashMapOf("a" to "all", "e" to "own"))
    println(h.a)
    println(h.e)
    println(h.f)
    try {
        Holder(hashMapOf("e" to "x")).a
    } catch (ex: NoSuchElementException) {
        println(ex.message)
    }
}
