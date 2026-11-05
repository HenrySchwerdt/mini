enum class Color { RED, GREEN, BLUE }
data class P(val x: Int) : Comparable<P> { override fun compareTo(other: P) = x - other.x }
@JvmInline value class V(val x: Int) : Comparable<V> { override fun compareTo(other: V) = x - other.x }
fun <T : Comparable<T>> mx(a: T, b: T): T = if (a > b) a else b
fun main() {
    println(Color.RED < Color.BLUE)
    println(Color.BLUE.compareTo(Color.GREEN))
    println(listOf(Color.BLUE, Color.RED, Color.GREEN).sorted())
    println(listOf(Color.BLUE, Color.RED, Color.GREEN).sortedDescending())
    println(listOf(Color.BLUE, Color.RED).maxOrNull())
    println(maxOf(Color.RED, Color.GREEN))
    println(mx(Color.RED, Color.GREEN))
    println(Color.values().sortedBy { -it.ordinal })
    println(listOf(P(3), P(1)).sorted())
    println(mx(P(3), P(1)))
    println(listOf(V(3), V(1)).sorted())
    println(mx(V(3), V(1)))
    println(listOf(P(3), P(1)).max())
    println(java.util.TreeMap<Color, Int>().also { it[Color.BLUE] = 1; it[Color.RED] = 2 })
}
