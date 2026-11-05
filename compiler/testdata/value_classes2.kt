value class Inner(val x: Int)
value class Outer(val i: Inner) {
    fun sum(o: Outer): Outer = Outer(Inner(i.x + o.i.x))
}

value class Flag(val on: Boolean) {
    fun flip(): Flag = Flag(!on)
}

value class Money(val cents: Long) : Comparable<Money> {
    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)
    override fun equals(other: Any?): Boolean = other is Money && other.cents / 100 == cents / 100
    override fun hashCode(): Int = (cents / 100).toInt()
    override fun toString(): String = "$" + (cents / 100) + "." + (cents % 100)
}

value class Label(val text: String) {
    fun shout(): String = text.uppercase() + "!"
}

value class Temp(val deg: Double) {
    operator fun plus(o: Temp): Temp = Temp(deg + o.deg)
}

value class Sec(val s: Int) {
    constructor(a: Int, b: Int) : this(a * 60 + b)
}

class Holder(val m: Inner, var f: Flag = Flag(true))

interface Named {
    val name: String
    fun greet(): String = "hi " + name
}

value class User(val id: Int) : Named {
    override val name: String get() = "user" + id
}

fun twice(i: Inner): Inner = Inner(i.x * 2)
fun maybe(b: Boolean): Inner? = if (b) Inner(7) else null
fun <T> pass(t: T): T = t
fun Inner.plusOne(): Inner = Inner(x + 1)
fun sumAll(vararg xs: Inner): Int {
    var t = 0
    for (x in xs) t += x.x
    return t
}

fun main() {
    val o = Outer(Inner(2)).sum(Outer(Inner(5)))
    println(o)
    println(o.i.x)
    val f = Flag(false)
    println(f.flip())
    println(f.flip().on)
    println(Money(1050) < Money(2000))
    println(Money(1050) == Money(1099))
    println(Money(1050))
    println(listOf(Money(300), Money(100), Money(200)).sorted())
    println(Label("ab").shout())
    println(Label("ab"))
    println(Temp(1.5) + Temp(2.0))
    println(Temp(1.5) == Temp(1.5))
    println(Sec(1, 30))
    val h = Holder(Inner(3))
    println(h.m.x)
    println(h.f)
    h.f = h.f.flip()
    println(h.f.on)
    val u = User(4)
    println(u.name)
    println(u.greet())
    val n: Named = u
    println(n.greet())
    println(twice(Inner(4)))
    println(maybe(true))
    println(maybe(false))
    println(maybe(true)?.x)
    println(maybe(false)?.x)
    println(maybe(true) ?: Inner(0))
    println(maybe(false) ?: Inner(0))
    println(pass(Inner(9)))
    println(pass(Inner(9)).x)
    println(Inner(1).plusOne().plusOne())
    println(sumAll(Inner(1), Inner(2), Inner(3)))
    var counter = Inner(0)
    val inc = { counter = Inner(counter.x + 1) }
    inc()
    inc()
    println(counter)
    val arr = arrayOf(Inner(1), Inner(2))
    arr[0] = Inner(5)
    println(arr[0].x + arr[1].x)
    val lst = mutableListOf<Inner>()
    lst.add(Inner(3))
    lst.add(Inner(1))
    println(lst)
    println(lst.map { it.x * 2 })
    val any: Any = Inner(8)
    if (any is Inner) println(any.x)
    println((any as Inner).x)
    val s = when (Flag(true)) {
        Flag(true) -> "on"
        else -> "off"
    }
    println(s)
    val fn: (Inner) -> Inner = { Inner(it.x + 100) }
    println(fn(Inner(1)))
    val set = HashSet<Inner>()
    set.add(Inner(1))
    set.add(Inner(1))
    println(set.size)
    println(Inner(1).hashCode())
    println(Inner(3) == Inner(3))
    println(Inner(3).equals(Inner(4)))
    println(Inner(3).equals("x"))
    println(Flag(true).hashCode())
    println("v=${Inner(5)} w=${Flag(true)}")
}
