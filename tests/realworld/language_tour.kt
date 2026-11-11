// A tour of language features in realistic combinations: variance, nested / inner / local / anonymous classes, operator conventions,
// references, inline functions with crossinline / noinline / reified, enums with behaviour, control flow, scoping and initialisation order.

import kotlin.reflect.KProperty1

interface Source<out T> {
    fun next(): T?
}

interface Sink<in T> {
    fun accept(x: T)
}

class ListSource<T>(private val items: List<T>) : Source<T> {
    private var i = 0
    override fun next(): T? = items.getOrNull(i++)
}

class PrintSink : Sink<Any> {
    val seen = ArrayList<String>()
    override fun accept(x: Any) {
        seen.add(x.toString())
    }
}

fun <T> pump(from: Source<T>, to: Sink<T>): Int {
    var n = 0
    while (true) {
        val x = from.next() ?: break
        to.accept(x)
        n++
    }
    return n
}

class Vec2(val x: Int, val y: Int) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun get(i: Int) = if (i == 0) x else y
    operator fun contains(v: Int) = v == x || v == y
    operator fun component1() = x
    operator fun component2() = y
    operator fun invoke(k: Int) = Vec2(x * k, y * k)
    operator fun compareTo(o: Vec2) = (x * x + y * y).compareTo(o.x * o.x + o.y * o.y)
    operator fun inc() = Vec2(x + 1, y + 1)
    operator fun not() = Vec2(y, x)
    operator fun rangeTo(o: Vec2): List<Vec2> = (x..o.x).map { Vec2(it, y) }
    override fun toString() = "($x, $y)"
}

class Grid(val w: Int, val h: Int) : Iterable<Pair<Int, Int>> {
    private val data = IntArray(w * h)
    operator fun get(x: Int, y: Int) = data[y * w + x]
    operator fun set(x: Int, y: Int, v: Int) {
        data[y * w + x] = v
    }

    override fun iterator() = iterator {
        for (y in 0 until h) for (x in 0 until w) yield(x to y)
    }
}

enum class Op(val symbol: String, private val f: (Int, Int) -> Int) {
    ADD("+", { a, b -> a + b }),
    SUB("-", { a, b -> a - b }),
    MUL("*", { a, b -> a * b }) {
        override fun describe() = "multiplication"
    },
    POW("^", { a, b -> var r = 1; repeat(b) { r *= a }; r });

    fun apply(a: Int, b: Int) = f(a, b)
    open fun describe() = name.lowercase()

    companion object {
        fun bySymbol(s: String) = values().first { it.symbol == s }
    }
}

class Outer(val name: String) {
    private var secret = 42

    inner class Inner(val tag: String) {
        fun reveal() = "$name/$tag/$secret"
    }

    class Nested(val v: Int) {
        fun twice() = v * 2
    }

    fun make(tag: String) = Inner(tag)
    fun local(n: Int): List<String> {
        class Local(val i: Int) {
            fun label() = "$name-$i"
        }
        return (1..n).map { Local(it).label() }
    }
}

open class Base(val id: Int) {
    init {
        println("Base init $id")
    }

    open val kind = "base"
    open fun hello() = "hello from $kind $id"
    protected open fun secret() = "base-secret"
    fun reveal() = secret()
}

class Derived(id: Int, extra: String) : Base(id) {
    val extra = extra.uppercase()

    init {
        println("Derived init $extra")
    }

    override val kind = "derived"
    override fun secret() = "derived-" + super.secret()
}

inline fun <T> measure(label: String, block: () -> T): T {
    val r = block()
    println("  [$label done]")
    return r
}

inline fun <reified T> describeType(x: Any?): String = when (x) {
    is T -> "${T::class.simpleName}!"
    null -> "null"
    else -> "other"
}

inline fun run2(crossinline a: () -> Unit, noinline b: () -> Unit): () -> Unit {
    a()
    return b
}

fun classify(n: Int): String = when {
    n < 0 -> "negative"
    n == 0 -> "zero"
    n in 1..9 -> "digit"
    n % 2 == 0 -> "big even"
    else -> "big odd"
}

fun tryFinally(): String {
    val log = StringBuilder()
    fun inner(): Int {
        try {
            log.append("try ")
            return 1
        } finally {
            log.append("finally ")
        }
    }
    log.append(inner())
    return log.toString()
}

val topLevelInit = run {
    println("top-level init")
    7
}

fun main() {
    println("-- variance")
    val sink = PrintSink()
    println(pump(ListSource(listOf(1, 2, 3)), sink).toString() + " " + pump(ListSource(listOf("a", "b")), sink) + " " + sink.seen)
    println("-- operators")
    var v = Vec2(1, 2)
    val (a, b) = v + Vec2(10, 20)
    println("$a $b ${v[1]} ${2 in v} ${5 in v} ${v(3)} ${v > Vec2(1, 1)} ${!v} ${v++} $v ${Vec2(1, 5)..Vec2(3, 5)}")
    val g = Grid(3, 2)
    for ((x, y) in g) g[x, y] = x * 10 + y
    println(g.map { (x, y) -> g[x, y] })
    println("-- enums")
    println(Op.values().joinToString { "${it.symbol}:${it.apply(2, 5)}" } + " " + Op.bySymbol("*").describe() + " " + Op.ADD.describe() + " " + Op.valueOf("POW").ordinal)
    println("-- classes")
    val o = Outer("out")
    println(o.make("t").reveal() + " " + Outer.Nested(21).twice() + " " + o.local(3))
    val d = Derived(5, "x")
    println(d.hello() + " " + d.reveal() + " " + d.extra)
    println("-- inline")
    println(measure("sum") { (1..10).sum() })
    println(describeType<String>("s") + describeType<Int>("s") + describeType<List<*>>(listOf(1)) + describeType<String>(null))
    var count = 0
    val deferred = run2({ count++ }, { count += 10 })
    deferred(); deferred()
    println(count)
    println("-- references")
    val names = listOf("kotlin", "java", "go")
    println(names.map(String::length) + names.map { it.length } + names.filter(String::isNotEmpty).map(String::uppercase))
    val prop: KProperty1<Vec2, Int> = Vec2::x
    println("${prop.get(Vec2(8, 9))} ${prop.name}")
    val bound = names::contains
    println(bound("go") to bound("c") to (::classify)(5) to listOf(-1, 0, 5, 12, 13).map(::classify))
    val adder: (Int) -> (Int) -> Int = { x -> { y -> x + y } }
    println(adder(3)(4) to (1..3).map(adder(10)))
    println("-- control flow")
    println(tryFinally())
    println(topLevelInit)
    val nested = (1..3).flatMap { i -> (1..3).mapNotNull { j -> if (i != j) i * 10 + j else null } }
    println(nested)
    outer@ for (i in 1..5) {
        for (j in 1..5) {
            if (i * j == 12) {
                println("found $i*$j")
                break@outer
            }
            if (j > i) continue@outer
        }
    }
    println((1..10 step 3).toList().toString() + (10 downTo 1 step 4).toList() + ('a'..'e').toList() + (1 until 4).reversed().toList() + (0..-1).toList().isEmpty())
    val x: Any = 42
    val s = when (x) {
        is String -> "string"
        is Int -> if (x > 40) "big int" else "int"
        else -> "?"
    }
    val len = (x as? String)?.length ?: -1
    println("$s $len ${(x as Int) + 1} ${x is Number}")
    val text: String? = null
    println(text?.length ?: "none" + (text ?: "dflt") + (text?.let { it.length } ?: 0))
    var late: String? = null
    println(late.also { late = "set" } + late)
}
