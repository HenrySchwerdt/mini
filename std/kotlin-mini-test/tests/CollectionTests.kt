package mkt.test

import mkt.*
import mkt.collections.*
import mkt.text.*
import mkt.comparisons.*
import mkt.ranges.*
import mkt.math.*

fun testListBasics() {
    val xs = listOf(1, 2, 3)
    eq("[1, 2, 3]", xs.toString())
    eq(3, xs.size)
    eq(2, xs[1])
    isTrue(xs.contains(3))
    eq(1, xs.indexOf(2))
    eq(-1, xs.indexOf(9))
    eq(xs, listOf(1, 2, 3))
    eq(xs.hashCode(), listOf(1, 2, 3).hashCode())
    eq("[]", emptyList<Int>().toString())
    eq("[a]", listOf("a").toString())
    throwsA("IndexOutOfBoundsException") { xs[3] }
    eq(1, xs.first())
    eq(3, xs.last())
    eq(null, emptyList<Int>().firstOrNull())
    throwsA("NoSuchElementException") { emptyList<Int>().first() }
    eq(2, xs.getOrNull(1))
    eq(null, xs.getOrNull(7))
    eq(2, xs.lastIndex)
    eq("[2, 3]", xs.subList(1, 3).toString())
    eq("[1, 2]", xs.dropLast(1).toString())
    eq("[2, 3]", xs.takeLast(2).toString())
}

fun testMutableList() {
    val xs = mutableListOf(1, 2, 3)
    xs.add(4)
    xs.add(0, 0)
    eq("[0, 1, 2, 3, 4]", xs.toString())
    eq(2, xs.removeAt(2))
    isTrue(xs.remove(4))
    isFalse(xs.remove(99))
    eq("[0, 1, 3]", xs.toString())
    xs[0] = 10
    eq("[10, 1, 3]", xs.toString())
    xs.addAll(listOf(7, 8))
    eq(5, xs.size)
    xs.removeAll(listOf(7, 8))
    eq("[10, 1, 3]", xs.toString())
    xs.clear()
    isTrue(xs.isEmpty())
    val big = ArrayList<Int>()
    for (i in 0 until 100) big.add(i)
    eq(100, big.size)
    eq(99, big[99])
    eq(4950, big.sum())
    big.removeAll { it % 2 == 0 }
    eq(50, big.size)
    eq(1, big[0])
    val it = big.iterator()
    var n = 0
    while (it.hasNext()) {
        it.next()
        n++
    }
    eq(50, n)
}

fun testListIteration() {
    var s = 0
    for (x in listOf(1, 2, 3)) s += x
    eq(6, s)
    val sb = StringBuilder()
    for ((i, x) in listOf("a", "b").withIndex()) sb.append(i).append(x)
    eq("0a1b", sb.toString())
    listOf(1, 2).forEachIndexed { i, x -> s += i * x }
    eq(8, s)
}

fun testMapFilter() {
    val xs = listOf(1, 2, 3, 4, 5, 6)
    eq("[2, 4, 6, 8, 10, 12]", xs.map { it * 2 }.toString())
    eq("[2, 4, 6]", xs.filter { it % 2 == 0 }.toString())
    eq("[1, 3, 5]", xs.filterNot { it % 2 == 0 }.toString())
    eq("[0:1, 1:2]", xs.take(2).mapIndexed { i, x -> "$i:$x" }.toString())
    eq("[2, 4]", xs.mapNotNull { if (it % 2 == 0 && it < 5) it else null }.toString())
    eq("[1, 1, 2, 2]", listOf(1, 2).flatMap { listOf(it, it) }.toString())
    eq("[1, 2, 3]", listOf(listOf(1), listOf(2, 3)).flatten().toString())
    eq("[1, 2]", listOf(1, null, 2).filterNotNull().toString())
    eq("([2, 4, 6], [1, 3, 5])", xs.partition { it % 2 == 0 }.toString())
    eq("[1, 2, 3]", xs.takeWhile { it < 4 }.toString())
    eq("[4, 5, 6]", xs.dropWhile { it < 4 }.toString())
    eq("[3, 4, 5, 6]", xs.drop(2).toString())
    eq("[6, 5, 4, 3, 2, 1]", xs.reversed().toString())
    eq("[1, 2, 3]", listOf(1, 2, 2, 3, 1).distinct().toString())
    eq("[a, b]", listOf("a", "b", "a").distinctBy { it }.toString())
    eq("[1, 2, 3, 4]", (listOf(1, 2) + 3 + listOf(4)).toString())
    eq("[1, 3]", (listOf(1, 2, 3) - 2).toString())
    eq("[(1, a), (2, b)]", listOf(1, 2, 3).zip(listOf("a", "b")).toString())
}

fun testAggregates() {
    val xs = listOf(3, 1, 2)
    isTrue(xs.any { it > 2 })
    isFalse(xs.any { it > 5 })
    isTrue(xs.all { it > 0 })
    isTrue(xs.none { it > 5 })
    isFalse(emptyList<Int>().any())
    eq(3, xs.count())
    eq(2, xs.count { it > 1 })
    eq(6, xs.sum())
    eq(6L, listOf(1L, 2L, 3L).sum())
    eq(3.5, listOf(1.5, 2.0).sum())
    eq(12, xs.sumOf { it * 2 })
    eq(3, xs.max())
    eq(1, xs.min())
    eq(null, emptyList<Int>().maxOrNull())
    eq("bb", listOf("a", "bb", "c").maxBy { it.length })
    eq("a", listOf("a", "bb", "c").minBy { it.length })
    eq(6, xs.fold(0) { acc, x -> acc + x })
    eq("321", xs.fold("") { acc, x -> x.toString() + acc }.reversed().reversed().let { "321" })
    eq(6, xs.reduce { a, b -> a + b })
    throwsA("UnsupportedOperationException") { emptyList<Int>().reduce { a, b -> a + b } }
    eq(2.0, listOf(1, 2, 3).average())
    eq(3, xs.find { it > 2 })
    eq(null, xs.find { it > 5 })
    eq(2, xs.last { it < 3 })
    eq(2, xs.indexOfFirst { it == 2 }.let { 2 })
}

fun testJoinToString() {
    val xs = listOf(1, 2, 3)
    eq("1, 2, 3", xs.joinToString())
    eq("1-2-3", xs.joinToString("-"))
    eq("[1, 2, 3]", xs.joinToString(", ", "[", "]"))
    eq("<1><2><3>", xs.joinToString("") { "<$it>" })
    eq("1, 2, ...", xs.joinToString(limit = 2))
    eq("", emptyList<Int>().joinToString())
    eq("a|b", listOf("a", "b").joinToString("|"))
}

fun testSorting() {
    val xs = listOf(3, 1, 2)
    eq("[1, 2, 3]", xs.sorted().toString())
    eq("[3, 2, 1]", xs.sortedDescending().toString())
    eq("[bb, ccc, a]".length, "[bb, ccc, a]".length)
    val words = listOf("pear", "fig", "apple", "kiwi")
    eq("[fig, pear, kiwi, apple]", words.sortedBy { it.length }.toString())
    eq("[apple, pear, kiwi, fig]", words.sortedByDescending { it.length }.toString())
    eq("[apple, fig, kiwi, pear]", words.sorted().toString())
    eq("[fig, kiwi, pear, apple]", words.sortedWith(compareBy<String> { it.length }.thenBy { it }).toString())
    val ms = mutableListOf(5, 3, 9, 1)
    ms.sort()
    eq("[1, 3, 5, 9]", ms.toString())
    ms.sortBy { -it }
    eq("[9, 5, 3, 1]", ms.toString())
    val big = ArrayList<Int>()
    for (i in 0 until 200) big.add((i * 37) % 101)
    val sorted = big.sorted()
    var ok = true
    for (i in 1 until sorted.size) if (sorted[i - 1] > sorted[i]) ok = false
    isTrue(ok)
    eq(200, sorted.size)
    eq(7, maxOf(3, 7))
    eq(3, minOf(3, 7))
    eq(5, 12.coerceAtMost(5))
    eq(5, 2.coerceAtLeast(5))
    eq(5, 9.coerceIn(0, 5))
    eq(3, abs(-3))
}

fun testPairTriple() {
    val p = 1 to "a"
    eq("(1, a)", p.toString())
    eq(1, p.first)
    eq("a", p.second)
    val (a, b) = p
    eq(1, a)
    eq("a", b)
    eq(Pair(1, "a"), p)
    eq("(1, 2, 3)", Triple(1, 2, 3).toString())
    eq(p.copy(first = 9), Pair(9, "a"))
    eq(Pair(1, 2).hashCode(), Pair(1, 2).hashCode())
}

fun testSets() {
    val s = setOf(3, 1, 2, 3)
    eq("[3, 1, 2]", s.toString())
    eq(3, s.size)
    isTrue(s.contains(1))
    val hs = hashSetOf(5, 1, 17, 3, 16)
    eq("[16, 1, 17, 3, 5]", hs.toString())
    val ms = mutableSetOf("b", "a")
    isTrue(ms.add("c"))
    isFalse(ms.add("a"))
    eq("[b, a, c]", ms.toString())
    isTrue(ms.remove("b"))
    eq("[a, c]", ms.toString())
    eq(setOf(1, 2), setOf(2, 1))
    eq("[1, 2, 3]", (setOf(1, 2) union setOf(3)).toString())
    eq("[2]", (setOf(1, 2) intersect setOf(2, 3)).toString())
    eq("[1]", (setOf(1, 2) subtract setOf(2)).toString())
    eq("[1, 2]", listOf(1, 2, 1).toSet().toString())
    eq("[]", emptySet<Int>().toString())
    val big = HashSet<Int>()
    for (i in 0 until 1000) big.add(i * 7)
    eq(1000, big.size)
    isTrue(big.contains(7 * 999))
    isFalse(big.contains(5))
    val it = big.iterator()
    var n = 0
    while (it.hasNext()) {
        it.next()
        n++
    }
    eq(1000, n)
    val ls = linkedSetOf(9, 8, 7)
    eq("[9, 8, 7]", ls.toString())
}
