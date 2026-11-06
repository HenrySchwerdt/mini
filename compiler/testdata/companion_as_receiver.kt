// a class name is its companion object: extension functions of Any / the companion called on it; primitive class names as values

fun Any.foo() = 1

class A {
    companion object
}

class C {
    companion object Foo
}

fun C.Foo.create() = 3

object Single

fun Single.tag() = "single"

fun main() {
    println(A.foo())
    println(C.Foo.create())
    println(C.create())
    println(Single.tag())
    val cls: Any? = Int
    println(cls != null)
    val none: Any? = null
    println(none?.let { "x" })
}
