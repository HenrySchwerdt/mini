// Type aliases: plain, generic, function types, alias of alias, constructors and companions through an alias.
typealias IntList = List<Int>
typealias Str = String
typealias Pred<T> = (T) -> Boolean
typealias BinOp = (Int, Int) -> Int
typealias Table<V> = MutableList<V>
typealias Pairs<A, B> = List<Pair<A, B>>
typealias Names = List<Str>
typealias Handler = (String) -> Unit
typealias MaybeInt = Int?

class Point(val x: Int, val y: Int) {
    companion object {
        fun origin() = Point(0, 0)
        const val DIMS = 2
    }
    override fun toString() = "($x,$y)"
}
typealias P = Point

enum class Color { RED, GREEN }
typealias Col = Color

object Config { val name = "cfg" }
typealias Cfg = Config

interface Shape { fun area(): Int }
class Sq(val s: Int) : Shape { override fun area() = s * s }
typealias AnyShape = Shape

fun count(l: IntList, p: Pred<Int>): Int = l.count(p)
fun apply(op: BinOp, a: Int, b: Int) = op(a, b)
fun fill(t: Table<Int>, k: String, v: Int) { t.add(v + k.length) }
fun firsts(p: Pairs<Int, String>): List<Int> = p.map { it.first }
fun joinAll(n: Names): Str = n.joinToString("+")
fun run(h: Handler, s: Str) = h(s)
fun maybe(x: MaybeInt): Int = x ?: -1
fun area(s: AnyShape) = s.area()

class Holder(val items: IntList, val f: Pred<Str>) {
    fun test() = items.size > 1 && f("x")
}

fun main() {
    val s: Str = "a"
    println(s)
    println(count(listOf(1, 2, 3)) { it > 1 })
    val sum: BinOp = { a, b -> a + b }
    println(apply(sum, 2, 3))
    println(apply({ a, b -> a * b }, 4, 5))
    val t: Table<Int> = mutableListOf()
    fill(t, "k", 7)
    println(t)
    println(firsts(listOf(1 to "a", 2 to "b")))
    println(joinAll(listOf("x", "y")))
    run({ println("got $it") }, "h")
    println(maybe(null))
    println(maybe(5))
    val p = P(3, 4)
    println(p)
    println(P.origin())
    println(P.DIMS)
    println(Col.RED)
    println(Col.valueOf("GREEN"))
    println(Cfg.name)
    println(area(Sq(3)))
    println(Holder(listOf(1, 2), { it.isNotEmpty() }).test())
    val preds: List<Pred<Int>> = listOf({ it > 0 }, { it < 0 })
    println(preds.map { it(5) })
}
