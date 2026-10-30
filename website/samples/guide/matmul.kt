class Vec(val x: Int, val y: Int) {
    override fun toString() = "($x, $y)"
}

class Matrix(val a: Int, val b: Int, val c: Int, val d: Int) {
    operator fun matmul(o: Matrix): Matrix =
        Matrix(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d)

    operator fun matmul(v: Vec): Vec = Vec(a * v.x + b * v.y, c * v.x + d * v.y)

    override fun toString() = "[$a $b; $c $d]"
}

// an extension works too: the dot product of two vectors
operator fun Vec.matmul(o: Vec): Int = x * o.x + y * o.y

fun main() {
    val m = Matrix(1, 2, 3, 4)
    val v = Vec(5, 6)
    println(m @ m)
    println(m @ v)
    println(v @ v)
    println(m @ m @ v)

    var p = Matrix(1, 1, 0, 1)
    p @= p
    p @= p
    println(p)
}
