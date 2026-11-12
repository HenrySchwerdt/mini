import kotlin.math.*

data class Emp(val name: String, val dept: String, val salary: Double, val age: Int)

fun main() {
    val emps = listOf(Emp("ann", "eng", 120.5, 30), Emp("bob", "eng", 99.0, 45), Emp("cy", "ops", 80.25, 28), Emp("di", "ops", 85.0, 39), Emp("ed", "hr", 70.0, 51))
    println(emps.groupBy { it.dept }.mapValues { (_, v) -> v.sumOf { it.salary } })
    println(emps.associateBy { it.name }.keys)
    println(emps.associateWith { it.age }.values.sum())
    println(emps.maxByOrNull { it.salary }?.name + emps.minByOrNull { it.age }?.name)
    println(emps.sortedWith(compareBy<Emp> { it.dept }.thenByDescending { it.salary }).map { it.name })
    println(emps.partition { it.age > 35 }.let { it.first.size to it.second.size })
    println(emps.map { it.salary }.average())
    println(emps.sumOf { it.age } / emps.size.toDouble())
    println(emps.flatMap { listOf(it.name, it.dept) }.distinct())
    println(emps.zip(emps.drop(1)) { a, b -> b.age - a.age })
    println(emps.fold(0.0) { acc, e -> acc + e.salary })
    println(emps.reduce { a, b -> if (a.salary > b.salary) a else b }.name)
    println(emps.any { it.age > 50 } to emps.all { it.salary > 70 } to emps.none { it.dept == "x" } to emps.count { it.dept == "eng" })
    println(emps.first { it.dept == "ops" }.name + emps.last().name + emps.firstOrNull { it.age > 100 } + emps.find { it.age == 28 }?.name)
    println(emps.takeWhile { it.age < 50 }.size to emps.dropWhile { it.age < 50 }.size)
    println(emps.windowed(2).map { (a, b) -> a.name + b.name })
    println(emps.chunked(2).map { it.map { e -> e.name } })
    println(emps.withIndex().filter { it.index % 2 == 0 }.map { it.value.name })
    println(emps.groupingBy { it.dept }.eachCount())
    println(emps.map { it.name }.joinToString("|", "<", ">") { it.uppercase() })
    println(emps.sortedBy { it.name.length }.map { it.name } to emps.sortedByDescending { it.age }.take(2).map { it.age })
    println(emps.minOf { it.salary } to emps.maxOf { it.age } to emps.sumOf { it.salary.toInt() })
    println(emps.indexOfFirst { it.dept == "ops" } to emps.indexOfLast { it.dept == "ops" } to emps.map { it.dept }.lastIndexOf("eng"))
    val m = mutableMapOf("a" to 1, "b" to 2)
    m.getOrPut("c") { 3 }
    m.merge("a", 10) { x, y -> x + y }
    m.computeIfAbsent("d") { 4 }
    m.compute("b") { _, v -> (v ?: 0) * 100 }
    println(m)
    println(m.filter { it.value > 3 }.mapKeys { it.key.uppercase() })
    println(m.entries.sortedByDescending { it.value }.map { "${it.key}${it.value}" })
    println(m.toList() to m.maxByOrNull { it.value }?.key to m.count { it.value % 2 == 0 })
    m.remove("a"); m.putIfAbsent("z", 26)
    println(m)
    val l = mutableListOf(5, 3, 8, 1)
    l.sort(); l.reverse(); l.add(0, 99); l.removeAt(1); l.addAll(listOf(7, 7)); l.removeAll { it == 7 }; l.retainAll { it > 1 }
    println(l to l.binarySearch(3, reverseOrder()) to l.indexOf(3))
    println(listOf(3, 1, 2).sorted().asReversed() to listOf(1, 2, 3, 4).slice(1..2) to listOf(1, 2, 3).plus(4).minus(2) to listOf(1, 2, 3, 4, 5).subList(1, 4))
    println(listOf("a", "b", "c").mapIndexed { i, s -> "$i$s" } to listOf(1, 2, 3).runningFold(10) { a, b -> a + b } to listOf(1, 2, 2, 3).toSet() to listOf(1, 2, 3).sumOf { it * 2 })
    println(listOf(1, 2, 3, 4).foldRight("") { x, acc -> acc + x } to listOf(listOf(1, 2), listOf(3)).flatten() to List(3) { it * it } to MutableList(2) { "x" })
    println(arrayOf(3, 1, 2).sortedArray().toList() to intArrayOf(1, 2, 3).map { it * 2 } to intArrayOf(5, 4).sum() to arrayOf("b", "a").sorted() to IntArray(3) { it }.contentToString())
    val grid = Array(3) { r -> IntArray(3) { c -> r * 3 + c } }
    println(grid.map { it.toList() } to grid.sumOf { it.sum() } to grid[1].copyOfRange(1, 3).toList() to grid.flatMap { it.asList() }.filter { it % 2 == 0 })
    println(sqrt(2.0) to 2.0.pow(0.5) to abs(-3) to max(3, 9) to floor(-1.5) to ceil(1.2) to round(2.5) to round(3.5) to truncate(-2.7) to sign(-4.0) to hypot(3.0, 4.0) to ln(E) to log10(1000.0) to log2(8.0) to exp(0.0) to atan2(1.0, 1.0) to sin(PI / 2) to cos(0.0))
    println(10 / 3 to 10 % 3 to -10 / 3 to -10 % 3 to (-10).mod(3) to 10.0 / 3 to 7 / 2.0 to 1 / 3f to Int.MAX_VALUE + 1 to Long.MAX_VALUE / 2 to (5 shl 2) to (-16 shr 2) to (-16 ushr 28) to (0xF0 and 0x3C) to (5 xor 3) to 5.inv())
    println(1e3 to 1.0E-5 to 123456789.0 to 1.0E7 to 12345678.9 to 0.1 + 0.2 to 100.0f to 1.1f * 3 to 3.0e-10f to (0.0 / 0.0) to 1 / 0.0 to Float.MAX_VALUE to Double.MIN_VALUE to 1234567890123456789.0)
    println(3.7.toInt() to (-3.7).toInt() to 3.99.toLong() to 300.toByte() to 65.toChar() to 'a'.code to "12".toInt() + "3.5".toDouble() to 7.toDouble() to 'x'.toString() + 1 to 1.toString(2) to 3.14159.toString().take(4))
    println("%.3f %5.1f %e %10.4f|".format(PI, E, 12345.6789, -2.5))
    println(Int.MAX_VALUE.toLong() * 2 to Int.MIN_VALUE.toLong() - 1 to Long.MAX_VALUE + 1 to (1L shl 40) to 1_000_000L * 1_000_000 to 255.toByte() to (-1).toUInt() to 3000000000u to ULong.MAX_VALUE to 7u / 2u to 5u.toInt())
    println("abc".repeat(2) + "x".padStart(3, '-') + "Hello World".split(" ").map { it.reversed() } + "a-b".uppercase() + "TitleCase".lowercase() + " trim ".trim() + "x".isEmpty() + "".isBlank() + "a,b".split(",").size)
    println(listOf(1, 2, 3).toString() + arrayOf(1, 2).contentToString() + setOf(1).toString() + mapOf(1 to 2).toString() + Pair(1, 2) + Triple(1, 2, 3) + (1 to 2 to 3) + null + Unit + 'c' + 1.0f + 2L + true)
}
