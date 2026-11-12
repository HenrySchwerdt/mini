// realworld-1: the iteration order of hash maps and sets equals the JVM (table sizes of putAll, toMap(destination), toHashSet of arrays); seeded Random.
import kotlin.random.Random

data class P(val x: Int, val y: Int)
enum class Color { RED, GREEN, BLUE }

fun main() {
    val hm = HashMap<String, Int>()
    for (w in "the quick brown fox jumps over the lazy dog and runs away from the angry farmer".split(" ")) hm[w] = (hm[w] ?: 0) + 1
    println(hm)
    val hs = HashSet<Int>(listOf(100, 5, 17, 33, 1024, 65, 2, 99999, -4))
    println(hs)
    val hp = hashSetOf(P(1, 2), P(3, 4), P(0, 0), P(-1, 5))
    println(hp)
    println(setOf("banana", "apple", "cherry").toHashSet())
    println(mapOf(1.5 to "a", 2.5 to "b").toMap(HashMap()))
    println(hashSetOf('z', 'a', 'm', 'B'))
    println(hashMapOf(10L to 1, 3000000000L to 2, -1L to 3))
    println("hello".hashCode().toString() + " " + P(1, 2).hashCode() + " " + listOf(1, 2, 3).hashCode() + " " + "".hashCode() + " " + 3.14.hashCode() + " " + true.hashCode() + " " + Pair(1, "a").hashCode() + " " + 'c'.hashCode() + " " + 123456789012L.hashCode())
    val r = Random(42)
    println(List(5) { r.nextInt(100) })
    println(r.nextDouble())
    println(r.nextLong())
    println(r.nextBoolean())
    println(listOf(1, 2, 3, 4, 5, 6).shuffled(Random(7)))
    println(Random(1).nextInt(1, 7))
    println(Random(123).nextInt())
    println((1..10).map { Random(it).nextInt(1000) })
    println(Color.entries.map { it.name } to enumValues<Color>().size to enumValueOf<Color>("GREEN"))
}
