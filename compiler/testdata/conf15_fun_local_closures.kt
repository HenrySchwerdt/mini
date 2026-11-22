// local functions, closures capturing vars, recursion in local functions, function returning functions with state
fun counter(): () -> Int { var c = 0; return { ++c } }
fun fibMemo(n: Int): Long { val memo = HashMap<Int, Long>(); fun fib(k: Int): Long { if (k < 2) return k.toLong(); return memo.getOrPut(k) { fib(k - 1) + fib(k - 2) } }; return fib(n) }
fun outer(x: Int): Int { var total = x; fun add(y: Int) { total += y }; add(1); add(2); fun dbl() = total * 2
    return dbl() }
fun makeAccumulators(): List<() -> Int> { val res = ArrayList<() -> Int>(); for (i in 1..3) { var v = i; res.add { v += i; v } }; return res }
fun apply3(f: (Int) -> Int) = f(f(f(1)))
fun main() {
    val c1 = counter(); val c2 = counter(); println(c1()); println(c1()); println(c2()); println(c1())
    println(fibMemo(50)); println(outer(5))
    val accs = makeAccumulators(); println(accs.map { it() }); println(accs.map { it() })
    var x = 10; val read = { x }; x = 20; println(read()); val w = { x = 30 }; w(); println(x)
    println(apply3 { it * 2 }); println(apply3 { it + x })
    fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)
    println(fact(6))
    val list = mutableListOf<Int>(); fun push(v: Int) = list.add(v)
    push(1); push(2); println(list)
    fun <T> tw(t: T): List<T> = listOf(t, t)
    println(tw("a")); println(tw(1))
    val fns = (1..3).map { i -> { j: Int -> i * j } }; println(fns.map { it(10) })
    var s = ""; (1..3).forEach { s += it; }; println(s); val fx = fun() { s += "!" }; fx(); fx(); println(s)
    class Local(val n: Int) { fun twice() = n * 2 }; println(Local(4).twice()); println(object { val z = 3; fun f() = z + 1 }.f())
    val anon = object : Comparator<String> { override fun compare(a: String, b: String) = b.compareTo(a) }; println(listOf("a", "c", "b").sortedWith(anon))
}
