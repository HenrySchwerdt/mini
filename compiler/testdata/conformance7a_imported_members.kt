// members an object inherits, imported by name; vararg parameters of unsigned types are unsigned arrays

import Reg.fromInterface
import Reg.fromClass
import Reg.genericFromSuper
import Reg.own

interface I<G> {
    fun <T> T.fromInterface(): T = this

    fun genericFromSuper(g: G) = g
}

open class BaseClass {
    val <T> T.fromClass: T
        get() = this
}

object Reg : BaseClass(), I<String> {
    fun own(s: Int) = s + 1
}

fun uint(vararg us: UInt): UIntArray = us

fun sum(vararg us: UInt): UInt {
    var sum = 0u
    for (i in us) sum += i
    return sum
}

fun main() {
    println(9.fromInterface())
    println("10".fromClass)
    println(genericFromSuper("11"))
    println(own(1))
    val uints = uint(1u, 2u, 3u)
    println(sum(*uints))
    println(sum(4u, *uints, 5u, *uints, 6u))
    println(sum())
    println(uints is UIntArray)
}
