// reified type parameters with bounds
interface Shape { fun area(): Int }
class Sq(val s: Int) : Shape { override fun area() = s * s }
class Rect(val w: Int, val h: Int) : Shape { override fun area() = w * h }
inline fun <reified T : Shape> List<Shape>.sumOf2(): Int { var t = 0; for (x in this) if (x is T) t += x.area(); return t }
inline fun <reified T> Any?.castOrNull(): T? = this as? T
inline fun <reified T : Number> parse(s: String): T? = when (T::class) {
    Int::class -> s.toIntOrNull() as T?
    Long::class -> s.toLongOrNull() as T?
    Double::class -> s.toDoubleOrNull() as T?
    else -> null
}
inline fun <reified T> typeName(): String = when (T::class) { String::class -> "String"; Int::class -> "Int"; else -> "other" }
inline fun <reified A, reified B> both(x: Any): String = (if (x is A) "A" else "") + (if (x is B) "B" else "")
fun main() {
    val l = listOf<Shape>(Sq(2), Rect(2, 3), Sq(3))
    println(l.sumOf2<Sq>()); println(l.sumOf2<Rect>()); println(l.sumOf2<Shape>())
    println("s".castOrNull<String>()); println(1.castOrNull<String>()); println(null.castOrNull<Int>())
    println(parse<Int>("12")); println(parse<Long>("x")); println(parse<Double>("1.5"))
    println(typeName<String>() + typeName<Int>() + typeName<List<Int>>())
    println(both<Int, Number>(3)); println(both<String, Number>(3)); println(both<String, CharSequence>("a"))
}
