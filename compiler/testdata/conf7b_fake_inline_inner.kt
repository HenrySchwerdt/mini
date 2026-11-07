open class A {
    val v = "OK"
    open inner class AA {
        protected inline fun inAA(crossinline modifier: (String) -> String): String = modifier(v)
    }
}
class B : A() {
    inner class BB : AA() {
        fun test(): String = inAA { i -> i }
    }
}
fun main() { println(B().BB().test()) }
