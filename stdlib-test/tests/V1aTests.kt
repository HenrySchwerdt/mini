package mkt.test

import mkt.*
import mkt.collections.*
import mkt.text.*
import mkt.comparisons.*
import mkt.ranges.*
import mkt.math.*

// v1-A additions: ranges as Iterable, ArrayDeque, Result, string/map/iterable functions used by the backend sources.

fun testRangesAreIterable() {
    val r = IntRange(1, 5)
    eq("[1, 2, 3, 4, 5]", r.toList().toString())
    eq("[2, 4, 6]", IntRange(1, 3).map { it * 2 }.toString())
    eq("1-2-3", IntRange(1, 3).joinToString("-"))
    eq("[5, 4, 3, 2, 1]", r.reversed().toList().toString())
    eq("1..5", r.toString())
    isTrue(3 in r)
    isFalse(r.contains(6))
    isTrue(IntRange(3, 1).isEmpty())
    eq("[]", IntRange(3, 1).toList().toString())
    eq(15, r.sum())
    eq("[10, 7, 4, 1]", 10.downTo(1).step(3).toList().toString())
    eq("[0, 1, 2]", 0.until(3).toList().toString())
    eq("[]", 0.until(0).toList().toString())
    eq("[a, b, c]", CharRange('a', 'c').toList().toString())
    eq("[1, 2, 3]", LongRange(1L, 3L).toList().toString())
    eq(r, IntRange(1, 5))
    eq(IntRange.EMPTY, IntRange(5, 1))
    throwsA("IllegalArgumentException") { r.step(0) }
    eq("[1, 3, 5]", r.step(2).toList().toString())
    eq("1..5 step 2", r.step(2).toString())
}

fun testArrayDeque() {
    val d = ArrayDeque<Int>()
    isTrue(d.isEmpty())
    d.addLast(2); d.addLast(3); d.addFirst(1); d.addFirst(0)
    eq("[0, 1, 2, 3]", d.toString())
    eq(0, d.first())
    eq(3, d.last())
    eq(0, d.removeFirst())
    eq(3, d.removeLast())
    eq("[1, 2]", d.toString())
    d.add(1, 9)
    eq("[1, 9, 2]", d.toString())
    eq(9, d.removeAt(1))
    for (i in 10..30) d.addLast(i)
    eq(23, d.size)
    eq(10, d[2])
    d[2] = 11
    eq(11, d.get(2))
    eq(null, ArrayDeque<Int>().removeFirstOrNull())
    eq(null, ArrayDeque<Int>().firstOrNull())
    throwsA("NoSuchElementException") { ArrayDeque<Int>().removeFirst() }
    isTrue(d.contains(30))
    d.clear()
    eq(0, d.size)
    val e = ArrayDeque(listOf(1, 2, 3))
    eq(listOf(1, 2, 3), e)
    var sum = 0
    while (e.isNotEmpty()) sum += e.removeFirst()
    eq(6, sum)
}

fun testResult() {
    val ok = runCatching { 21 * 2 }
    isTrue(ok.isSuccess)
    eq(42, ok.getOrNull())
    eq(42, ok.getOrThrow())
    eq("Success(42)", ok.toString())
    val bad = runCatching { if (ok.isSuccess) throw IllegalStateException("boom"); 1 }
    isTrue(bad.isFailure)
    eq(null, bad.getOrNull())
    eq(-1, bad.getOrDefault(-1))
    eq("boom", bad.exceptionOrNull()!!.message)
    eq(7, bad.getOrElse { 7 })
    eq("failed", bad.fold({ "fine" }, { "failed" }))
    eq(43, ok.map { it + 1 }.getOrThrow())
    eq("recovered", bad.recover { "recovered" }.getOrThrow())
    throwsA("IllegalStateException") { bad.getOrThrow() }
    var seen = 0
    ok.onSuccess { seen += it }
    bad.onFailure { seen += 100 }
    eq(142, seen)
    eq(Result.success(1), Result.success(1))
}

fun testIterableAdditions() {
    val xs = listOf(1, 2, 3, 4)
    eq("[4, 3, 2, 1]", xs.asReversed().toString())
    val m = mutableListOf(1, 2, 3)
    m.asReversed().add(0, 9)
    eq("[1, 2, 3, 9]", m.toString())
    eq(3, listOf(1, 2, 3, 2, 1).indexOfLast { it == 2 } - 0)
    eq(-1, xs.indexOfLast { it > 9 })
    eq("[2]", listOf<Any>(1, "a", 2, "b", 3).filterIsInstance<Int>().filter { it % 2 == 0 }.toString())
    eq("[a, b]", listOf<Any>(1, "a", 2, "b").filterIsInstance<String>().toString())
    eq("empty", emptyList<Int>().ifEmpty { listOf(0) }.let { if (it.size == 1) "empty" else "?" })
    eq(listOf(1), listOf(1).ifEmpty { listOf(2) })
    eq("[1, 2, 3]", (setOf(1, 2) + setOf(3)).toString())
    eq("[1]", (setOf(1, 2, 3) - setOf(2, 3)).toString())
    eq("[1, 2]", xs.takeLastWhile { it > 2 }.let { xs.dropLastWhile { it > 2 } }.toString())
    eq("[[1, 2], [3, 4]]", xs.chunked(2).toString())
    eq("[[1, 2, 3], [2, 3, 4]]", xs.windowed(3).toString())
    eq("[(1, 2), (2, 3), (3, 4)]", xs.zipWithNext().toString())
    eq("[1, 2]", listOf(2, 1).sortedWith(compareBy({ it }, { it })).toString())
    eq("[(a, 1), (b, 2)]", listOf("b" to 2, "a" to 1).sortedWith(compareBy({ it.first }, { it.second })).toString())
    eq("[(b, 2), (a, 1)]", listOf("a" to 1, "b" to 2).sortedWith(compareByDescending<Pair<String, Int>> { it.second }).toString())
}

fun testMapAdditions() {
    val m = LinkedHashMap<String, Int>()
    eq(null, m.putIfAbsent("a", 1))
    eq(1, m.putIfAbsent("a", 2))
    eq("{a=1}", m.toString())
    var s = ""
    for ((k, v) in m) s += "$k=$v;"
    eq("a=1;", s)
    for (e in m.entries) s += e.key
    eq("a=1;a", s)
    m["b"] = 5
    eq("[b]", m.mapNotNull { if (it.value > 1) it.key else null }.toString())
    eq("b", m.maxByOrNull { it.value }!!.key)
    eq("a", m.minByOrNull { it.value }!!.key)
    eq(false, m.none())
    eq("{a=1}", (m - "b").toString())
    eq("{a=1}", m.ifEmpty { mapOf("z" to 0) }.let { if (it.size == 2) m.filter { e -> e.key == "a" } else it }.toString())
}

fun testStringAdditions() {
    var acc = ""
    for (c in "abc") acc += c.uppercaseChar()
    eq("ABC", acc)
    var idx = ""
    for ((i, c) in "xyz".withIndex()) idx += "$i$c"
    eq("0x1y2z", idx)
    eq("b.c", "a.b.c".substringAfter('.'))
    eq("a", "a.b.c".substringBefore('.'))
    eq("c", "a.b.c".substringAfterLast('.'))
    eq("a.b", "a.b.c".substringBeforeLast('.'))
    eq("def", "".ifEmpty { "def" })
    eq("x", "x".ifEmpty { "def" })
    eq("blank", "  ".ifBlank { "blank" })
    eq("[A, B]", "ab".map { it.uppercaseChar() }.toString())
    eq(1, "abc".indexOfFirst { it == 'b' })
    eq(2, "abcb".indexOfLast { it == 'b' } - 1)
    eq("", null.orEmpty())
    eq("[ab, cd, e]", "abcde".chunked(2).toString())
    eq("abc", "[abc]".removeSurrounding("[", "]"))
}

fun testDoubleClassification() {
    isTrue((0.0 / 0.0).isNaN())
    isFalse(1.0.isNaN())
    isTrue((1.0 / 0.0).isInfinite())
    isFalse(1.0.isInfinite())
    isTrue(1.5.isFinite())
    isFalse((0.0 / 0.0).isFinite())
    isTrue(Double.MAX_VALUE > 1.0e308)
    isTrue(Double.MIN_VALUE > 0.0)
}
