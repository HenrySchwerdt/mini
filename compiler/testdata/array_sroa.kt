// Scalar replacement of small object arrays that do not escape (opt/ArraySroa.kt): every case prints the same
// as it would with the array allocated; the cases that must keep the array (escape, mutation, long loops, bad index) too.
class P(var x: Double, var v: Double, val m: Double)

class Cnt(var n: Int)

val keep = ArrayList<Array<Cnt>>()

fun step(ps: Array<P>, dt: Double) {
    for (i in 0 until 3) {
        val a = ps[i]
        for (j in i + 1 until 3) {
            val b = ps[j]
            val d = a.x - b.x
            val f = dt * d * 0.5
            a.v -= f * b.m
            b.v += f * a.m
        }
    }
    for (i in 0 until 3) {
        val p = ps[i]
        p.x += dt * p.v
    }
}

fun energy(ps: Array<P>): Double {
    var e = 0.0
    for (i in 0 until ps.size) {
        val a = ps[i]
        e += 0.5 * a.m * a.v * a.v
        for (j in i + 1 until ps.size) e -= a.m * ps[j].m / (1.0 + kotlin.math.abs(a.x - ps[j].x))
    }
    return e
}

fun basic() {
    val ps = arrayOf(P(0.0, 0.0, 3.0), P(1.0, 0.5, 2.0), P(-2.0, 0.25, 1.0))
    println((energy(ps) * 1000.0).toLong())
    for (s in 0 until 1000) step(ps, 0.01)
    println((energy(ps) * 1000.0).toLong())
    println((ps[0].x * 1000.0).toLong())
}

// the same object in two slots: the slots alias
fun aliased() {
    val a = P(1.0, 0.0, 1.0)
    val b = P(2.0, 0.0, 1.0)
    val ps = arrayOf(a, a, b)
    for (s in 0 until 10) step(ps, 0.1)
    println((a.x * 1000.0).toLong())
    println((b.x * 1000.0).toLong())
}

// the array escapes: stays an array
fun escapes() {
    val cs = arrayOf(Cnt(1), Cnt(2), Cnt(3))
    for (i in 0 until 3) cs[i].n += i
    keep.add(cs)
    println(keep[0][2].n)
}

// a slot is replaced after the construction
fun mutated() {
    val cs = arrayOf(Cnt(1), Cnt(2), Cnt(3))
    var t = 0
    for (i in 0 until 3) t += cs[i].n
    cs[1] = Cnt(10)
    for (i in 0 until 3) t += cs[i].n
    println(t)
}

// a long loop and a non-constant index
fun longLoop(k: Int) {
    val cs = arrayOf(Cnt(1), Cnt(2), Cnt(3))
    var t = 0
    for (r in 0 until 40) for (i in 0 until 3) { cs[i].n += r; t += cs[i].n }
    println(t)
    println(cs[k].n)
}

// a constant index out of bounds still throws, after the earlier effects
fun outOfBounds() {
    val cs = arrayOf(Cnt(1), Cnt(2))
    try {
        for (i in 0 until 3) { cs[i].n += 1; println(cs[i].n) }
    } catch (e: ArrayIndexOutOfBoundsException) {
        println("caught " + cs[0].n + " " + cs[1].n)
    }
}

// break / continue / early return inside the unrolled loop
fun control(): Int {
    val cs = arrayOf(Cnt(5), Cnt(6), Cnt(7), Cnt(8))
    var t = 0
    for (i in 0 until 4) {
        if (i == 1) continue
        t += cs[i].n
        if (cs[i].n == 7) break
    }
    for (i in 0 until 4) if (cs[i].n == 8) return t * 100 + i
    return t
}

// identity of an element: the objects stay objects
fun identity() {
    val a = Cnt(1)
    val b = Cnt(1)
    val cs = arrayOf(a, b)
    println(cs[0] === a)
    println(cs[1] === a)
    println(cs.size)
}

// a null slot, and a polluted (null retyped to non-null) array: the checks stay
fun nullable() {
    val a = Cnt(1)
    val cs = arrayOf<Cnt?>(a, null, Cnt(3))
    var t = 0
    for (i in 0 until 3) {
        val c = cs[i]
        if (c != null) t += c.n else t += 100
    }
    println(t)
}

@Suppress("UNCHECKED_CAST")
fun polluted() {
    val cs = arrayOfNulls<Cnt>(2) as Array<Cnt>
    try {
        println(cs[0].n)
    } catch (e: NullPointerException) {
        println("npe")
    }
}

fun main() {
    nullable()
    polluted()
    basic()
    aliased()
    escapes()
    mutated()
    longLoop(1)
    outOfBounds()
    println(control())
    identity()
}
