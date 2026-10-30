// ++ and -- on an indexed element: a[i]++, --a[i], with the value of the expression, on arrays and on a class with get/set.
class Counter {
    val c = IntArray(3)
    var reads = 0
    operator fun get(i: Int): Int { reads++; return c[i] }
    operator fun set(i: Int, v: Int) { c[i] = v }
}

fun next(box: IntArray): Int {
    box[0]++
    return box[0] - 1
}

fun main() {
    val a = IntArray(3)
    a[1]++
    a[1]++
    --a[2]
    println(a[0].toString() + " " + a[1] + " " + a[2])
    val old = a[1]++
    val nw = ++a[1]
    println("$old $nw ${a[1]}")
    val d = DoubleArray(2)
    d[1]++
    d[1]++
    println(d[1])
    val k = Counter()
    k[2]++
    ++k[2]
    k[0]--
    println("${k.c[0]} ${k.c[2]} reads=${k.reads}")
    // the index expression is evaluated once
    val box = IntArray(1)
    val idx = IntArray(2)
    idx[next(box)]++
    idx[next(box)]++
    println("${idx[0]} ${idx[1]} ${box[0]}")
}
