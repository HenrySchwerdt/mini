// Lambdas, closures, function types and function references.

class Counter(var count: Int) {
    fun inc(): Int { count += 1; return count }
    fun add(n: Int) { count += n }
}

class Point(val x: Int, val y: Int) {
    fun sum(): Int = x + y
    fun scaled(k: Int): Int = (x + y) * k
}

fun twiceApply(f: (Int) -> Int, x: Int): Int = f(f(x))
fun callBoth(a: () -> String, b: () -> String): String = a() + "/" + b()
fun compose(f: (Int) -> Int, g: (Int) -> Int): (Int) -> Int = { x -> g(f(x)) }
fun makeCounter(): () -> Int {
    var n = 0
    return { n += 1; n }
}
fun adder(n: Int): (Int) -> Int = { it + n }
fun curry(a: Int): (Int) -> (Int) -> Int = { b -> { c -> a * 100 + b * 10 + c } }
fun double(x: Int) = x * 2
fun square(x: Int) = x * x
fun greeter(prefix: String): (String) -> String = { name -> "$prefix, $name!" }
fun runAction(action: () -> Unit) { action(); action() }
fun withReceiver(f: Counter.() -> Unit): Counter { val c = Counter(0); c.f(); return c }
fun pick(flag: Boolean, a: (Int) -> Int, b: (Int) -> Int): (Int) -> Int = if (flag) a else b
fun longArity(f: (Int, Int, Int, Int) -> Int): Int = f(1, 2, 3, 4)
fun nullableFn(f: ((Int) -> Int)?): Int = if (f == null) -1 else f(7)

inline fun inlineTwice(f: () -> Unit) { f(); f() }
inline fun <T> inlineMap(x: T, f: (T) -> T): T = f(x)
fun firstBig(a: Int, b: Int, c: Int): String {
    var i = 0
    while (i < 3) {
        val v = if (i == 0) a else if (i == 1) b else c
        run {
            if (v > 10) return "big:$v"
        }
        i += 1
    }
    return "none"
}

fun earlyReturn(n: Int): Int {
    inlineTwice {
        if (n > 5) return n * 2
    }
    return -n
}

fun main() {
    // captured var shared by two closures and the declaring function
    var total = 0
    val addTo = { x: Int -> total += x }
    val readTotal = { total }
    addTo(5)
    addTo(7)
    println(readTotal())
    total = 100
    println(readTotal())

    // closure counter, independent instances
    val c1 = makeCounter()
    val c2 = makeCounter()
    println(c1() + c1() + c1())
    println(c2())

    // returning and composing lambdas
    val plus3 = adder(3)
    println(plus3(4))
    println(compose(adder(1), ::double)(10))
    println(compose(::square, plus3)(5))
    println(curry(1)(2)(3))
    println(twiceApply({ it * 3 }, 2))
    println(twiceApply(::double, 5))
    println(callBoth({ "a" }, { "b" }))
    println(greeter("Hello")("World"))

    // Unit lambdas and receivers
    var log = ""
    runAction { log += "x" }
    println(log)
    val cnt = withReceiver { add(5); inc() }
    println(cnt.count)

    // function references: bound, unbound, constructor
    val p = Point(3, 4)
    val bound = p::sum
    println(bound())
    val unbound = Point::sum
    println(unbound(Point(10, 20)))
    val scaled = p::scaled
    println(scaled(2))
    val ctor = ::Point
    println(ctor(5, 6).sum())
    val inc = Counter(41)::inc
    println(inc())

    // captured vals, lambdas created in a loop
    val fs = ArrayHolder()
    for (i in 1..3) {
        val k = i * 10
        fs.add { k + i }
    }
    println(fs.sum())

    // selecting a function value
    println(pick(true, ::double, ::square)(6))
    println(pick(false, ::double, ::square)(6))
    println(longArity { a, b, c, d -> a + b * c - d })
    println(nullableFn(null))
    println(nullableFn { it + 1 })

    // local functions
    var calls = 0
    fun fact(n: Int): Int {
        calls += 1
        return if (n <= 1) 1 else n * fact(n - 1)
    }
    println(fact(5))
    println(calls)
    val factRef = ::fact
    println(factRef(4))

    // inline functions
    var acc = 0
    inlineTwice { acc += 3 }
    println(acc)
    println(inlineMap(21) { it * 2 })
    println(inlineMap("ab") { it + it })
    println(firstBig(1, 20, 30))
    println(firstBig(1, 2, 3))
    println(earlyReturn(9))
    println(earlyReturn(2))

    // scope functions
    println(5.let { it + 1 })
    val s = "abc".also { println("also " + it) }
    println(s)
    println(Counter(1).apply { add(10) }.count)
    println(with(Counter(2)) { inc() })
    println(run { 1 + 2 })
}

class ArrayHolder {
    var a: (() -> Int)? = null
    var b: (() -> Int)? = null
    var c: (() -> Int)? = null
    var n = 0
    fun add(f: () -> Int) {
        if (n == 0) a = f else if (n == 1) b = f else c = f
        n += 1
    }
    fun sum(): Int {
        val fa = a
        val fb = b
        val fc = c
        var s = 0
        if (fa != null) s += fa()
        if (fb != null) s += fb()
        if (fc != null) s += fc()
        return s
    }
}
