// value class: init blocks, secondary constructors, companion, inline in collections and when
@JvmInline value class Id(val raw: Long) {
    init { require(raw >= 0) { "negative id" } }
    constructor(s: String) : this(s.toLong())
    companion object { val ZERO = Id(0); fun of(x: Int) = Id(x.toLong()) }
    operator fun compareTo(o: Id) = raw.compareTo(o.raw)
    fun next() = Id(raw + 1)
}
@JvmInline value class Flag(val on: Boolean) { operator fun not() = Flag(!on) }
fun classify(i: Id): String = when (i) { Id.ZERO -> "zero"; Id(1) -> "one"; else -> "many" }
fun main() {
    println(Id("42").raw); println(Id.of(7).next().raw); println(Id.ZERO.raw)
    println(Id(3) < Id(4)); println(Id(5) >= Id(6))
    println(classify(Id(0)) + classify(Id(1)) + classify(Id(9)))
    try { Id(-1) } catch (e: IllegalArgumentException) { println(e.message) }
    println((!Flag(true)).on)
    val sorted = listOf(Id(3), Id(1), Id(2)).sortedBy { it.raw }; println(sorted.map { it.raw })
    println(listOf(Id(3), Id(1)).maxOf { it.raw })
    val m = HashMap<Id, String>(); m[Id(1)] = "a"; m[Id(1)] = "b"; println(m.size); println(m[Id(1)])
    var cur = Id(0); repeat(3) { cur = cur.next() }; println(cur.raw)
}
