// More of the collection / grouping / text long tail, comparators from lambdas, AbstractIterator and the bit operations of Byte and Short.
import kotlin.random.Random

class CountDown(private var n: Int) : AbstractIterator<Int>() {
    override fun computeNext() {
        if (n > 0) setNext(n--) else done()
    }
}

fun main() {
    val l = listOf(5, 6, 7)
    println(l.flatMapIndexedTo(ArrayList()) { i, x -> listOf(i, x) })
    println(l.mapIndexedNotNullTo(ArrayList()) { i, x -> if (i != 1) x else null })
    println(l.maxOfWithOrNull(compareBy<Int> { -it }) { it % 4 })
    println(emptyList<Int>().minOfWithOrNull(naturalOrder<Int>()) { it })
    println(l.groupByTo(HashMap(), { it % 2 }, { it * 10 }).toSortedMap())
    println(l.associateByTo(HashMap(), { it }, { it * it }).toSortedMap())
    println(l.associateWithTo(HashMap()) { it + 1 }.toSortedMap())
    println(l.lastIndexOf(6))
    println(listOf(1, 2, 1).lastIndexOf(1))
    println(listOf(1, 2, 1).asSequence().lastIndexOf(1))
    println(l.groupingBy { it % 2 }.eachCountTo(HashMap()).toSortedMap())
    println(l.groupingBy { it % 2 }.foldTo(HashMap(), 0) { a, b -> a + b }.toSortedMap())
    println(l.groupingBy { it % 2 }.fold({ k, e -> k * 100 }) { k, a, b -> a + b })
    println(l.groupingBy { it % 2 }.reduceTo(HashMap()) { k, a, b -> a * b }.toSortedMap())
    println(l.groupingBy { it % 2 }.aggregateTo(HashMap()) { k, acc: Int?, b, first -> (acc ?: 0) + b }.toSortedMap())
    println("mississippi".groupingBy { it }.eachCount())
    val it2 = listOf("a", "b").iterator().withIndex()
    println(it2.next())
    println(it2.next().index)
    println(listOf(arrayOf(1, 2), arrayOf(3)).flatten())

    val m = mapOf("a" to 3, "b" to 1, "c" to 2)
    println(m.maxOfOrNull { it.value })
    println(m.minOfOrNull { it.value })
    println(emptyMap<String, Int>().maxOfOrNull { it.value })
    println(m.maxOfWith(naturalOrder<Int>()) { it.value })
    println(m.filterNotTo(HashMap()) { it.value > 1 })

    println(String.format("%d-%s-%.1f", 7, "x", 2.55))
    println(String.format("%5d|%-5d|", 42, 42))

    println(5.toByte() and 3.toByte())
    println(5.toByte() or 3.toByte())
    println(5.toByte() xor 3.toByte())
    println(5.toByte().inv())
    println(40000.toShort() and 255.toShort())
    println(5.toShort().inv())

    for (x in CountDown(3)) print("$x ")
    println()
    println(CountDown(2).asSequence().toList())
    println(CountDown(0).hasNext())

    val people = listOf("bob" to 25, "alice" to 30, "carol" to 25)
    println(people.sortedWith(compareBy({ it.second }, { it.first })))
    println(people.sortedWith(compareBy { it.first }))
    println(people.sortedWith(compareByDescending { it.second }))
    println(people.sortedWith { a, b -> a.first.length - b.first.length })
    println(people.sortedWith(Comparator { a, b -> b.second - a.second }))
    println(people.maxWithOrNull(compareBy { it.second }))
    println(people.minWithOrNull(compareBy { it.first }))
    val byName: Comparator<Pair<String, Int>> = compareBy { it.first }
    println(people.sortedWith(byName.thenBy { it.second }).map { it.first })
    println(compareValuesBy("a", "b", { s: String -> s.length }, { s: String -> s }))

    println((1..4).randomOrNull(Random(3)) in 1..4)
    println(IntRange.EMPTY.randomOrNull())
    println(('a'..'e').random(Random(2)) in 'a'..'e')
    println((5L..5L).random())
    println((7u..7u).random())
    println((1uL..3uL).random() in 1uL..3uL)
    try {
        ('b'..'a').random()
    } catch (e: NoSuchElementException) {
        println(e.message)
    }
    println("hello".subSequence(1, 3))
    println("x".let { s -> s.subSequence(0, 1).length })
}
