package mkt.test

import mkt.*
import mkt.collections.*
import mkt.text.*
import mkt.comparisons.*
import mkt.ranges.*
import mkt.math.*

fun testMapBasics() {
    val m = mapOf("a" to 1, "b" to 2)
    eq("{a=1, b=2}", m.toString())
    eq(2, m.size)
    eq(1, m["a"])
    eq(null, m["z"])
    isTrue(m.containsKey("b"))
    isFalse(m.containsKey("c"))
    isTrue(m.containsValue(2))
    eq(9, m.getOrDefault("z", 9))
    eq(2, m.getValue("b"))
    throwsA("NoSuchElementException") { m.getValue("z") }
    eq("[a, b]", m.keys.toString())
    eq("[1, 2]", m.values.toString())
    eq("[a=1, b=2]", m.entries.toString())
    eq(m, mapOf("b" to 2, "a" to 1))
    eq("{}", emptyMap<String, Int>().toString())
    eq(m.hashCode(), mapOf("b" to 2, "a" to 1).hashCode())
}

fun testMutableMap() {
    val m = mutableMapOf<String, Int>()
    eq(null, m.put("a", 1))
    eq(1, m.put("a", 2))
    m["b"] = 3
    eq("{a=2, b=3}", m.toString())
    eq(2, m.remove("a"))
    eq(null, m.remove("zz"))
    eq(3, m.getOrPut("b") { 99 })
    eq(7, m.getOrPut("c") { 7 })
    eq("{b=3, c=7}", m.toString())
    m.putAll(mapOf("d" to 1))
    eq(3, m.size)
    m.clear()
    isTrue(m.isEmpty())
    val counts = HashMap<Char, Int>()
    for (c in "mississippi") counts[c] = (counts[c] ?: 0) + 1
    eq(4, counts['s'])
    eq(4, counts['i'])
    eq(2, counts['p'])
    eq(1, counts['m'])
}

fun testHashMapOrder() {
    val m = HashMap<String, Int>()
    for (w in listOf("banana", "apple", "cherry", "date", "elderberry", "fig", "grape")) m[w] = w.length
    eq("{banana=6, date=4, apple=5, cherry=6, fig=3, grape=5, elderberry=10}", m.toString())
    val im = HashMap<Int, String>()
    for (i in listOf(100, 5, 33, 17, 16, 1)) im[i] = "v$i"
    eq("{16=v16, 33=v33, 17=v17, 1=v1, 100=v100, 5=v5}", im.toString())
    val big = HashMap<Int, Int>()
    for (i in 0 until 50) big[i * 13] = i
    eq(50, big.size)
    eq(25, big[25 * 13])
    var sum = 0
    for ((k, v) in big) sum += k - v
    eq(13 * 1225 - 1225, sum)
    val removed = HashMap<Int, Int>()
    for (i in 0 until 20) removed[i] = i
    for (i in 0 until 20 step 2) removed.remove(i)
    eq("{1=1, 3=3, 5=5, 7=7, 9=9, 11=11, 13=13, 15=15, 17=17, 19=19}", removed.toString())
}

fun testLinkedHashMap() {
    val m = LinkedHashMap<String, Int>()
    m["z"] = 1
    m["a"] = 2
    m["m"] = 3
    eq("{z=1, a=2, m=3}", m.toString())
    m.remove("a")
    m["a"] = 4
    eq("{z=1, m=3, a=4}", m.toString())
    m["z"] = 10
    eq("{z=10, m=3, a=4}", m.toString())
    val it = m.entries.iterator()
    it.next()
    it.remove()
    eq("{m=3, a=4}", m.toString())
}

fun testMapFunctions() {
    val m = mapOf("a" to 1, "b" to 2, "c" to 3)
    eq("{b=2, c=3}", m.filter { it.value > 1 }.toString())
    eq("{a=1}", m.filterKeys { it == "a" }.toString())
    eq("{c=3}", m.filterValues { it > 2 }.toString())
    eq("{a=2, b=4, c=6}", m.mapValues { it.value * 2 }.toString())
    eq("{A=1, B=2, C=3}", m.mapKeys { it.key.uppercase() }.toString())
    eq("[a1, b2, c3]", m.map { it.key + it.value }.toString())
    eq("[(a, 1), (b, 2), (c, 3)]", m.toList().toString())
    eq("{a=1, b=2, c=3, d=4}", (m + ("d" to 4)).toString())
    eq("{a=1, c=3}", (m - "b").toString())
    isTrue(m.any { it.value == 2 })
    isTrue(m.all { it.value > 0 })
    eq(1, m.count { it.value > 2 })
    var sum = 0
    for ((k, v) in m) sum += v
    eq(6, sum)
    m.forEach { sum += it.value }
    eq(12, sum)
    eq("{1=a, 2=b}", listOf(1 to "a", 2 to "b").toMap().toString())
}

fun testAssociateGroup() {
    val words = listOf("apple", "avocado", "banana", "blueberry", "cherry")
    eq("{a=2, b=2, c=1}", words.groupBy { it[0] }.mapValues { it.value.size }.toString())
    eq("{a=[apple, avocado], b=[banana, blueberry], c=[cherry]}", words.groupBy { it[0] }.toString())
    eq("{apple=5, avocado=7, banana=6, blueberry=9, cherry=6}", words.associateWith { it.length }.toString())
    eq("{5=apple, 7=avocado, 6=cherry, 9=blueberry}", words.associateBy { it.length }.toString())
    eq("{a=APPLE}", listOf("apple").associate { it[0] to it.uppercase() }.toString())
}

fun testScopeFunctions() {
    eq(4, 2.let { it * 2 })
    eq("x", "x".also { })
    eq(5, run { 2 + 3 })
    eq("[1]", mutableListOf<Int>().apply { add(1) }.toString())
    eq(3, with("abc") { length })
    eq(5, 5.takeIf { it > 3 })
    eq(null, 5.takeIf { it > 9 })
    eq(5, 5.takeUnless { it > 9 })
    var n = 0
    repeat(4) { n += it }
    eq(6, n)
    var calls = 0
    val lz = lazy { calls++; 42 }
    eq(0, calls)
    eq(42, lz.value)
    eq(42, lz.value)
    eq(1, calls)
}

fun testPreconditions() {
    require(true)
    check(true)
    throwsA("IllegalArgumentException") { require(false) }
    throwsA("IllegalStateException") { check(false) }
    throwsA("IllegalStateException") { error("boom") }
    throwsA("IllegalArgumentException") { requireNotNull(null as String?) }
    eq("x", requireNotNull("x"))
    var msg = ""
    try {
        require(1 > 2) { "custom" }
    } catch (e: IllegalArgumentException) {
        msg = e.message ?: ""
    }
    eq("custom", msg)
    throwsA("NotImplementedError") { TODO() }
}

fun testBuilders() {
    eq("[0, 1, 4]", List(3) { it * it }.toString())
    eq("[x, x]", MutableList(2) { "x" }.toString())
    eq("[1, 2]", buildList { add(1); add(2) }.toString())
    eq("{a=1}", buildMap { put("a", 1) }.toString())
    eq("[1, 2]", listOfNotNull(1, null, 2).toString())
    eq("[a, b]", arrayListOf("a", "b").toString())
}
