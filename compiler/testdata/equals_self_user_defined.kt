// `a.equals(a)` / `a == a` call a user-defined equals; there is no identity shortcut.
class Never(val x: Int) {
    override fun equals(other: Any?): Boolean {
        println("equals called")
        return false
    }
    override fun hashCode() = x
}

class Plain(val x: Int)

fun main() {
    val a = Never(1)
    println(a.equals(a))
    println(a == a)
    val any: Any = a
    println(any == any)
    println(listOf(a).contains(a))
    val p = Plain(2)
    println(p.equals(p))
    println(p == Plain(2))
    println("s" == "s")
}
