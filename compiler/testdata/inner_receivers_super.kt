// implicit receivers of inner classes: the innermost one that fits wins; qualified `super` picks the receiver of the labelled class

interface BK {
    fun foo(): String
    fun bar(): String
}

interface K : BK {
    override fun foo() = bar()
}

class A : K {
    override fun foo() = "A.foo"
    override fun bar() = "A.bar"

    inner class B : K {
        override fun foo() = "B.foo"
        override fun bar() = "B.bar"

        fun test1() = super<K>@A.foo()
        fun test2() = super<K>@B.foo()
        fun test3() = super<K>.foo()
        fun test4() = super@A.foo()
        fun test5() = super@B.foo()
        fun test6() = super.foo()
    }
}

open class Father(val param: String) {
    abstract inner class InClass {
        fun work(): String = param
    }

    inner class Child(p: String) : Father(p) {
        inner class Child2 : Father.InClass {
            constructor() : super()
        }

        fun test(): InClass {
            class Local : Father.InClass()
            return Local()
        }
    }
}

fun main() {
    val b = A().B()
    println(listOf(b.test1(), b.test2(), b.test3(), b.test4(), b.test5(), b.test6()))
    println(Father("fail").Child("OK").Child2().work())
    println(Father("fail").Child("OK2").test().work())
}
