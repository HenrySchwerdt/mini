// Mini extension: the matrix operator `@` (a @ b == a.matmul(b), a @= b == matmulAssign / a = a @ b).
// Not valid Kotlin: marked with matmul_operator.nok2 so the K2-based comparisons skip it.

class Vec(val x: Int, val y: Int) {
    override fun toString() = "($x, $y)"
}

class Matrix(val a: Int, val b: Int, val c: Int, val d: Int) {
    // member operator: matrix * matrix
    operator fun matmul(o: Matrix): Matrix =
        Matrix(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d)

    // overload: matrix * vector
    operator fun matmul(v: Vec): Vec = Vec(a * v.x + b * v.y, c * v.x + d * v.y)

    operator fun plus(o: Matrix): Matrix = Matrix(a + o.a, b + o.b, c + o.c, d + o.d)

    override fun toString() = "[$a $b; $c $d]"
}

// extension operator: dot product
operator fun Vec.matmul(o: Vec): Int = x * o.x + y * o.y

// Int has no `matmul` by default: this extension makes `i @ j` valid in find()
operator fun Int.matmul(o: Int): Int = this * o

// `@=` through matmulAssign: in-place update
class Acc(var v: Int) {
    operator fun matmulAssign(k: Int) {
        v = v * 10 + k
    }
}

// `@=` through `a = a @ b` (no matmulAssign)
class Num(val n: Int) {
    operator fun matmul(k: Int): Num = Num(n * 100 + k)
}

class Holder {
    var m = Matrix(1, 1, 0, 1)
}

fun find(limit: Int): Int {
    var found = -1
    outer@ for (i in 0..limit) {
        for (j in 0..limit) {
            if (i @ j == 6) {
                found = i * 10 + j
                break@outer
            }
        }
    }
    return found
}

fun main() {
    val m = Matrix(1, 2, 3, 4)
    val id = Matrix(1, 0, 0, 1)
    val v = Vec(5, 6)
    println(m @ id)
    println(m @ v)
    println(v @ v)
    // chaining is left-associative: (m @ m) @ m
    println(m @ m @ m)
    println(m @ m @ v)
    // `@` binds like `*`: a + b @ c == a + (b @ c)
    println(id + m @ m)
    println((id + m) @ m)
    // same level as `*`, left to right
    println(2 * 3 @ 4)
    println(24 / 2 @ 3)

    var mm = Matrix(1, 1, 0, 1)
    mm @= mm
    mm @= mm
    println(mm)

    val acc = Acc(1)
    acc @= 2
    acc @= 3
    println(acc.v)

    var n = Num(1)
    n @= 2
    n @= 3
    println(n.n)

    val h = Holder()
    h.m @= h.m
    println(h.m)

    println(find(5))

    // labels stay as they were
    val xs = listOf(1, 2, 3)
    var sum = 0
    xs.forEach {
        if (it == 2) return@forEach
        sum += it
    }
    println(sum)
    println(Matrix(2, 0, 0, 2) @ Matrix(1, 2, 3, 4))
}
