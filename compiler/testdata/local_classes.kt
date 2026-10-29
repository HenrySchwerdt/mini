// Inner, local and anonymous classes; objects capturing variables of the enclosing function.

interface Greeter { fun greet(): String }
interface Counter { fun next(): Int }
abstract class Shape(val name: String) {
    abstract fun area(): Int
    fun describe(): String = name + ":" + area()
}

class Outer(val name: String) {
    var count = 0
    inner class Inner(val tag: String) {
        fun describe(): String { count += 1; return name + "/" + tag + "/" + count }
        inner class Deep {
            fun path(): String = name + ">" + tag + ">" + count
        }
        fun deep(): Deep = Deep()
    }
    fun make(tag: String): Inner = Inner(tag)
    fun greeter(): Greeter = object : Greeter {
        override fun greet(): String = "greeting from " + name + " #" + count
    }
}

fun counterFrom(start: Int, step: Int): Counter {
    var current = start
    return object : Counter {
        override fun next(): Int {
            val r = current
            current += step
            return r
        }
    }
}

fun makeShape(w: Int, h: Int): Shape = object : Shape("rect") {
    override fun area(): Int = w * h
}

fun localClassDemo(): String {
    val base = "hi"
    var n = 0
    class Local(val x: Int) : Greeter {
        override fun greet(): String { n += 1; return base + x + n }
    }
    val a = Local(3)
    val b = Local(4)
    val first = a.greet()
    val second = b.greet()
    return first + " " + second + " n=" + n
}

fun nestedCapture(k: Int): Int {
    var total = 0
    class Adder(val inc: Int) {
        fun add(): Int { total += inc * k; return total }
        fun addTwice(): Int {
            val f = { add() + add() }
            return f()
        }
    }
    val ad = Adder(2)
    ad.add()
    ad.addTwice()
    return total
}

class Registry {
    private val items = ArrayList2()
    fun register(g: Greeter) { items.add(g) }
    fun all(): String {
        var s = ""
        for (i in 0 until items.size) s += items.get(i).greet() + ";"
        return s
    }
}

class ArrayList2 {
    var data: Array<Greeter?> = arrayOfNulls(4)
    var size = 0
    fun add(g: Greeter) { data[size] = g; size += 1 }
    fun get(i: Int): Greeter = data[i]!!
}

fun main() {
    val o = Outer("out")
    val i = o.make("a")
    println(i.describe())
    println(o.Inner("b").describe())
    println(i.deep().path())
    println(o.greeter().greet())

    val c1 = counterFrom(10, 5)
    val c2 = counterFrom(0, 1)
    println(c1.next() + c1.next() + c1.next())
    println(c2.next())
    println(c2.next())

    println(makeShape(3, 4).describe())
    println(localClassDemo())
    println(nestedCapture(3))

    var calls = 0
    val reg = Registry()
    reg.register(object : Greeter { override fun greet(): String { calls += 1; return "one" + calls } })
    reg.register(object : Greeter { override fun greet(): String = "two" })
    println(reg.all())
    println(reg.all())
    println(calls)

    val label = "L"
    val anon = object {
        val v = 5
        fun show(): String = label + v
    }
    println(anon.show())
    println(anon.v + 1)
}
