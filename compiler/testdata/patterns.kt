// Comparable and generic bounds, sealed hierarchies, generic visitors, caches with function values,
// companion factories.

class Version(val major: Int, val minor: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        if (major != other.major) major - other.major else minor - other.minor
    override fun toString(): String = "" + major + "." + minor
}

fun <T : Comparable<T>> sortInPlace(r: Array<T>): Array<T> {
    for (i in 1 until r.size) {
        val x = r[i]
        var j = i - 1
        while (j >= 0 && r[j] > x) {
            r[j + 1] = r[j]
            j -= 1
        }
        r[j + 1] = x
    }
    return r
}

sealed class Expr
class Num(val v: Int) : Expr()
class Add(val l: Expr, val r: Expr) : Expr()
class Mul(val l: Expr, val r: Expr) : Expr()
class Neg(val e: Expr) : Expr()

fun eval(e: Expr): Int = when (e) {
    is Num -> e.v
    is Add -> eval(e.l) + eval(e.r)
    is Mul -> eval(e.l) * eval(e.r)
    is Neg -> -eval(e.e)
}

interface Visitor<R> {
    fun visitNum(n: Num): R
    fun visitAdd(a: Add): R
}
fun <R> accept(e: Expr, v: Visitor<R>): R? = when (e) {
    is Num -> v.visitNum(e)
    is Add -> v.visitAdd(e)
    else -> null
}

class Cache<K, V>(val compute: (K) -> V) {
    private val keys = arrayOfNulls<Any>(8)
    private val vals = arrayOfNulls<Any>(8)
    private var n = 0
    var hits = 0
    @Suppress("UNCHECKED_CAST")
    fun get(k: K): V {
        for (i in 0 until n) {
            if (keys[i] == k) { hits += 1; return vals[i] as V }
        }
        val v = compute(k)
        keys[n] = k
        vals[n] = v
        n += 1
        return v
    }
}

object Registry {
    private var next = 100
    fun nextId(): Int { next += 1; return next }
}

class Factory private constructor(val id: Int) {
    companion object {
        fun create(): Factory = Factory(Registry.nextId())
    }
}

fun run(f: (() -> Int)?): Int = if (f != null) f() else -1

fun main() {
    println(Version(1, 2) < Version(1, 10))
    val vs = arrayOf(Version(2, 0), Version(1, 5), Version(1, 2))
    println(sortInPlace(vs).contentToString())
    println(sortInPlace(arrayOf(3, 1, 2)).contentToString())
    println(sortInPlace(arrayOf("pear", "apple", "fig")).contentToString())
    println(eval(Add(Num(2), Mul(Num(3), Neg(Num(4))))))
    val printer = object : Visitor<String> {
        override fun visitNum(n: Num): String = "n" + n.v
        override fun visitAdd(a: Add): String = "add"
    }
    println(accept(Add(Num(1), Num(2)), printer))
    println(accept(Num(7), printer))
    println(accept(Neg(Num(1)), printer))
    val c = Cache<Int, String> { "v" + it * 2 }
    println(c.get(1) + c.get(2) + c.get(1))
    println(c.hits)
    println(Factory.create().id + Factory.create().id)
    println(run(null))
    println(run { 5 })
}
