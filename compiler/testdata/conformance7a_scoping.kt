// the condition of do-while sees the variables of the body; local function defaults read the enclosing class; inner classes of local classes;
// `is Unit`; a Nothing parameter overriding a generic one; a super call in the delegation of an object literal

class Test<T : Char>(val k: T) {
    fun test(): String {
        fun nested(x: T = k): String = "O$x"
        return nested()
    }
}

class A(val x: String) {
    fun value(): String = object {
        inner class Y {
            val y = x
        }

        fun value() = Y().y
    }.value()

    val a = 1
    fun calc(): Int {
        class B {
            val b = 2
            inner class C {
                val c = 3
                fun calc() = this@A.a + this@B.b + this.c
            }
        }
        return B().C().calc()
    }
}

data class U(val x: Unit)

interface I<T> {
    fun foo(p: T)
}

class C : I<Nothing> {
    override fun foo(p: Nothing) {
        println(p.toString())
    }
}

interface Face {
    fun foo(): String
}

class FaceImpl(val z: String) : Face {
    override fun foo(): String = z
}

open class Fabric {
    open fun createFace(): Face = FaceImpl("super")
}

class WrapperFabric : Fabric() {
    override fun createFace(): Face = FaceImpl("own")
    fun createMyFace(): Face = object : Face by super.createFace() {}
}

fun main() {
    var x = 0
    do {
        x++
        var y = x + 5
    } while (y < 10)
    println(x)
    var b = false
    do {
        var s = "X"
        var t = "Y"
        b = true
    } while (s + t != "XY")
    println(b)
    println(Test('K').test())
    println(A("inner").value())
    println(A("z").calc())
    println(U(Unit).component1() is Unit)
    val any: Any? = null
    println(any is Unit)
    println(Unit is Unit)
    try {
        (C() as I<String>).foo("zzz")
        println("no exception")
    } catch (e: ClassCastException) {
        println("CCE")
    }
    println(WrapperFabric().createMyFace().foo())
}
