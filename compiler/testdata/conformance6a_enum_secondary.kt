// enum classes with secondary constructors (with and without a primary one)
enum class A1(val prop1: String) {
    X("asd"),
    Y() {
        override fun f() = super.f() + "#Y"
    },
    Z(5);

    val prop2: String = "const2"
    var prop3: String = ""

    constructor() : this("default") {
        prop3 = "empty"
    }

    constructor(x: Int) : this(x.toString()) {
        prop3 = "int"
    }

    open fun f(): String = "$prop1#$prop2#$prop3"
}

enum class Test {
    A(0),
    B;

    val n: Int

    constructor(n: Int) {
        this.n = n
    }

    constructor() : this(0)
}

fun main() {
    println(Test.A.n)
    println(Test.B.name)
    println(Test.B.ordinal)
    println(A1.X.f())
    println(A1.Y.f())
    println(A1.Z.f())
    println(A1.Z.name)
}
