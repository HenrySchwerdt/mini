fun interface MyRunnable {
    fun run()
}

fun foo(vararg rs: MyRunnable) {
    for (r in rs) r.run()
}

fun interface Foo : () -> Int

class C3 : () -> Int {
    override fun invoke(): Int = 42
    override fun toString(): String = "OK3"
}

fun id(foo: Foo): Any = foo

open class M { open var y = 500 }
open class N : M() {
    override var y = 200
    open inner class C {
        fun test6(): Int {
            super<M>@N.y += 200
            return super<M>@N.y
        }
    }
}

fun main() {
    var result = "failed"
    val r = MyRunnable { result += "K" }
    // a lambda in a vararg of a fun interface is converted to it
    foo({ result = "O" }, r)
    println(result)
    val p1 = object : Foo {
        override fun invoke(): Int = 42
        override fun toString(): String = "OK"
    }
    val p2 = id(p1)
    println((p1 === p2).toString() + " " + p2.toString())
    // a class whose only supertype is a function type still has Any's members
    val b: Any = C3()
    println(b.toString() + " " + "$b")
    val x: Any = 4
    val u: Any = Unit
    println("${x is Unit} ${u is Unit}")
    println(N().C().test6())
    // the array of a for loop is evaluated once
    var xs = intArrayOf(1, 2, 3)
    var sum = 0
    for (v in xs) {
        sum = sum * 10 + v
        xs = intArrayOf(4, 5)
    }
    println(sum)
}
