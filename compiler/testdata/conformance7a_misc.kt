// data objects, String(), companion properties that hide instance ones, enum classes implementing interface properties with name / ordinal,
// extension delegates with equal names, inline members of inner classes, a property that shadows a local function, a smart cast of null

data object DataObject {
    data object Inner
}

class Test(val prop: String) {
    companion object {
        val prop: String = "CO"
    }
}

open class Bar<T>(val prop: String)

class Foo {
    companion object : Bar<Foo>("inherited") {
        val p2 = prop
    }
}

open class A(val a: String = DEFAULT_A) {
    companion object : A() {
        const val DEFAULT_A = "O"
    }
}

interface Ordinaled {
    val ordinal: Int
}

interface Named {
    val name: String
}

enum class E : Ordinaled, Named {
    X, Y
}

enum class WithEntries {
    OK, NOPE;

    companion object {
        val first = entries[0]
    }
}

open class C

object O : C()

object K : C()

class D(val value: String) {
    operator fun getValue(thisRef: C, property: Any): String = value
}

val O.prop by D("O")
val K.prop by D("K")

class Outer(val x: String) {
    inner class Inner {
        inline fun foo(y: String) = x + y
    }
}

interface Base {
    fun foo(o: String): String = o + "K"
}

interface Mid<T> : Base

class Impl : Mid<Int> {
    override fun foo(o: String): String = super<Mid>.foo(o)
}

fun nullCheck(): Int {
    var b: String? = "abc"
    b = null
    var x = 0
    if (b != null) {
        x += b.length
    }
    return x
}

fun main() {
    println(DataObject.toString())
    println(DataObject.Inner.toString())
    println(DataObject.hashCode() == DataObject.hashCode())
    println(DataObject.hashCode() != DataObject.Inner.hashCode())
    println(DataObject.hashCode() != 0)
    println(DataObject == DataObject)
    println("[" + String() + "]")
    println(Test("x").prop + Test.prop)
    println(Foo.prop + Foo.Companion.p2)
    println(A.a)
    println((E.Y as Ordinaled).ordinal)
    println((E.Y as Named).name)
    println(WithEntries.first)
    println(O.prop + K.prop)
    println(Outer("O").Inner().foo("K"))
    println(Impl().foo("O"))
    println(nullCheck())
    val o = "O"
    fun ok() = o + "K"
    class OK {
        val ok = ok()
    }
    println(OK().ok)
}
