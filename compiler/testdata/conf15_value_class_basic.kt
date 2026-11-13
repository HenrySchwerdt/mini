// value classes: boxing at generic boundaries, equality, toString, nullable
@JvmInline value class Meters(val v: Int) { operator fun plus(o: Meters) = Meters(v + o.v); override fun toString() = v.toString() + "m"; fun double() = Meters(v * 2) }
@JvmInline value class Name(val s: String) { val len get() = s.length }
fun <T> ident(t: T): T = t
fun takeAny(a: Any): String = a.toString()
fun nullable(m: Meters?): String = m?.toString() ?: "none"
fun main() {
    val a = Meters(3); val b = Meters(4)
    println(a + b); println((a + b).double())
    println(ident(a)); println(takeAny(a))
    println(a == Meters(3)); println(a == b); println(a.equals(Meters(3)))
    println(nullable(a)); println(nullable(null))
    val l = listOf(a, b, Meters(3)); println(l); println(l.indexOf(Meters(4))); println(l.toSet().size)
    val m = mapOf(Name("x") to 1, Name("y") to 2); println(m[Name("y")])
    println(Name("hello").len)
    val any: Any = a; println(any is Meters); println(any is Int)
    val n: Meters? = null; println(n == null); println(n?.v)
    println(a.hashCode() == Meters(3).hashCode())
    val arr = arrayOf(a, b); println(arr.size); println(arr[1])
}
