// References to members of an object / companion are bound to it; `E::valueOf` and `E::values` of an enum class;
// a lambda in an `init` block of a class that only has secondary constructors.
class C {
    companion object {
        fun ok() = "OK"
        val tag = "T"
    }
}

object Single {
    fun hi(x: Int) = "hi" + x
}

enum class E { ENTRY, OTHER }

open class Base {
    fun test(): String = "base"
}

class Example : Base {
    constructor() : super()

    private var obj: Base? = this
    var result: String? = null

    init {
        val lambda = { result = obj?.test() + "!" }
        lambda()
    }
}

fun main() {
    val h = C.Companion::ok
    println(h())
    println((C.Companion::ok).let { it() })
    val s = Single::hi
    println(s(3))
    println(C.Companion::tag.get())
    val g = E::valueOf
    println(g("OTHER"))
    val vs = E::values
    println(vs().size)
    println(Example().result)
}
