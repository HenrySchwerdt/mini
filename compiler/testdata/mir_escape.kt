// Scalar replacement of objects that never escape (opt/Sroa.kt): iterators, small value-like classes, and every way an object
// can escape (stored, returned, captured, compared, thrown, passed to a big function) which must keep it a real object.
class Pt(val x: Int, val y: Int) {
    fun plus(o: Pt): Pt = Pt(x + o.x, y + o.y)
    fun len2(): Int = x * x + y * y
}

data class Pair2(val a: Int, val b: String)

class Box(var v: Int)

class Holder {
    var kept: Pt? = null
}

val sink = ArrayList<Any>()

fun big(p: Pt): Int {
    // too big to inline: p escapes into a list
    sink.add(p)
    var s = 0
    for (i in 0 until 3) {
        s += p.x * i
        s += p.y * i
        if (s > 1000) s -= 1000
    }
    return s
}

fun makes(x: Int): Pt = Pt(x, x + 1)

fun throwsIt(x: Int): Int {
    val p = Pt(x, 2)
    if (x > 1) throw RuntimeException("p=" + p.x)
    return p.len2()
}

fun escapesByReturn(x: Int): Any = Pt(x, x)

fun identity(a: Pt, b: Pt): Boolean = a === b

fun main() {
    // local temporaries
    val a = Pt(3, 4)
    val b = a.plus(Pt(1, 1))
    println(b.x.toString() + "," + b.y + "," + b.len2())

    // iterators over list, over a mutated list
    val list = ArrayList<Int>()
    for (i in 1..6) list.add(i * i)
    var sum = 0
    for (x in list) sum += x
    println(sum)
    var odd = 0
    for (x in list) if (x % 2 == 1) odd++
    println(odd)

    // destructuring data class
    val (n, s) = Pair2(7, "seven")
    println("$n $s")

    // escapes: stored in a list, a field, returned, thrown around, captured by a lambda, identity
    val e1 = Pt(10, 20)
    println(big(e1))
    println(sink.size)
    val h = Holder()
    val e2 = Pt(1, 2)
    h.kept = e2
    println(h.kept!!.len2())
    println((escapesByReturn(4) as Pt).len2())
    val e3 = Pt(5, 5)
    val f = { e3.x + e3.y }
    println(f())
    val e4 = Pt(1, 1)
    println(identity(e4, e4).toString() + identity(e4, Pt(1, 1)))
    var caught = ""
    for (x in 0..2) {
        try {
            println(throwsIt(x))
        } catch (ex: RuntimeException) {
            caught += ex.message
        }
    }
    println(caught)

    // an object that is conditionally escaping
    var keep: Pt? = null
    for (i in 0 until 4) {
        val p = Pt(i, i)
        if (i == 2) keep = p
        sum += p.len2()
    }
    println("$sum ${keep!!.x}")

    // a mutable object only used locally, with mutation in a loop
    val bx = Box(0)
    for (i in 0 until 10) bx.v += i
    println(bx.v)
    val boxes = ArrayList<Box>()
    for (i in 0 until 3) boxes.add(Box(i))
    for (bb in boxes) bb.v *= 2
    println(boxes.map { it.v })
    println(makes(3).len2())
}
