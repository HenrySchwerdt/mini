// value class wrapping a generic/nullable/interface type
interface Greeter { fun hi(): String }
@JvmInline value class Wrap<T>(val x: T) { fun get() = x }
@JvmInline value class G(val n: String) : Greeter { override fun hi() = "hi " + n }
@JvmInline value class MaybeInt(val v: Int?) { fun orZero() = v ?: 0 }
@JvmInline value class Chars(val c: Char) { fun up() = Chars(c.uppercaseChar()) }
fun greet(g: Greeter) = g.hi()
fun <T> twice(f: () -> T): List<T> = listOf(f(), f())
fun main() {
    val w = Wrap(5); println(w.get() + 1); println(w)
    val ws = Wrap("s"); println(ws.x.length)
    println(greet(G("bob")))
    val gs: List<Greeter> = listOf(G("a"), G("b")); println(gs.map { it.hi() })
    println(MaybeInt(null).orZero()); println(MaybeInt(7).orZero()); println(MaybeInt(null) == MaybeInt(null))
    println(Chars('a').up().c)
    println(twice { Wrap(1) })
    val o: Wrap<Int>? = Wrap(2); println(o?.x)
    println(Wrap(Wrap(3)).x.x)
    println(listOf(Chars('x'), Chars('y')).map { it.c }.joinToString(""))
}
