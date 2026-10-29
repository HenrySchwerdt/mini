// Collections and strings from the Mini stdlib (Kotlin source compiled together with the program).

data class Person(val name: String, val age: Int)

fun main() {
    val xs = listOf(5, 3, 8, 1)
    println(xs.map { it * 2 })
    println(xs.filter { it > 2 }.sorted())
    println(xs.fold(0) { acc, x -> acc + x })
    println(xs.zip(listOf("a", "b", "c")))

    val people = listOf(Person("Ann", 31), Person("Bo", 25), Person("Cy", 40))
    println(people.sortedBy { it.age }.map { it.name })
    println(people.maxByOrNull { it.age })
    println(people.sumOf { it.age })

    val list = mutableListOf(1, 2, 3)
    list.add(4)
    list[0] = 99
    println(list)

    val counts = HashMap<String, Int>()
    for (w in "a b a c b a".split(" ")) counts[w] = (counts[w] ?: 0) + 1
    println(counts)

    val set = setOf(1, 2, 2, 3)
    println(set.size)

    println("Hello, Mini".lowercase().replace("mini", "world").padEnd(15, '.'))
    println("a,b,c".split(",").joinToString("|"))
    println(buildString {
        for (i in 1..3) append(i).append(';')
    })
}
