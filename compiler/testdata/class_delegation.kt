// Class delegation: `class C : I by expr` forwards every member of I that C does not declare itself.
interface Shape<T> {
    val name: String
    fun area(): T
    fun describe(prefix: String, times: Int = 2): String = prefix + name + "x" + times
}

class Square(val side: Int) : Shape<Int> {
    override val name = "square"
    override fun area() = side * side
}

class Wrapper(inner: Shape<Int>) : Shape<Int> by inner

class Loud(val inner: Shape<Int>) : Shape<Int> by inner {
    override fun area(): Int = inner.area() + 1000
}

interface Counter {
    var count: Int
    fun bump(): Int
}

class Impl : Counter {
    override var count = 0
    override fun bump(): Int { count += 1; return count }
}

class Delegating(c: Counter) : Counter by c

class Order(log: StringBuilder) : Shape<String> by make(log) {
    init { log.append("init;") }
}

fun make(log: StringBuilder): Shape<String> {
    log.append("make;")
    return object : Shape<String> {
        override val name = "anon"
        override fun area() = "area"
    }
}

fun main() {
    val w: Shape<Int> = Wrapper(Square(3))
    println(w.name)
    println(w.area())
    println(w.describe("a-"))
    println(w.describe("b-", 5))
    val l: Shape<Int> = Loud(Square(4))
    println(l.area())
    println(l.name)
    val d = Delegating(Impl())
    d.bump()
    d.bump()
    println(d.count)
    d.count = 10
    println(d.bump())
    val log = StringBuilder()
    val o = Order(log)
    println(log.toString())
    println(o.area())
}
