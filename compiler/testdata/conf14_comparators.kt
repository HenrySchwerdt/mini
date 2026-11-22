data class Person(val name: String, val age: Int, val city: String)

class Version(val major: Int, val minor: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor })
    override fun toString() = "$major.$minor"
}

fun main() {
    val ps = listOf(Person("Ann", 30, "Rome"), Person("Bob", 25, "Oslo"), Person("Cid", 30, "Oslo"), Person("Dan", 25, "Rome"))
    println(ps.sortedBy { it.age }.map { it.name })
    println(ps.sortedWith(compareBy<Person> { it.age }.thenBy { it.city }.thenByDescending { it.name }).map { it.name })
    println(ps.sortedWith(compareByDescending<Person> { it.age }.thenBy { it.name }).map { it.name })
    println(ps.sortedWith(compareBy({ it.city }, { it.age })).map { it.name })
    println(ps.maxByOrNull { it.age }?.name)
    println(ps.minWithOrNull(compareBy { it.name.length })?.name)
    val vs = listOf(Version(1, 10), Version(1, 2), Version(0, 9))
    println(vs.sorted())
    println(vs.maxOrNull())
    println(vs.sortedDescending())
    println(Version(1, 2) < Version(1, 3))
    println(maxOf(3, 9, 4) to minOf(2L, 8L))
    val byLen = Comparator<String> { a, b -> a.length - b.length }
    println(listOf("ccc", "a", "bb").sortedWith(byLen.reversed()))
    println(listOf("b", "A", "c").sortedWith(String.CASE_INSENSITIVE_ORDER))
    println(listOf("b", null, "a").sortedWith(nullsFirst(naturalOrder())))
    println(listOf(3, 1, 2).sortedWith(reverseOrder()))
    println(compareValues(1, 2))
}
