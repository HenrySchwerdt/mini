// Implicit receivers: an inner class whose super class is an inner class of the outer class's super class,
// and member extension operators used from an extension function of the same class.
open class A() {
    open inner class InnerA {
        fun hello(): String = "InnerA of " + name()
    }
    open fun name(): String = "A"
}

class B : A() {
    override fun name(): String = "B"
    inner class InnerB : A.InnerA()
}

class C(val i: Int)

class M {
    operator fun C.component1() = i + 1
    operator fun C.component2() = i + 2
    operator fun C.get(k: Int) = i * 10 + k
}

fun M.render(l: Array<C>): String {
    var s = ""
    for ((a, b) in l) s += "$a:$b;"
    return s
}

fun M.index(c: C): Int = c[3]

interface Runnable { fun run(): Unit }

class Outer {
    private fun f() { println("f") }
    private fun String.ext(): String = "ext" + this
    fun foo() {
        object : Runnable {
            override fun run() {
                f()
                println("-".ext())
            }
        }.run()
    }
}

fun main() {
    Outer().foo()
    println(B().InnerB().hello())
    println(M().render(arrayOf(C(0), C(1), C(2))))
    println(M().index(C(4)))
}
