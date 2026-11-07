// a property initializer that stores the default value does not run after the superclass constructor
open class Base {
    open fun setup() {}
    init { setup() }
}

val placeHolder = Any()

class Derived : Base {
    constructor() : super()
    override fun setup() {
        xBool = true
        xInt = 4
        xLong = 5L
        xDouble = 7.0
        xRef = placeHolder
        xStr = "s"
    }
    var xBool = false
    var xInt = 0
    var xLong = 0L
    var xDouble = 0.0
    var xRef: Any? = null
    var xStr: String? = null
    var xOther = 1
}

fun main() {
    val d = Derived()
    println(d.xBool)
    println(d.xInt)
    println(d.xLong)
    println(d.xDouble)
    println(d.xRef === placeHolder)
    println(d.xStr)
    println(d.xOther)
}
