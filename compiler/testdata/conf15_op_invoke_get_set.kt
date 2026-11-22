// invoke, get/set with several arguments, compound assignment on indexed access
class Matrix(val w: Int, val h: Int) {
    private val d = IntArray(w * h)
    operator fun get(r: Int, c: Int) = d[r * w + c]
    operator fun set(r: Int, c: Int, v: Int) { d[r * w + c] = v }
    operator fun get(r: Int) = (0 until w).map { d[r * w + it] }
}
class Adder(val n: Int) { operator fun invoke(x: Int) = x + n; operator fun invoke(a: String, b: String) = a + n + b }
class Registry { private val m = HashMap<String, Int>(); operator fun get(k: String) = m[k] ?: 0; operator fun set(k: String, v: Int) { m[k] = v } }
class Fn { operator fun invoke(f: () -> String) = "called " + f() }
fun main() {
    val m = Matrix(3, 2)
    m[0, 1] = 5; m[1, 2] = 7; m[1, 2] += 3; m[0, 1]++
    println(m[0, 1]); println(m[1, 2]); println(m[1]); println(m[0])
    val add = Adder(10); println(add(5)); println(add("<", ">")); println(listOf(1, 2).map { add(it) })
    val r = Registry(); r["a"] = 1; r["a"] += 5; r["b"]--; println(r["a"]); println(r["b"]); println(r["zz"])
    val f = Fn(); println(f { "lambda" })
    val g: (Int) -> Int = Adder(1)::invoke; println(g(41))
    val arr = IntArray(3); arr[1] += 2; arr[1] *= 4; arr[2] = arr[1]--; println(arr.toList())
    val l = mutableListOf(1, 2, 3); l[0] += 10; l[l.size - 1] = l[1] * 2; println(l)
    val sb = StringBuilder("abc"); sb[1] = 'X'; println(sb)
    val mm = mutableMapOf("k" to 1); mm["k"] = mm["k"]!! + 1; mm["n"] = 0; println(mm)
}
