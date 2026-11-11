import vault.*

fun main() {
    val v = Vault("swordfish")
    println(v.hint())
    println(v.open(1))
    // the library keeps these to itself: the app must not compile
    println(backdoor(v))
    println(v.opened)
    println(Hidden(3).x)
    println(v.mask("zz"))
}
