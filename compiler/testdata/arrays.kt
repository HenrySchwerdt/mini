// Arrays (Array<T>, IntArray, LongArray, DoubleArray, BooleanArray, CharArray), vararg and spread,
// enum values() / valueOf() / entries.

enum class Color { RED, GREEN, BLUE }
enum class Planet(val mass: Int) { MERCURY(3), VENUS(5), EARTH(6) }

fun sum(vararg xs: Int): Int {
    var s = 0
    for (x in xs) s += x
    return s
}

fun join(sep: String, vararg parts: String): String {
    var out = ""
    for (i in parts.indices) {
        if (i > 0) out += sep
        out += parts[i]
    }
    return out
}

fun count(vararg xs: Any?): Int = xs.size
fun firstOr(default: Int, vararg xs: Int): Int = if (xs.size == 0) default else xs[0]
fun fill(n: Int): IntArray {
    val a = IntArray(n)
    for (i in 0 until n) a[i] = i * i
    return a
}
fun total(a: IntArray): Int {
    var t = 0
    for (x in a) t += x
    return t
}
fun reverse(a: Array<String>): Array<String> {
    val r = Array(a.size) { "" }
    for (i in a.indices) r[a.size - 1 - i] = a[i]
    return r
}

class Matrix(val n: Int) {
    val cells = Array(n) { IntArray(n) }
    fun set(r: Int, c: Int, v: Int) { cells[r][c] = v }
    fun trace(): Int {
        var t = 0
        for (i in 0 until n) t += cells[i][i]
        return t
    }
}

fun main() {
    // IntArray
    val a = IntArray(4)
    a[0] = 10
    a[1] = 20
    a[3] = a[0] + a[1]
    println(a.size)
    println(a.contentToString())
    println(total(a))
    println(fill(5).contentToString())
    println(intArrayOf(5, 6, 7)[2])

    // other primitive arrays
    val la = LongArray(2)
    la[1] = 1L shl 40
    println(la[1] + la[0])
    val da = doubleArrayOf(1.5, 2.5)
    da[0] = da[0] * 2.0
    println(da.contentToString())
    val ba = BooleanArray(3)
    ba[1] = true
    println(ba.contentToString())
    val ca = charArrayOf('a', 'b', 'c')
    ca[1] = 'z'
    println(ca.contentToString())

    // Array<T>
    val names = arrayOf("x", "y", "z")
    println(names.size)
    println(names[1] + names[2])
    for (s in names) print(s + ";")
    println()
    println(reverse(names).contentToString())
    val boxed = arrayOf(1, 2, 3)
    boxed[1] = boxed[1] * 10
    println(boxed[0] + boxed[1] + boxed[2])
    println(boxed.contentToString())
    val nulls = arrayOfNulls<String>(2)
    println(nulls.contentToString())
    nulls[0] = "set"
    println(nulls[0])
    println(nulls[1] == null)
    val squares = Array(4) { it * it }
    println(squares.contentToString())
    val m = Matrix(3)
    m.set(0, 0, 1); m.set(1, 1, 2); m.set(2, 2, 3)
    println(m.trace())
    println(m.cells[1].contentToString())

    // out of bounds is an exception
    try {
        println(a[4])
    } catch (e: ArrayIndexOutOfBoundsException) {
        println(e.message)
    }
    try {
        a[-1] = 0
    } catch (e: IndexOutOfBoundsException) {
        println(e.message)
    }
    try {
        val neg = IntArray(-1)
        println(neg.size)
    } catch (e: NegativeArraySizeException) {
        println("negative size " + e.message)
    }

    // vararg and spread
    println(sum())
    println(sum(1))
    println(sum(1, 2, 3))
    val more = intArrayOf(10, 20)
    println(sum(*more))
    println(sum(1, *more, 2, *more))
    println(join("-", "a", "b", "c"))
    println(join("+"))
    val parts = arrayOf("p", "q")
    println(join("/", *parts, "r"))
    println(count(1, "two", null, 4.0))
    println(firstOr(7))
    println(firstOr(7, 8, 9))

    // enums
    for (c in Color.values()) print(c.name + c.ordinal + " ")
    println()
    println(Color.valueOf("GREEN"))
    println(Color.values().size)
    var mass = 0
    for (p in Planet.values()) mass += p.mass
    println(mass)
    println(Planet.valueOf("EARTH").mass)
    try {
        println(Color.valueOf("PURPLE"))
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    val firstColor = Color.values()[0]
    println(firstColor == Color.RED)
}
