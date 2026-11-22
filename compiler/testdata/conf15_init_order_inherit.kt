// superclass constructor calling an open member sees the subclass's uninitialized primitive fields (reading an uninitialized reference field
// gives null on the JVM; mini rejects that, see docs/conformance.md)
open class Base {
    init { println("Base init"); show() }
    open fun show() { println("Base.show") }
    val tag = "base".also { println("Base tag init") }
}
class Derived(val n: Int) : Base() {
    val s = "derived"
    init { println("Derived init n=$n s=$s") }
    override fun show() { println("Derived.show n=$n len=${n + 1}") }
}
open class P1(open val v: Int) { init { println("P1 v=" + v) } }
class C1 : P1(5) { override val v: Int = 9; init { println("C1 v=" + v) } }
abstract class Shape { abstract val name: String; init { println("Shape init") }; fun hello() = "I am " + name }
class Circle : Shape() { override val name = "circle" }
open class First(x: Int) { init { println("First($x)") } }
open class Second(x: Int, y: Int) : First(x + y) { init { println("Second($x,$y)") } }
class Third : Second(1, 2) { init { println("Third") } }
fun main() {
    Derived(5); println("--"); C1(); println("--"); println(Circle().hello()); println("--"); Third()
}
