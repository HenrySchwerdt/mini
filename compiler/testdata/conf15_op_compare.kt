// compareTo conventions, Comparable, comparisons with mixed numeric types, sortedWith
class Ver(val major: Int, val minor: Int) : Comparable<Ver> {
    override fun compareTo(other: Ver): Int = if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)
    override fun toString() = "$major.$minor"
}
class Money(val c: Long) { operator fun compareTo(o: Money) = c.compareTo(o.c); operator fun compareTo(o: Long) = c.compareTo(o) }
fun main() {
    val a = Ver(1, 2); val b = Ver(1, 10)
    println(a < b); println(a >= b); println(a.compareTo(b)); println(maxOf(a, b)); println(minOf(a, b))
    println(listOf(Ver(2, 0), a, b).sorted()); println(listOf(Ver(2, 0), a, b).sortedDescending())
    println(Money(5) < Money(6)); println(Money(5) > 4L); println(Money(5) <= 5L)
    println(1 < 2L); println(1.5 > 1); println('a' < 'b'); println("abc" < "abd"); println("b" > "abc")
    println((1..3).contains(2)); println(3.compareTo(5)); println("a".compareTo("c")); println(true.compareTo(false))
    println(Double.NaN.compareTo(1.0)); println(0.0.compareTo(-0.0)); println(Double.NaN == Double.NaN)
    val x: Comparable<Int> = 5; println(x.compareTo(3))
    println(listOf("bb", "a", "ccc").sortedWith(compareBy<String> { it.length }.thenBy { it }))
    println(listOf(3, 1, 2).sortedWith(Comparator { p, q -> q - p }))
    println(compareValues(null, 1)); println(compareValues(2, 1))
    println(listOf(1 to "b", 1 to "a", 0 to "z").sortedWith(compareBy({ it.first }, { it.second })))
}
