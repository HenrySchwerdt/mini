// Round 11a: an inline call in an enum entry argument, `field` as an ordinary name in a type-less getter, unqualified `super`
// that picks the implementing supertype, IEEE comparison of smart-cast numbers of different types, an inherited inline member
// with a lambda in a private class.

enum class Test(val x: String, val closure1: () -> String) {
    FOO("O", run { { "K" } });
}

class Outer {
    inner class Inner {
        val field: Any

        constructor(field: Any) {
            this.field = field
        }

        val property get() = field
    }
    fun f() = Inner("inner").property as String
}

interface A0 {
    val size: Int get() = 56
}

class B0 : Collection<String>, A0 {
    override fun isEmpty() = throw UnsupportedOperationException()
    override fun contains(o: String) = throw UnsupportedOperationException()
    override fun iterator() = throw UnsupportedOperationException()
    override fun containsAll(c: Collection<String>) = throw UnsupportedOperationException()
    override val size: Int
        get() = super.size
}

fun eqDI(x: Any?, y: Any?) = x is Double? && y is Int? && x == y
fun eqLF(x: Any?, y: Any?) = x is Long? && y is Float? && x == y

class Holder {
    private abstract class Base {
        protected fun duplicate(s: String) = s + "K"
        protected inline fun doInline(block: () -> String): String = duplicate(block())
    }

    private class Extender : Base() {
        fun doSomething(): String = doInline { "O" }
    }

    fun run(): String = Extender().doSomething()
}

fun main() {
    println(Test.FOO.x + Test.FOO.closure1())
    println(Outer().f())
    println(B0().size)
    println("" + eqDI(null, null) + eqDI(-0.0, 0) + eqDI(0.0, 1) + eqDI(null, 0) + eqLF(0L, -0.0F))
    println(Holder().run())
}
