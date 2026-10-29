inline fun <reified T> nameOf(): String = T::class.simpleName ?: "?"

inline fun <reified T> qualified(): String = T::class.qualifiedName ?: "?"

inline fun <reified T> isA(x: Any?): Boolean = x is T

inline fun <reified T> castOrNull(x: Any?): T? = if (x is T) x else null

inline fun <reified T> cast(x: Any?): T = x as T

inline fun <reified T> describe(x: Any?): String =
    if (x is T) "is a ${T::class.simpleName}" else "is not a ${T::class.simpleName}"

inline fun <reified T> sameClass(a: Any, b: T): Boolean = a::class == T::class

inline fun <reified T> countOf(items: List<Any?>): Int {
    var n = 0
    for (i in items) if (i is T) n++
    return n
}

class Box(val v: Int)

fun main() {
    println(nameOf<Int>())
    println(nameOf<String>())
    println(nameOf<Box>())
    println(qualified<Box>())
    println(isA<String>("x"))
    println(isA<String>(1))
    println(castOrNull<Int>(5))
    println(castOrNull<Int>("no"))
    println(cast<String>("yes"))
    println(describe<Int>(3))
    println(describe<String>(3))
    println(describe<Box>(Box(1)))
    println(sameClass("a", "b"))
    println(sameClass(1, "b"))
    println(countOf<String>(listOf("a", 1, "b", null, 2.5)))
}
