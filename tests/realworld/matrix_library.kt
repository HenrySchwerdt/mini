// A small linear algebra library with operator overloading: matrices over doubles, vectors, rational numbers,
// determinant, inverse, powers, and solving linear systems.

import kotlin.math.abs
import kotlin.math.sqrt

class Vec(val xs: DoubleArray) {
    constructor(vararg v: Double, dummy: Unit = Unit) : this(v)

    val size get() = xs.size
    operator fun get(i: Int) = xs[i]
    operator fun plus(o: Vec) = Vec(DoubleArray(size) { xs[it] + o.xs[it] })
    operator fun minus(o: Vec) = Vec(DoubleArray(size) { xs[it] - o.xs[it] })
    operator fun times(k: Double) = Vec(DoubleArray(size) { xs[it] * k })
    operator fun unaryMinus() = this * -1.0
    infix fun dot(o: Vec): Double = xs.indices.sumOf { xs[it] * o.xs[it] }
    fun norm() = sqrt(this dot this)
    override fun equals(other: Any?) = other is Vec && xs.contentEquals(other.xs)
    override fun hashCode() = xs.contentHashCode()
    override fun toString() = xs.joinToString(", ", "(", ")") { fmt(it) }
}

operator fun Double.times(v: Vec) = v * this

fun fmt(d: Double): String {
    val r = kotlin.math.round(d * 1000) / 1000
    return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
}

class Matrix(val rows: Int, val cols: Int, init: (Int, Int) -> Double = { _, _ -> 0.0 }) {
    private val data = Array(rows) { r -> DoubleArray(cols) { c -> init(r, c) } }

    operator fun get(r: Int, c: Int) = data[r][c]
    operator fun set(r: Int, c: Int, v: Double) {
        data[r][c] = v
    }

    operator fun plus(o: Matrix): Matrix {
        require(rows == o.rows && cols == o.cols) { "shape mismatch" }
        return Matrix(rows, cols) { r, c -> this[r, c] + o[r, c] }
    }

    operator fun times(o: Matrix): Matrix {
        require(cols == o.rows) { "cannot multiply ${rows}x$cols by ${o.rows}x${o.cols}" }
        return Matrix(rows, o.cols) { r, c -> (0 until cols).sumOf { this[r, it] * o[it, c] } }
    }

    operator fun times(k: Double) = Matrix(rows, cols) { r, c -> this[r, c] * k }
    operator fun times(v: Vec) = Vec(DoubleArray(rows) { r -> (0 until cols).sumOf { this[r, it] * v[it] } })
    operator fun unaryMinus() = this * -1.0

    fun transpose() = Matrix(cols, rows) { r, c -> this[c, r] }

    infix fun pow(n: Int): Matrix {
        require(rows == cols && n >= 0)
        var result = identity(rows)
        var base = this
        var e = n
        while (e > 0) {
            if (e and 1 == 1) result *= base
            base *= base
            e = e shr 1
        }
        return result
    }

    fun determinant(): Double {
        require(rows == cols)
        val m = copy()
        var det = 1.0
        for (i in 0 until rows) {
            var pivot = i
            for (r in i + 1 until rows) if (abs(m[r, i]) > abs(m[pivot, i])) pivot = r
            if (abs(m[pivot, i]) < 1e-12) return 0.0
            if (pivot != i) {
                m.swapRows(pivot, i)
                det = -det
            }
            det *= m[i, i]
            for (r in i + 1 until rows) {
                val f = m[r, i] / m[i, i]
                for (c in i until cols) m[r, c] = m[r, c] - f * m[i, c]
            }
        }
        return det
    }

    fun inverse(): Matrix? {
        require(rows == cols)
        val a = Matrix(rows, cols * 2) { r, c -> if (c < cols) this[r, c] else if (c - cols == r) 1.0 else 0.0 }
        for (i in 0 until rows) {
            var pivot = i
            for (r in i + 1 until rows) if (abs(a[r, i]) > abs(a[pivot, i])) pivot = r
            if (abs(a[pivot, i]) < 1e-12) return null
            a.swapRows(pivot, i)
            val p = a[i, i]
            for (c in 0 until a.cols) a[i, c] = a[i, c] / p
            for (r in 0 until rows) if (r != i) {
                val f = a[r, i]
                for (c in 0 until a.cols) a[r, c] = a[r, c] - f * a[i, c]
            }
        }
        return Matrix(rows, cols) { r, c -> a[r, c + cols] }
    }

    fun solve(b: Vec): Vec? = inverse()?.let { it * b }

    private fun swapRows(i: Int, j: Int) {
        val t = data[i]
        data[i] = data[j]
        data[j] = t
    }

    fun copy() = Matrix(rows, cols) { r, c -> this[r, c] }

    override fun equals(other: Any?): Boolean {
        if (other !is Matrix || rows != other.rows || cols != other.cols) return false
        for (r in 0 until rows) for (c in 0 until cols) if (abs(this[r, c] - other[r, c]) > 1e-9) return false
        return true
    }

    override fun hashCode() = rows * 31 + cols

    override fun toString() = data.joinToString("\n") { row -> row.joinToString(" ") { fmt(it).padStart(7) } }

    companion object {
        fun identity(n: Int) = Matrix(n, n) { r, c -> if (r == c) 1.0 else 0.0 }
        fun of(vararg rows: List<Double>) = Matrix(rows.size, rows[0].size) { r, c -> rows[r][c] }
    }
}

data class Rational(val num: Long, val den: Long = 1) : Comparable<Rational> {
    init {
        require(den != 0L) { "zero denominator" }
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcd(b, a % b)
    fun normalized(): Rational {
        val g = gcd(num, den)
        val s = if (den < 0) -1 else 1
        return Rational(s * num / g, s * den / g)
    }

    operator fun plus(o: Rational) = Rational(num * o.den + o.num * den, den * o.den).normalized()
    operator fun minus(o: Rational) = Rational(num * o.den - o.num * den, den * o.den).normalized()
    operator fun times(o: Rational) = Rational(num * o.num, den * o.den).normalized()
    operator fun div(o: Rational) = Rational(num * o.den, den * o.num).normalized()
    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun toString() = normalized().let { if (it.den == 1L) "${it.num}" else "${it.num}/${it.den}" }
}

fun abs(x: Long) = if (x < 0) -x else x

fun main() {
    val a = Matrix.of(listOf(2.0, 1.0, 0.0), listOf(1.0, 3.0, 1.0), listOf(0.0, 1.0, 4.0))
    val b = Matrix.of(listOf(1.0, 2.0), listOf(0.0, 1.0), listOf(4.0, 0.0))
    println("A =\n$a")
    println("A*B =\n${a * b}")
    println("A^T == A: ${a.transpose() == a}")
    println("det(A) = ${fmt(a.determinant())}")
    val inv = a.inverse()!!
    println("inverse =\n$inv")
    println("A * A^-1 == I: ${a * inv == Matrix.identity(3)}")
    println("A^5 =\n${a pow 5}")
    val x = a.solve(Vec(1.0, 2.0, 3.0))!!
    println("solution of A x = (1,2,3): $x -> check ${a * x}")
    val singular = Matrix.of(listOf(1.0, 2.0), listOf(2.0, 4.0))
    println("singular det=${fmt(singular.determinant())} inverse=${singular.inverse()}")
    try {
        a * Matrix.identity(2)
    } catch (e: IllegalArgumentException) {
        println("error: ${e.message}")
    }
    val v = Vec(3.0, 4.0)
    println("v=$v |v|=${fmt(v.norm())} -v=${-v} 2v=${2.0 * v} v.v=${fmt(v dot v)} v+v=${v + v} v==v: ${v == Vec(3.0, 4.0)}")
    val fibm = Matrix.of(listOf(1.0, 1.0), listOf(1.0, 0.0))
    println("fib(30) via matrix power: ${fmt((fibm pow 30)[0, 1])}")
    val h = Rational(1, 2) + Rational(1, 3) * Rational(3, 4) - Rational(1, 6)
    println("rational: $h, ${Rational(6, -8)}, ${Rational(1, 3) / Rational(2, 9)}, max=${listOf(Rational(1, 3), Rational(2, 5), Rational(1, 4)).max()}")
    val hilbert = Matrix(4, 4) { r, c -> 1.0 / (r + c + 1) }
    println("hilbert det ~ ${hilbert.determinant()}")
}
