// Round 11a: generic inner classes keep the type arguments of the outer class (`E<IC>.Inner().foo()`).

class E<T>(val x: T) {
    inner class Inner {
        inline fun foo(): T = x
        fun bar(): T = x
    }
    inner class Pair2<P>(val a: T, val b: P)
    fun make(): Inner = Inner()
}

@JvmInline
value class IC(val s: String)

class Foo<T> {
    inner class Inner<P>(val a: T, val b: P)
}

inline fun <A, B> foo(a: A, b: B, foo: Foo<A>, x: (Foo<A>, A, B) -> Foo<A>.Inner<B>): Foo<A>.Inner<B> = x(foo, a, b)

@JvmInline
value class Z<T : Int>(val x: T) {
    inner class Inner(val z: Z<T>) {
        val xx = x
    }
}

fun nullableCmp() {
    var log = ""
    fun side(): Int { log += "s"; return 123 }
    val a: String? = "abc"
    val b: String? = null
    println((a?.length != side()) .toString() + " " + (b?.length == side()) + " " + log)
}

fun main() {
    println(E(IC("OK")).Inner().foo().s)
    println(E(IC("O2")).Inner().bar().s)
    println(E(IC("O3")).make().bar().s)
    val p = E("x").Pair2<Int>("y", 3)
    println(p.a + p.b)
    val f = foo<String, String>("O", "K", Foo<String>(), Foo<String>::Inner)
    println(f.a + f.b)
    val zi = Z(42).Inner(Z(100))
    println(zi.xx.toString() + " " + zi.z.x)
    nullableCmp()
}
