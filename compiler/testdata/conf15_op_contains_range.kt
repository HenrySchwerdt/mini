// contains, rangeTo, rangeUntil on custom types; in / !in
class Temp(val d: Int) : Comparable<Temp> { override fun compareTo(other: Temp) = d - other.d; override fun toString() = d.toString() + "C" }
class TempRange(override val start: Temp, override val endInclusive: Temp) : ClosedRange<Temp> { override fun toString() = "$start..$endInclusive" }
operator fun Temp.rangeTo(o: Temp) = TempRange(this, o)
class Bag(val items: Set<Int>) { operator fun contains(x: Int) = x in items }
class Evens { operator fun contains(x: Int) = x % 2 == 0 }
fun main() {
    val r = Temp(10)..Temp(20)
    println(Temp(15) in r); println(Temp(25) in r); println(Temp(5) !in r); println(r.isEmpty()); println(r)
    val bag = Bag(setOf(1, 2, 3)); println(2 in bag); println(5 in bag); println(5 !in bag)
    println(4 in Evens()); println(3 in Evens())
    for (x in 1..6) if (x in Evens()) print(x); println()
    println(when (7) { in Evens() -> "even"; in bag -> "bag"; else -> "no" })
    val rr = 1..<5; println(rr.toList()); println(rr.last)
    println((5..<5).isEmpty()); println(0.5 in 0.0..1.0); println(1.5 in 0.0..<1.5)
    println("b" in "a".."c"); println("bz" in "a".."c"); println('x' in 'a'..'z')
    val cr: ClosedFloatingPointRange<Double> = 0.0..1.0; println(cr.contains(1.0)); println(cr.start)
    println(listOf(1, 2, 3).contains(2)); println(mapOf(1 to 2).contains(1)); println("abc".contains('b')); println("abc".contains("bc"))
}
