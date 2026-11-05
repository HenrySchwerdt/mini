interface Shape {
    fun area(): Int
}

@JvmInline
value class Meters(val v: Int) : Shape {
    init {
        require(v >= 0) { "negative" }
    }
    fun double(): Meters = Meters(v * 2)
    operator fun plus(o: Meters): Meters = Meters(v + o.v)
    override fun area(): Int = v * v
    val half: Int get() = v / 2
}

value class Name(val s: String) {
    override fun toString(): String = "Name:" + s
}

value class Wrapper<T>(val inner: T)

value class Id(val raw: Long) : Comparable<Id> {
    override fun compareTo(other: Id): Int = raw.compareTo(other.raw)
}

fun <T> describe(x: T): String = "value " + x.toString()

fun main() {
    val a = Meters(3)
    val b = Meters(4)
    println(a)
    println(a + b)
    println(a.double())
    println(a == Meters(3))
    println(a == b)
    println(a != b)
    println(a.hashCode())
    println(a.half)
    println(a.area())
    val s: Shape = a
    println(s.area())
    val n: Meters? = a
    println(n)
    val nn: Meters? = null
    println(nn)
    println(n == a)
    val l = listOf(Meters(1), Meters(2), Meters(3))
    println(l)
    println(l.size)
    println(l[1].double())
    val any: Any = a
    println(any)
    println(any is Meters)
    println(any is Shape)
    println(any == Meters(3))
    println(describe(a))
    println(Name("x"))
    println(Name("x") == Name("x"))
    println(Name("x").hashCode() == "x".hashCode())
    println(Wrapper(5))
    println(Wrapper("s").inner)
    println(Wrapper(Meters(2)))
    println(Id(5) < Id(7))
    println(maxOf(Id(5), Id(7)))
    println("m=$a")
    val m = HashMap<Meters, String>()
    m[Meters(1)] = "one"
    m[Meters(2)] = "two"
    println(m[Meters(1)])
    println(m.size)
    try {
        Meters(-1)
    } catch (e: IllegalArgumentException) {
        println("caught " + e.message)
    }
    var total = Meters(0)
    for (i in 1..4) total = total + Meters(i)
    println(total)
}
