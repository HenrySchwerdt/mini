data class V(val x: Int, val y: Int) {
    operator fun plus(o: V) = V(x + o.x, y + o.y)
    operator fun minus(o: V) = V(x - o.x, y - o.y)
    operator fun times(k: Int) = V(x * k, y * k)
    operator fun unaryMinus() = V(-x, -y)
    operator fun not() = V(y, x)
    operator fun inc() = V(x + 1, y + 1)
    operator fun get(i: Int) = if (i == 0) x else y
    operator fun compareTo(o: V) = (x + y) - (o.x + o.y)
    operator fun invoke(k: Int) = x * k + y
    operator fun contains(n: Int) = n == x || n == y
    operator fun rangeTo(o: V) = listOf(this, o)
}

class Grid(val w: Int, val h: Int) {
    private val cells = IntArray(w * h)
    operator fun get(r: Int, c: Int) = cells[r * w + c]
    operator fun set(r: Int, c: Int, v: Int) { cells[r * w + c] = v }
}

class Acc { var total = 0; operator fun plusAssign(n: Int) { total += n } }

fun main() {
    val a = V(1, 2)
    val b = V(3, 4)
    println(a + b)
    println(b - a)
    println(a * 3)
    println(-a)
    println(!a)
    var c = a
    c++
    println(c)
    println(a[0] + a[1])
    println(a < b)
    println(a >= b)
    println(a(10))
    println(2 in a)
    println(5 !in a)
    println(a..b)
    val g = Grid(3, 2)
    g[1, 2] = 7
    g[0, 0] += 5
    println(g[1, 2] + g[0, 0])
    val acc = Acc()
    acc += 3
    acc += 4
    println(acc.total)
    var v = V(1, 1)
    v += V(2, 2)
    v *= 2
    println(v)
    println(a == V(1, 2))
    println(a.equals(b))
}
