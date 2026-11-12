package surface

annotation class Marker(val note: String = "")

typealias Builder<T> = T.() -> Unit
typealias Mapper<A, B> = (A) -> B

@JvmInline
value class Id<T>(val raw: Int) {
    inline val next: Id<T> get() = Id(raw + 1)
    override fun toString() = "#" + raw
}

enum class Level { LOW, MID, HIGH }

interface Shape {
    val sides: Int
    fun area(scale: Int = 1): Int
    companion object {
        const val UNIT = 10
        fun square(n: Int): Shape = Square(n)
    }
}

private class Square(val n: Int) : Shape {
    override val sides get() = 4
    override fun area(scale: Int) = n * n * scale
}

abstract class Widget(val title: String) {
    abstract val weight: Int
    open fun render(width: Int = title.length + weight, fill: Char = '.'): String = title + fill.toString().repeat(width - title.length)
}

class Box<out T>(val item: T) {
    fun <R> map(f: Mapper<T, R>): Box<R> = Box(f(item))
    override fun toString() = "Box(" + item + ")"
}

val emptyBox: Box<Nothing>? = null

object Config {
    const val NAME = "cfg"
    @JvmField val limit = 3
    @JvmStatic fun twice(x: Int) = 2 * x
    var level = Level.LOW
}

inline fun <T> build(init: Builder<T>, make: () -> T): T = make().also(init)

inline fun findFirst(xs: List<Int>, pred: (Int) -> Boolean): Int {
    for (x in xs) if (pred(x)) return x
    return -1
}

inline fun <T> retryTimes(times: Int, block: (Int) -> T?): T? {
    for (i in 1..times) {
        val r = block(i)
        if (r != null) return r
    }
    return null
}

inline fun withLabel(label: String, block: () -> Unit): String {
    block()
    return "[" + label + "]"
}

inline fun <T> T.applyIf(cond: Boolean, f: (T) -> T = { it }): T = if (cond) f(this) else this

class Counter {
    var n = 0
    inline fun bump(by: Int = 1): Counter { n += by; return this }
}

@Marker("on a class")
class Tagged {
    @Marker fun m() = "m"
}

fun levelName(l: Level): String = when (l) {
    Level.LOW -> "low"
    Level.MID -> "mid"
    Level.HIGH -> "high"
}

operator fun <T> List<T>.get(i: Id<T>): T = this[i.raw]
infix fun Int.pow(e: Int): Int { var r = 1; repeat(e) { r *= this }; return r }
fun Shape.describe(): String = "shape with " + sides + " sides, area " + area()
