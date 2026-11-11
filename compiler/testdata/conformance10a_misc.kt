// Round 10a: nested classifiers in a primary constructor header, `Enum` as a value, `vararg` passed as an array to an adapted
// reference, `invoke()` on an extension receiver of function type, a virtual `Throwable.cause`, function types joined.

class Foo2(val kind: Kind) {
    enum class Kind { FAIL, OK }
}

annotation class Foo(val kind: Kind) {
    enum class Kind { FAIL, OK }
}

enum class Test(val x: String, val closure1: () -> String) {
    FOO("O", { "K" }) {
        override val y: String = "Y"
    };

    abstract val y: String
}

fun use(fn: (IntArray, String) -> Unit) { fn(intArrayOf(42), "good") }
fun foo(vararg xs: Int, s1: String = "", s2: String = "OK") { println(xs[0].toString() + s1 + s2) }
fun bar(vararg xs: Int, s: String = "") { println(xs.size.toString() + s) }

inline fun (() -> String).twice(): () -> String = { invoke() + this.invoke() + this() }
fun (() -> String).both(): String = invoke() + this()

fun decode(value: String): Any = when (value[0]) {
    'F' -> String::toFloat
    else -> String::toInt
}(value.substring(2))

open class Base(message: String? = null, cause: Throwable? = null) : Throwable(message, cause)

open class Over(message: String? = null, cause: Throwable? = null) : Base(message, cause) {
    override val cause: Throwable?
        get() = super.cause ?: this
}

class Box<T>(val t: T)

fun main() {
    println(Foo2(Foo2.Kind.OK).kind)
    println(Foo.Kind.OK.name)
    println(Test.FOO.y + Test.FOO.closure1())
    val en = Enum
    println(en != null)

    use(::foo)
    use(::bar)

    println({ "OK" }.twice()())
    println({ "OK" }.both())
    println(decode("I:12"))

    val o = Over("m")
    val c: Throwable = o
    println(c.cause === o)
    println(Base("a", Base("b")).cause?.message)

    val j = if (c.cause === o) Box(1) else Box(2.5)
    println(j.t)
}
