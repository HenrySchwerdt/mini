// an inline member inherited through a fake override keeps its reified type argument; a null result of a function value
// handed to an unused generic parameter is not unboxed; @LowPriorityInOverloadResolution; the delegate of an object literal
// sees the enclosing receiver
open class A {
    inline fun <reified T : Any> baz(): String = T::class.simpleName!!
}

class B : A() {
    fun bar(): String = baz<OK>()
}

class OK

inline fun <T> takeT(t: T) {}
inline fun <T : Any> takeTSuperAny(t: T) {}

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.LowPriorityInOverloadResolution
fun foo(i: Int) = 1

fun foo(a: Any) = 2

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.LowPriorityInOverloadResolution
fun bar(a: String?) = 3

fun bar(a: Any) = 4

interface Service {
    fun send(message: String): String
}

fun Service.decorate(d: (Service) -> Service): Service = object : Service by d(this) {}

fun interface FunInterface {
    fun invoke()
}

private fun id(f: FunInterface): Any = f

fun target1() {}
fun target2() {}
fun adapted1(s: String? = null): String = s!!

fun main() {
    println(B().bar())
    println(B().baz<OK>())

    val f = { null } as () -> Int
    takeT(f())
    takeTSuperAny(f())

    println(foo(1))
    println(bar(null))

    val s = object : Service {
        override fun send(message: String): String = message
    }
    val t = s.decorate { svc -> object : Service { override fun send(message: String) = svc.send(message + "K") } }
    println(t.send("O"))

    println(id(::target1) == id(::target1))
    println(id(::target1) == id(::target2))
    println(id(::adapted1) == id(::adapted1))
}
