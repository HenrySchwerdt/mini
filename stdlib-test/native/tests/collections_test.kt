import java.util.TreeMap
import java.util.TreeSet
import kotlin.test.*

class CollectionsTest {
    @Test
    fun sortedMapAndSet() {
        val m = sortedMapOf(3 to "c", 1 to "a", 2 to "b")
        assertEquals("{1=a, 2=b, 3=c}", m.toString())
        assertEquals(1, m.firstKey())
        assertEquals(3, m.lastKey())
        assertEquals(3, m.floorKey(5))
        assertEquals(1, m.ceilingKey(0))
        assertEquals(3, m.higherKey(2))
        assertNull(m.lowerKey(1))
        m[0] = "z"
        m.remove(2)
        assertEquals(listOf(0, 1, 3), m.keys.toList())
        assertEquals(listOf("z", "a", "c"), m.values.toList())
        assertEquals("{a=2, b=1}", mapOf("b" to 1, "a" to 2).toSortedMap().toString())
        val s = sortedSetOf(5, 3, 9, 3)
        assertEquals("[3, 5, 9]", s.toString())
        assertEquals(3, s.first())
        assertEquals(9, s.last())
        assertEquals(3, s.floor(4))
        assertEquals(9, s.higher(5))
        assertEquals(setOf(3), s.headSet(5))
        assertEquals(setOf(5, 9), s.tailSet(5))
        val t = TreeSet<String>(compareByDescending { it })
        t.addAll(listOf("a", "c", "b"))
        assertEquals("[c, b, a]", t.toString())
        assertEquals("[1, 2, 3]", listOf(3, 1, 2).toSortedSet().toString())
        val tm = TreeMap<String, Int>()
        tm["x"] = 1
        tm["a"] = 2
        assertEquals(2, tm.pollFirstEntry()!!.value)
        assertEquals(1, tm.size)
    }

    @Test
    fun groupingAndScans() {
        assertEquals(mapOf(1 to 1, 2 to 2), listOf("a", "bb", "cc").groupingBy { it.length }.eachCount())
        assertEquals(listOf(0, 1, 3, 6), listOf(1, 2, 3).scan(0) { a, b -> a + b })
        assertEquals(listOf(1, 3, 6), listOf(1, 2, 3).runningReduce { a, b -> a + b })
        assertEquals("321", listOf(1, 2, 3).foldRight("") { a, acc -> acc + a })
        assertEquals(3, listOf(3, 1, 2).maxWith(naturalOrder()))
        assertEquals(5, listOf("a", "b").firstNotNullOfOrNull { if (it == "b") 5 else null })
        assertEquals(3, listOf(1, 2, 3).maxOf { it })
        assertEquals(1, listOf(1, 2, 3).minOf { it })
        assertEquals(listOf("0:a", "1:b"), listOf("a", "b").mapIndexedNotNull { i, s -> i.toString() + ":" + s })
        assertEquals(listOf(1, 3, 6), listOf(1, 2, 3).runningFold(0) { a, b -> a + b }.drop(1))
        var seen = 0
        assertEquals(3, listOf(1, 2, 3).onEach { seen += it }.size)
        assertEquals(6, seen)
    }

    @Test
    fun mutableMapHelpers() {
        val mm = mutableMapOf("a" to 1)
        mm.computeIfAbsent("b") { 2 }
        mm.merge("a", 5) { x, y -> x + y }
        mm.compute("c") { _, v -> (v ?: 0) + 1 }
        assertEquals(mapOf("a" to 6, "b" to 2, "c" to 1), mm)
        val lst = mutableListOf(1, 2, 3, 4)
        lst.removeIf { it % 2 == 0 }
        assertEquals(listOf(1, 3), lst)
    }

    @Test
    fun deque() {
        val d = ArrayDeque<Int>()
        d.addFirst(1)
        d.addLast(2)
        d.addFirst(0)
        assertEquals(0, d.removeFirst())
        assertEquals(2, d.removeLast())
        assertEquals("[1]", d.toString())
    }

    @Test
    fun comparators() {
        val l = listOf("bb", "a", "ccc", "aa")
        assertEquals(listOf("a", "aa", "bb", "ccc"), l.sortedWith(compareBy<String> { it.length }.thenBy { it }))
        assertEquals(listOf("ccc", "bb", "aa", "a"), l.sortedWith(compareByDescending<String> { it.length }.thenByDescending { it }))
        assertEquals(Pair(listOf(3, 4), listOf(1, 2)), listOf(1, 2, 3, 4).partition { it > 2 })
        assertEquals(listOf(listOf(1, 2), listOf(3)), listOf(1, 2, 3).chunked(2))
        assertEquals(listOf(listOf(1, 2), listOf(2, 3)), listOf(1, 2, 3).windowed(2))
        assertEquals(mapOf(1 to 2, 2 to 4), listOf(1, 2).associateWith { it * 2 })
        assertEquals(mapOf(2 to 1, 4 to 2), listOf(1, 2).associateBy { it * 2 })
        assertEquals(6, listOf(1, 2, 3).sumOf { it.toLong() }.toInt())
        assertEquals(2, listOf(1, 2).maxByOrNull { it })
        assertEquals(1, listOf(2, 1).minBy { it })
    }
}
