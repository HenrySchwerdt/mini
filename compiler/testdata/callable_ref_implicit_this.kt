object Util {
    fun inc(i: Int) = i + 1
    fun all() = listOf(1, 2).map(::inc)
}

open class Base {
    protected fun tw(i: Int) = i * 2
}

class T : Base() {
    private fun dbl(i: Int): Int = i * 2
    val k = 10
    fun add(i: Int) = i + k
    fun a() = listOf(1, 2).map(::dbl)
    fun b(): List<Int> { return listOf(1, 2).map(::dbl) }
    fun c(from: Int = 0, to: Int = 2) = (from..to).map(::dbl)
    fun d() = listOf(1, 2).map(::add)
    fun e() = listOf(1, 2).map(::tw)
    fun f() = listOf(1, 2).map(::cm)
    fun g(): () -> Int = ::size
    val size get() = k + 1

    companion object {
        fun cm(i: Int) = i - 1
    }
}

fun main() {
    val t = T()
    println(t.a())
    println(t.b())
    println(t.c())
    println(t.d())
    println(t.e())
    println(t.f())
    println(t.g()())
    println(Util.all())
}
