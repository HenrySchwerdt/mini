// Annotation classes and annotations with arguments: kept in the IR, no code generated.
@file:Suppress("UNUSED_PARAMETER")

import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

enum class Level { LOW, HIGH }

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tag(val name: String, val n: Int = 0, val level: Level = Level.LOW)

annotation class Names(vararg val names: String)

annotation class Refs(val type: kotlin.reflect.KClass<*>, val ids: IntArray = [])

@Repeatable
annotation class Note(val text: String)

@Tag("x", 2)
class A(@Tag("p") val p: Int) {
    @Tag(name = "m", level = Level.HIGH)
    fun f() = p + 1

    @Tag("prop")
    val q = 5

    @get:JvmName("getQQ")
    val r: Int = 7
}

@Names("a", "b", "c")
@Refs(String::class, [1, 2, 3])
@Note("one")
@Note("two")
object Holder {
    @Suppress("UNUSED_VARIABLE")
    fun g(@Tag("arg") x: Int): Int {
        @Suppress("UNUSED_VARIABLE") val unused = 3
        return x * 2
    }
}

@Suppress("NOTHING_TO_INLINE")
@JvmName("renamed")
inline fun h(a: Int) = a + 1

@Deprecated("old", ReplaceWith("h(a)"))
fun old(a: Int) = a

@Tag("iface")
interface Marked { @Tag("im") fun m(): Int }

@Tag("data")
data class D(@Tag("c") val c: Int)

fun main() {
    val a = A(1)
    println(a.f() + a.q + a.r)
    println(Holder.g(4))
    println(h(2))
    println(old(9))
    println(D(3))
    val m = object : Marked { override fun m() = 8 }
    println(m.m())
    @Suppress("UNCHECKED_CAST")
    val l = listOf(1, 2) as List<Int>
    println(l)
}
