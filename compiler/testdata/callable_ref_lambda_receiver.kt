class A(val k: Int) {
    fun foo(x: Int) = x + k
    val name = "A$k"
    fun twice(s: String) = s + s
    inner class In(val q: Int) { fun bar(x: Int) = x + q + k }
}

class Outer {
    val v = 100
    fun inc(x: Int) = x + v
    fun test(): List<Int> {
        val a = A(1)
        return with(a) {
            listOf(1, 2).map(::foo)
        }
    }
    fun test2(): List<Int> = with(A(2)) { listOf(1, 2).map(::inc) }
    fun test3(): List<String> = A(3).run { listOf("a").map(::twice) }
}

fun main() {
    with(A(10)) {
        println(listOf(1, 2).map(::foo))
        val f = ::foo
        println(f(5))
        val p = ::name
        println(p())
        println(listOf("q").map(::twice))
        val g = { listOf(3).map(::foo) }
        println(g())
    }
    A(3).apply {
        println(listOf("a", "b").map(::twice))
    }
    val sb = StringBuilder()
    with(sb) {
        listOf("x", "y").forEach(::append)
    }
    println(sb)
    println(Outer().test())
    println(Outer().test2())
    println(Outer().test3())
    with(A(1)) {
        val i = In(5)
        println(i.bar(1))
        with(i) { println(listOf(1).map(::bar)) }
    }
}
