@JvmInline value class Id(val v: Int) : Comparable<Id> {
    override fun compareTo(other: Id) = v.compareTo(other.v)
    fun next() = Id(v + 1)
    override fun toString() = "Id($v)"
}
@JvmInline value class Name(val s: String) { val len get() = s.length }
typealias Table = Map<Id, Name>
typealias Pred<T> = (T) -> Boolean

fun filterT(t: Table, p: Pred<Name>): Table = t.filterValues(p)

fun interface Visitor<R> { fun visit(n: Int): R }
fun <R> walk(xs: List<Int>, v: Visitor<R>) = xs.map { v.visit(it) }

fun main() {
    val ids = listOf(Id(3), Id(1), Id(2))
    println(ids.sorted())
    println(ids.max())
    println(Id(1).next().next())
    println(Name("abc").len)
    val t: Table = mapOf(Id(1) to Name("one"), Id(2) to Name("three"))
    println(filterT(t) { it.len > 3 })
    println(walk(listOf(1, 2)) { it * 10 })
    println(walk(listOf(1, 2), Visitor { "v$it" }))
    println(Id(5) == Id(5))
    println(listOf(Id(1), Id(1)).distinct().size)
    println(mapOf(Id(1) to 1)[Id(1)])
    val nid: Id? = null
    println(nid?.next())
    println(Id(2).hashCode() == Id(2).hashCode())
    println(setOf(Name("a"), Name("a")).size)
}
