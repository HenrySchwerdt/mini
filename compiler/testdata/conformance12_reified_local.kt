inline fun <reified T> test(x: Any): Boolean {
    val x = object {
        val y = x is T
    }

    return x.y
}

inline fun <reified T> names(x: Any): String {
    class Local(val v: Any) {
        fun describe(): String = if (v is T) "yes" else "no"
    }
    return Local(x).describe()
}

fun main() {
    println(test<String>("OK"))
    println(test<Int>("OK"))
    println(test<String>("again"))
    println(names<Int>(3) + names<String>(3) + names<Int>("s") + names<String>("s"))
}
