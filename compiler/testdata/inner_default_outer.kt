// default values of the constructors of inner classes read the outer instances; `init` blocks of doubly nested inner classes read the outermost one

val sb = StringBuilder()

class A {
    override fun toString(): String = "A"
    inner class B {
        override fun toString(): String = "B"
        inner class C(val a: A = this@A, val b: B = this@B) {
            override fun toString(): String = "C${f2()}${f3()}"
            fun f2(): String = a.toString()
            fun f3(): String = b.toString()
            fun f6(aa: A = this@A): String = aa.toString()
        }
    }
}

class Outer(val o: String, val oo: String) {
    inner class InnerArg(val i: String) {
        val result: String get() = o + i
    }

    inner class InnerParam(val i: InnerArg = InnerArg("B")) {
        fun foo() = i.result + oo
    }
}

class Outer2(val x: Any) {
    inner class Inner(val fn: () -> String = { x.toString() })
}

class Outer3(val x: Int) {
    inner class Inner() {
        inner class InnerInner() {
            init {
                sb.append("init ").append(x).append(';')
            }

            lateinit var s: String

            constructor(s: String) : this() {
                this.s = s
            }
        }
    }
}

fun main() {
    val c = A().B().C()
    println(c.toString() + " " + c.f6())
    println(Outer("A", "C").InnerParam().foo())
    println(Outer2("OK").Inner().fn())
    Outer3(42).Inner().InnerInner("zzz")
    println(sb)
}
