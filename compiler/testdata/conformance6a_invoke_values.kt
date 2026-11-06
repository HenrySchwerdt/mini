// invoke on values named by the callee: enum entries (qualified and imported), objects behind a type alias, extension properties,
// member extension invoke, and member extensions applied to implicit receivers
import A.TWO

enum class A {
    ONE,
    TWO;

    operator fun invoke(i: Int) = i + ordinal
}

object O {
    val x = "obj"
    operator fun invoke() = x
}

typealias OA = O

val Int.ext: () -> Int get() = { 5 }
val Long.ext: Long get() = 4.ext().toLong()

class P {
    operator fun invoke(f: P.() -> Unit) = 1
}

class Q {
    operator fun invoke(f: Q.() -> Unit) = 2
}

open class C
val C.attr: P get() = P()

open class D : C()
val D.attr: Q get() = Q()

class Rx
class B {
    operator fun Rx.invoke() = "##"
    operator fun Rx.invoke(i: Int) = "#$i"
}

fun mkRx() = Rx()

fun B.test(): String = mkRx()() + mkRx()(7) + (mkRx())(8)

class NoiseMaker {
    fun say(s: String) { println(s) }
}

fun noiseMaker(f: NoiseMaker.() -> Unit) = NoiseMaker().f()

class Doggy {
    fun <T> NoiseMaker.playWith(friend: T) { say("playing with " + friend) }
    fun play() = noiseMaker { playWith("owner") }
}

class Bob {
    fun Bob.bar() = "bar"
}

fun Any.foo() = when (this) {
    is Bob -> bar()
    else -> "other"
}

fun main() {
    println(A.ONE(40))
    println(TWO(40))
    println(OA())
    println(5L.ext)
    println(D().attr {})
    println(B().test())
    Doggy().play()
    println(Bob().foo())
}
