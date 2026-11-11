import kotlin.enums.EnumEntries
import kotlin.enums.enumEntries

enum class Color { RED, GREEN, BLUE }

inline fun <reified E : Enum<E>> names(): List<String> = enumEntries<E>().map { it.name }

fun main() {
    val e: EnumEntries<Color> = enumEntries<Color>()
    println(e)
    println(e.size)
    println(e[1])
    println(names<Color>())
    println(e == Color.entries)
}
