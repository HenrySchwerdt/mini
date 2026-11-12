import kotlin.random.Random
import kotlin.random.nextUInt

fun main() {
    val r = Random(42)
    println(r.nextInt())
    println(r.nextInt(100))
    println(r.nextInt(5, 10))
    println(r.nextLong())
    println(r.nextDouble())
    println(r.nextBoolean())
    println(r.nextBytes(3).toList())
    println(r.nextFloat())
    val l = (1..10).toList().shuffled(Random(7))
    println(l)
    println(listOf("a", "b", "c", "d").random(Random(3)))
    println(Random(1).nextInt(1000) == Random(1).nextInt(1000))
    println((1..5).map { Random(it).nextInt(10) })
    val u = Random(99).nextUInt()
    println(u)
    println(Random(5).nextLong(1000L))
    println(Random(0).nextInt(Int.MAX_VALUE))
}
