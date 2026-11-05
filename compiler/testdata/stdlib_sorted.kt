import java.util.TreeMap
import java.util.TreeSet

fun main() {
    val m = sortedMapOf(3 to "c", 1 to "a", 2 to "b")
    println(m)
    println(m.firstKey().toString() + " " + m.lastKey() + " " + m.floorKey(5) + " " + m.ceilingKey(0) + " " + m.higherKey(2))
    m[0] = "z"
    m.remove(2)
    println(m.keys.toList().toString() + m.values.toList())
    for ((k, v) in m) print("$k=$v ")
    println()
    println(mapOf("b" to 1, "a" to 2).toSortedMap())
    val s = sortedSetOf(5, 3, 9, 3)
    println(s)
    println(s.first().toString() + " " + s.last() + " " + s.floor(4) + " " + s.higher(5) + " " + s.headSet(5) + s.tailSet(5))
    val t = TreeSet<String>(compareByDescending { it })
    t.addAll(listOf("a", "c", "b"))
    println(t)
    println(listOf(3, 1, 2).toSortedSet())
    val tm = TreeMap<String, Int>()
    tm["x"] = 1; tm["a"] = 2
    println(tm.entries.first().key + tm.pollFirstEntry()!!.value + tm.size)
    println(listOf("a", "bb", "cc").groupingBy { it.length }.eachCount())
    println(listOf(1, 2, 3).scan(0) { a, b -> a + b })
    println(listOf(1, 2, 3).runningReduce { a, b -> a + b })
    println(listOf(1, 2, 3).onEach { print(it) }.size)
    println(listOf(3, 1, 2).maxWith(naturalOrder()))
    println(listOf("a", "b").firstNotNullOfOrNull { if (it == "b") 5 else null })
    println(listOf(1, 2, 3).foldRight("") { a, acc -> acc + a })
    val mm = mutableMapOf("a" to 1)
    mm.computeIfAbsent("b") { 2 }; mm.merge("a", 5) { x, y -> x + y }; mm.compute("c") { _, v -> (v ?: 0) + 1 }
    println(mm)
    val lst = mutableListOf(1, 2, 3, 4)
    lst.removeIf { it % 2 == 0 }
    println(lst)
}
