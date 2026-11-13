// == vs ===, equals override, hashCode contract, boxed Int caching-free semantics, structural equality of collections/arrays
class Pt(val x: Int) { override fun equals(other: Any?) = other is Pt && other.x == x; override fun hashCode() = x }
class NoEq(val x: Int)
fun main() {
    println(Pt(1) == Pt(1)); println(Pt(1) === Pt(1)); println(Pt(1) != Pt(2)); println(NoEq(1) == NoEq(1)); val n = NoEq(1); println(n == n); println(n === n)
    println(setOf(Pt(1), Pt(1)).size); println(setOf(NoEq(1), NoEq(1)).size); println(listOf(Pt(1)).indexOf(Pt(1))); println(mapOf(Pt(3) to 1)[Pt(3)])
    println(listOf(1, 2) == listOf(1, 2)); println(listOf(1, 2) == setOf(1, 2)); println(setOf(1, 2) == setOf(2, 1)); println(mapOf(1 to 2) == mapOf(1 to 2)); println(arrayOf(1) == arrayOf(1)); println(arrayOf(1).contentEquals(arrayOf(1)))
    println(intArrayOf(1, 2).contentEquals(intArrayOf(1, 2))); println(intArrayOf(1, 2).contentHashCode() == intArrayOf(1, 2).contentHashCode()); println(arrayOf(arrayOf(1)).contentDeepEquals(arrayOf(arrayOf(1))))
    val a: Any = 1; val b: Any = 1L; println(a == b); println(a.equals(1)); println(1 == 1); println(1L == 1L); println((1 as Number) == (1 as Number)); println(1.0 == 1.0); println(0.0 == -0.0); println((0.0 as Any) == (-0.0 as Any))
    val d1: Double = Double.NaN; println(d1 == d1); println((d1 as Any) == (d1 as Any)); println(d1.equals(d1)); println(listOf(Double.NaN) == listOf(Double.NaN))
    println("a" + "b" == "ab"); println("abc".substring(1) == "bc"); val s1 = "hi"; val s2 = "h" + "i"; println(s1 == s2); println(s1 === s1); println(null == null); println(null === null)
    println(1.compareTo(2) == -1); println('a' == 'a'); println(true == true); println(Unit == Unit); println(Pair(1, 2) == Pair(1, 2)); println(Pair(1, "a").equals(Pair(1, "a")))
    val o1 = Any(); val o2 = Any(); println(o1 == o2); println(o1 == o1); println(o1.equals(o1)); println(o1.hashCode() == o1.hashCode())
    println(Pt(5).hashCode()); println("abc".hashCode()); println(listOf(1, 2, 3).hashCode()); println(1.hashCode()); println(true.hashCode()); println('a'.hashCode()); println(2.5.hashCode()); println(10L.hashCode()); println(setOf(1, 2).hashCode()); println(mapOf(1 to 2).hashCode()); println(Pair(1, 2).hashCode())
}
