// A non-reified type parameter of an inline function stays erased in the expansion: `x as T` is unchecked,
// and a reified `is T` with a nullable type argument accepts null.
inline fun <T> asT(x: Any?): String {
    val y: T = x as T
    return y as String
}

fun <T> bar(): T = "OK" as T

inline fun <T> viaBar(): String = bar<T>()!!.toString()

inline fun <reified T> Any?.isT() = this is T
inline fun <reified T> Any?.isNullableT() = this is T?
inline fun <reified T> Any?.isTChain() = isT<T>()

class A
class B

fun main() {
    println(asT<Int>("OK"))
    println(viaBar<Int>())
    println(null.isT<Any>())
    println(null.isT<Any?>())
    println(null.isT<A>())
    println(null.isT<A?>())
    println(A().isT<A?>())
    println(B().isT<A?>())
    println(null.isNullableT<A>())
    println(B().isNullableT<A>())
    println(null.isTChain<A?>())
    println(null.isTChain<A>())
}
