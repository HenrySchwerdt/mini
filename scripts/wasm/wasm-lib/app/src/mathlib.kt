import mini.wasm.WasmExport
import textutil.shout

var calls = 0
val greeting = "Hello, " + "wasm"

class Counter(var value: Int) {
    fun inc(): Int { value += 1; return value }
}

@WasmExport
fun add(a: Int, b: Int): Int { calls++; return a + b }

@WasmExport("addLong")
fun add64(a: Long, b: Long): Long = a + b

@WasmExport
fun hypot(a: Double, b: Double): Double = kotlin.math.sqrt(a * a + b * b)

@WasmExport
fun halve(x: Float): Float = x / 2.0f

@WasmExport
fun isEven(n: Int): Boolean = n % 2 == 0

@WasmExport
fun reverse(s: String): String = s.reversed()

@WasmExport
fun greet(name: String): String = greeting + ", " + name + "!"

@WasmExport
fun sum(a: IntArray): Int {
    var t = 0
    for (x in a) t += x
    return t
}

@WasmExport
fun scale(a: DoubleArray, k: Double): DoubleArray {
    val r = DoubleArray(a.size)
    for (i in a.indices) r[i] = a[i] * k
    return r
}

@WasmExport
fun squares(n: Int): IntArray = IntArray(n) { it * it }

@WasmExport
fun newCounter(start: Int): Counter = Counter(start)

@WasmExport
fun increment(c: Counter): Int = c.inc()

@WasmExport
fun callCount(): Int = calls

@WasmExport
fun log(msg: String) { println("log: " + msg) }

@WasmExport
fun fail(msg: String): Int {
    if (msg.isEmpty()) return 0
    throw IllegalStateException(msg)
}

@WasmExport
fun parse(s: String): Int = s.toInt()

@WasmExport
fun shoutIt(s: String): String = shout(s)

@WasmExport
fun words(s: String): Int = textutil.wordCount(s)
