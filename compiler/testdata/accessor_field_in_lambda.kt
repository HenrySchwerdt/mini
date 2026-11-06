// `field` inside a lambda in an accessor.
fun <T> eval(fn: () -> T) = fn()

class My {
    val my: String = "O"
        get() = eval { field } + "K"
    var count: Int = 1
        get() = eval { field * 10 }
        set(v) { eval { field = v + 1 } }
}

fun main() {
    val m = My()
    println(m.my)
    m.count = 4
    println(m.count)
}
