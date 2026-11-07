// Conformance round 8 (b): `while (break)` and `throw ... as T` (Nothing operands), `recv.prop(args)` where the property of
// function type with receiver comes from an implicit receiver, a Unit value before an argument with side statements,
// expected types for nested generic calls (`val l: List<Box<Any?>> = listOf(Box(0))`, `a[i] = Box(2)`), `A::member` of the
// companion object, a lambda for an `in`-projected fun interface, same-named entry bodies of two enums, `Array(n, return x)`,
// `::Z` of a value class, an extension property named like a member, `pkg.property`, `$"..."` (one-dollar prefix).
class Box<T>(val h: T)

val boxes: ArrayList<Box<Any?>> = arrayListOf(Box(0))
val boxList: List<Box<Any?>> = listOf(Box("s"))

class Holder(val foo: Int.() -> Int)

fun breakInCondition(): String {
    var i = 0
    while (true) {
        ++i
        while (break) {
            i += 100
        }
        if (i == 3) break
    }
    return "i=$i"
}

fun castOfThrow(): String {
    try {
        when (1) {
            else -> throw IllegalStateException("x")
        } as String
    } catch (e: IllegalStateException) {
        return "caught"
    }
    return "none"
}

fun <T> eq(a: T, b: T) = a == b

fun unitBefore(n: Number, b: Boolean) {
    n.let {
        if (b) return@let
        listOf(42)
    }
}

class A {
    companion object {
        fun foo(): String = "companion foo"
        val bar = "companion bar"
    }
}

fun call(f: () -> String) = f()

fun interface Element {
    fun invoke(): String
}

class Container<T> {
    var last: T? = null
    fun add(arg: T) { last = arg }
}

fun test(c: Container<in Element>) {
    c.add(Element { "sam" })
}

enum class E1 {
    X,
    Y {
        override fun f() = "E1.Y"
    };

    open fun f() = "E1"
}

enum class E2 {
    X,
    Y {
        override fun f() = "E2.Y"
    };

    open fun f() = "E2"
}

@JvmInline
value class Z(val x: Int)

class Chars : CharSequence {
    private val List<String>.length: Int
        get() = size
    override val length: Int
        get() = 2
    override fun get(index: Int): Char = if (index == 0) 'O' else 'K'
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
}

fun arrayReturn(): String {
    val test: Array<Int> = Array(42, return "returned")
    return "not reached"
}

fun main() {
    println(breakInCondition())
    println(castOfThrow())
    val h = Holder({ this + 1 })
    println(with(h) { 5.foo() })
    println(with(h) { foo(6) })
    println(eq(Unit, unitBefore(1, false)))
    boxes[0] = Box(2)
    println(boxes[0].h)
    println(boxList[0].h)
    println(call(A::foo))
    println(call(A::bar))
    val c = Container<Element>()
    test(c)
    println(c.last!!.invoke())
    println(E1.Y.f() + " " + E2.Y.f() + " " + E1.X.f() + " " + E2.X.f())
    println(arrayReturn())
    println(42.let(::Z).x)
    println(Chars().length)
    println(kotlin.math.E > 2.0)
    println($"dollar ${h.foo(1)}")
}
