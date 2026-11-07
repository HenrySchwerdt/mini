// indexed assignment through `set` with a defaulted or vararg parameter before the value; defaults that read earlier constructor parameters
class X {
    var result: String = "fail"

    operator fun get(name: String, type: Int = 100) = name + type

    operator fun set(name: String, defaultParam: String = "_default_", value: String) {
        result = name + defaultParam + value
    }
}

class V {
    var sum = 0
    operator fun get(vararg i: Int): Int = i.sum()
    operator fun set(vararg i: Int, value: Int) {
        sum = i.sum() * 10 + value
    }
}

class A(val i: Int, val j: Int = i)

data class D(val a: Int = 1, val b: String = "$a")

enum class E(one: Int, val two: Int = one) {
    FOO(42)
}

class Outer(val o: String, val oo: String = o) {
    inner class Inner(val k: String, val kk: String = k)
}

fun main() {
    val x = X()
    x["index"] = "OK"
    println(x.result)
    x["index"] += "OK"
    println(x.result)
    val v = V()
    v[1, 2, 3] = 4
    println(v.sum)
    println(A(1).j)
    println(A(1, 5).j)
    println(D().b)
    println(D().copy(a = 2).b)
    println(E.FOO.two)
    val o = Outer("O")
    println(o.oo + o.Inner("K").kk)
}
