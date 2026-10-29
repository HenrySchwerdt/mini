class Config(val name: String = "default", val retries: Int = 3, val verbose: Boolean = false) {
    fun show() = "$name/$retries/$verbose"
}

fun greet(who: String, greeting: String = "Hello", punct: String = "!"): String = greeting + ", " + who + punct

class Outer(val id: Int) {
    class Nested(val tag: String) {
        fun twice() = tag + tag
    }

    fun nested() = Nested("n" + id)
}

class Slot {
    var number: Int? = null
    var text: String? = null
    var any: Any? = null
    var flag: Boolean? = null
}

abstract class Vehicle(val wheels: Int) {
    abstract fun name(): String
    open fun describe(): String = name() + " with " + wheels + " wheels"
}

class Car : Vehicle(4) {
    override fun name() = "car"
}

class Bike : Vehicle(2) {
    override fun name() = "bike"
    override fun describe(): String = "fast " + super.describe()
}

class Stack {
    private var items: Array3? = null
    var size = 0
    fun push(v: Int) {
        items = Array3(v, items)
        size++
    }
    fun pop(): Int? {
        val top = items ?: return null
        items = top.rest
        size--
        return top.value
    }
}

class Array3(val value: Int, val rest: Array3?)

class Pair2(val first: Any, val second: Any)

class Counter {
    var n = 0
    fun next(): Counter {
        n++
        return this
    }
}

fun maybe(flag: Boolean): Counter? = if (flag) Counter() else null

fun main() {
    println(Config().show())
    println(Config("x").show())
    println(Config(retries = 9).show())
    println(Config("y", 1, true).show())
    println(greet("Ann"))
    println(greet("Bob", "Hi"))
    println(greet("Cy", punct = "?"))
    val o = Outer(5)
    println(o.nested().twice())
    println(Outer.Nested("z").tag)

    val s = Slot()
    println(s.number)
    s.number = 41
    s.text = "hey"
    s.any = 3.5
    s.flag = true
    println(s.number!! + 1)
    println(s.text)
    println(s.any)
    println(s.flag)
    s.number = null
    println(s.number)
    s.any = s
    println(s.any === s)
    s.any = null

    val v: List3 = List3()
    println(v.items())
    val vs = arrayOf2(Car(), Bike())
    println(vs.first.describe())
    println(vs.second.describe())

    val st = Stack()
    st.push(1)
    st.push(2)
    st.push(3)
    println(st.pop())
    println(st.size)
    println(st.pop())
    println(st.pop())
    println(st.pop())
    println(st.size)

    val p = Pair2("a", 7)
    println(p.first)
    println(p.second)
    println("" + p.first + p.second)

    println(Counter().next().next().next().n)
    println(maybe(true)?.next()?.n)
    println(maybe(false)?.next()?.n)
}

class List3 {
    fun items() = "list3"
}

class VPair(val first: Vehicle, val second: Vehicle)

fun arrayOf2(a: Vehicle, b: Vehicle) = VPair(a, b)
