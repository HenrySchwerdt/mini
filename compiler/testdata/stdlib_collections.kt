// The real stdlib collections (std/kotlin-mini, compiled natively) with lambdas, generics, extension
// functions, vararg and exceptions.

data class Person(val name: String, val age: Int)

fun main() {
    println(listOf(1, 2, 3).map { it * 2 }.joinToString())
    val xs = listOf(5, 3, 8, 1)
    println(xs.size)
    println(xs.filter { it > 2 })
    println(xs.sorted())
    println(xs.sortedDescending())
    println(xs.reversed())
    println(xs.sum())
    println(xs.fold(100) { acc, x -> acc - x })
    println(xs.any { it > 7 })
    println(xs.all { it > 0 })
    println(xs.count { it % 2 == 1 })
    println(xs.first())
    println(xs.last())
    println(xs.take(2))
    println(xs.drop(2))
    println(xs.zip(listOf("a", "b", "c")))
    println(xs.maxOrNull())
    println(xs.indexOf(8))
    println(xs.contains(3))

    val names = listOf("bob", "alice", "carol", "dave")
    println(names.map { it.length })
    println(names.sortedBy { it.length })
    println(names.joinToString(separator = "|", prefix = "<", postfix = ">"))
    println(names.filter { it.startsWith("a") || it.startsWith("d") })

    val people = listOf(Person("Ann", 31), Person("Bo", 25), Person("Cy", 40))
    println(people.sortedBy { it.age }.map { it.name })
    println(people.maxByOrNull { it.age })
    println(people.sumOf { it.age })

    val list = mutableListOf(1, 2, 3)
    list.add(4)
    list.add(0, 10)
    list.remove(2)
    list.removeAt(0)
    println(list)
    list[0] = 99
    println(list)
    for (x in list) print("$x ")
    println()
    list.forEach { print(it + 1) }
    println()

    val sb = StringBuilder()
    sb.append("n=").append(5).append(',').append(true)
    println(sb.toString())
    println(buildString { append("built"); append(1 + 2) })
    val lazyValue by lazy { println("computing"); 42 }
    println(lazyValue + lazyValue)
    val p = Pair(1, "one")
    println(p.first + 1)
    println(p)
    val (a, b) = p
    println(a.toString() + b)

    // exceptions thrown by the collections
    try {
        println(listOf(1, 2, 3)[5])
    } catch (e: IndexOutOfBoundsException) {
        println("index error")
    }
    try {
        println(emptyList<Int>().first())
    } catch (e: NoSuchElementException) {
        println("empty")
    }
    try {
        println("abc".toInt())
    } catch (e: NumberFormatException) {
        println("nfe")
    }
}
