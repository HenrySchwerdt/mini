// casts to Unit and to a definitely non-null type; a when subject smart cast after a negative branch is compared as a number
fun <T> test(t: T) = t as (T & Any)

fun testF(x: Any) = when (x) {
    !is Float -> "!Float"
    0.0F -> "0.0"
    else -> "other"
}

fun testD(x: Any) = when (x) {
    !is Double -> "!Double"
    0.0 -> "0.0"
    else -> "other"
}

fun main() {
    println(if (4 as? Unit != null) "Fail" else "OK")
    println(if (Unit as? Unit != null) "OK" else "Fail")
    try {
        println(4 as Unit)
    } catch (e: ClassCastException) {
        println("CCE")
    }
    println(Unit as Unit)
    try {
        test<Any?>(null)
        println("FAIL")
    } catch (e: NullPointerException) {
        println(test("OK"))
    }
    println(testF(-0.0F))
    println(testF(1.0F))
    println(testF("s"))
    println(testD(-0.0))
    println(testD(2.0))
}
