// A class that inherits an interface default through a superclass and a more specific interface override: the override wins.
interface A { fun f(): String = "Fail"; val p: String get() = "Fail" }
interface B : A
abstract class AImpl : A
abstract class BImpl : AImpl(), B
interface C : B { override fun f(): String = "OK"; override val p: String get() = "OK-p" }
object CImpl : BImpl(), C
fun main() { println(CImpl.f()); println(CImpl.p) }
