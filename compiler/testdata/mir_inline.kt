// MIR inliner (opt/Inline.kt): small functions are expanded in place; the program must behave exactly as without it.
var trace = StringBuilder()

fun log(s: String): Int {
    trace.append(s).append(';')
    return s.length
}

class Counter(var n: Int) {
    fun inc(): Int { n++; return n }
    fun get(): Int = n
    fun set(v: Int) { n = v }
    fun addAll(vararg xs: Int): Int { var s = 0; for (x in xs) s += x; n += s; return n }
}

fun pick(c: Boolean, a: Int, b: Int): Int {
    if (c) return a
    return b
}

fun earlyReturn(x: Int): Int {
    if (x < 0) return -1
    if (x == 0) return 0
    return 1
}

fun twice(f: Int): Int = f * 2

fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)

fun mayThrow(x: Int): Int {
    if (x > 2) throw IllegalStateException("big $x")
    return x
}

fun sideA(): Int { trace.append("A"); return 1 }
fun sideB(): Int { trace.append("B"); return 2 }
fun add(a: Int, b: Int): Int = a + b

fun strLen(s: String?): Int = s?.length ?: -1

open class Base { open fun name(): String = "base" ; fun greet(): String = "hi " + name() }
class Derived : Base() { override fun name(): String = "derived" }

fun main() {
    val c = Counter(5)
    println(c.inc() + c.inc() * 10)           // evaluation order: 6 + 7 * 10
    println(c.get())
    c.set(c.get() + 100)
    println(c.n)
    println(c.addAll(1, 2, 3))

    println(pick(true, 1, 2).toString() + pick(false, 1, 2))
    println(earlyReturn(-5).toString() + earlyReturn(0) + earlyReturn(9))

    // arguments are evaluated once, in order, even when the callee ignores or repeats them
    println(add(sideA(), sideB()))
    println(trace.toString())
    trace = StringBuilder()
    println(twice(log("x")) + twice(log("yy")))
    println(trace.toString())

    println(fact(6))

    // a call in the right operand of && must stay conditional
    trace = StringBuilder()
    val r1 = false && (log("never") > 0)
    val r2 = true || (log("never2") > 0)
    val r3 = true && (log("yes") > 0)
    println("$r1 $r2 $r3 ${trace}")

    // calls in loop conditions
    var i = 0
    val cc = Counter(0)
    while (cc.inc() < 5) i += 10
    println(i)
    var j = 0
    do { j++ } while (cc.get() < 8 && cc.inc() > 0)
    println("$j ${cc.n}")

    // exceptions out of an inlined body are caught by the caller
    var total = 0
    for (k in 0 until 5) {
        try {
            total += mayThrow(k)
        } catch (e: IllegalStateException) {
            total += 100
            println(e.message)
        }
    }
    println(total)

    // nulls
    println(strLen(null).toString() + strLen("abcd"))
    // virtual dispatch through a Base-typed value
    val b: Base = Derived()
    val b2: Base = Base()
    println(b.greet() + "/" + b2.greet())
    // a local holding the exact class
    val d = Derived()
    println(d.greet())
}
