open class Base(val tag: String) {
    init { println("Base init $tag") }
    open val kind: String = "base"
    constructor() : this("default") { println("Base secondary") }
    open fun hello() = "Base.hello $kind"
    init { println("Base init2") }
}

class Derived : Base("d") {
    override val kind = "derived"
    val extra = compute()
    init { println("Derived init $extra") }
    private fun compute(): Int { println("compute"); return 5 }
    override fun hello() = "Derived.hello " + super.hello()
}

class Third(x: Int) : Base() {
    val y: Int
    init { y = x * 2; println("Third y=$y") }
    constructor(a: Int, b: Int) : this(a + b) { println("Third secondary") }
}

abstract class Shape {
    init { println("Shape init ${describe()}") }
    abstract fun describe(): String
}

class Sq(val s: Int) : Shape() {
    override fun describe() = "Sq $s"
}

fun main() {
    println(Derived().hello())
    val t = Third(2, 3)
    println(t.y)
    println(Base().hello())
    println(Sq(4).describe())
}
