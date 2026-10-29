// Extension functions and properties, top-level properties, lateinit, Any().

val answer = 40 + 2
var counter = 0
val greeting: String = "hello " + answer
val numbers = intArrayOf(3, 1, 2)
var lastLog = ""

fun log(s: String) { lastLog = s; counter += 1 }

fun Int.double(): Int = this * 2
fun Int.isEven(): Boolean = this % 2 == 0
fun String.shout(): String = this + "!"
fun String?.orDash(): String = this ?: "-"
fun <T> T.describe(): String = "<" + this.toString() + ">"
fun Boolean.toInt(): Int = if (this) 1 else 0
fun Point.manhattan(): Int = (if (x < 0) -x else x) + (if (y < 0) -y else y)
fun Point.shifted(dx: Int, dy: Int): Point = Point(x + dx, y + dy)

val Int.squared: Int get() = this * this
val String.firstOrSpace: Char get() = if (this.length == 0) ' ' else this[0]
val Point.sum: Int get() = x + y

class Point(val x: Int, val y: Int) {
    override fun toString(): String = "(" + x + ", " + y + ")"
    // member extension
    fun Int.plusX(): Int = this + x
    fun bump(v: Int): Int = v.plusX()
    val Int.timesY: Int get() = this * y
    fun scaled(v: Int): Int = v.timesY
}

open class Animal(val name: String) {
    open fun sound(): String = "..."
}
fun Animal.intro(): String = name + " says " + sound()
class Dog(name: String) : Animal(name) {
    override fun sound(): String = "woof"
}

class Config {
    lateinit var path: String
    lateinit var owner: Point
    fun isReady(): Boolean = this::path.isInitialized
}

lateinit var globalName: String

fun withDefault(a: Int, b: Int = a * 10, c: String = "c"): String = "" + a + ":" + b + ":" + c

fun main() {
    println(answer)
    println(greeting)
    log("first")
    log("second")
    println(counter.toString() + " " + lastLog)
    counter = 100
    println(counter)
    println(numbers.size)

    println(21.double())
    println(7.isEven())
    println(8.isEven())
    println("hey".shout())
    println("a".shout().shout())
    val none: String? = null
    println(none.orDash())
    println("some".orDash())
    println(5.describe())
    println("x".describe())
    println(true.toInt() + false.toInt())
    println(7.squared)
    println(5.double().squared)
    println("hello".firstOrSpace)
    println("".firstOrSpace == ' ')

    val p = Point(-3, 4)
    println(p.manhattan())
    println(p.shifted(1, 1))
    println(p.shifted(1, 1).sum)
    println(p.bump(10))
    println(p.scaled(5))
    println(Dog("Rex").intro())
    println(Animal("Cat").intro())

    // lateinit
    val c = Config()
    println(c.isReady())
    try {
        println(c.path)
    } catch (e: UninitializedPropertyAccessException) {
        println(e.message)
    }
    c.path = "/tmp/x"
    println(c.isReady())
    println(c.path)
    try {
        println(c.owner)
    } catch (e: UninitializedPropertyAccessException) {
        println(e.message)
    }
    c.owner = Point(1, 2)
    println(c.owner)
    try {
        println(globalName)
    } catch (e: UninitializedPropertyAccessException) {
        println(e.message)
    }
    globalName = "g"
    println(globalName)
    lateinit var local: String
    try {
        println(local)
    } catch (e: UninitializedPropertyAccessException) {
        println(e.message)
    }
    local = "l"
    println(local)

    // Any()
    val a1 = Any()
    val a2 = Any()
    println(a1 == a1)
    println(a1 == a2)
    println(a1.equals(a2))
    println(a1.hashCode() == a1.hashCode())

    println(withDefault(1))
    println(withDefault(2, 3))
    println(withDefault(4, 5, "z"))
}
