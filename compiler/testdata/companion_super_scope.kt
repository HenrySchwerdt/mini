// the companion objects of the superclasses are in the scope of a class body (members and properties), also from nested classes and lambdas

open class A {
    companion object {
        fun getO() = "O"
        val vo = "o"
    }
}

open class B : A() {
    companion object {
        fun getK() = "K"
        val vk = "k"
    }
}

class C : B() {
    val viaLambda = { getO() + getK() }
    fun both() = getO() + getK() + vo + vk
    inline fun <T> run(f: () -> T) = f()
    fun inline() = run { getO() + vo }

    class Nested {
        fun foo() = getK() + vk
    }
}

fun main() {
    val c = C()
    println(c.viaLambda())
    println(c.both())
    println(c.inline())
    println(C.Nested().foo())
}
