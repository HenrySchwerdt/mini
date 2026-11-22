// member vs extension resolution, extension static dispatch, extension on nullable/generic, extension properties, shadowed names
open class Base { fun who() = "member"; open fun virt() = "base virt" }
class Derived : Base() { override fun virt() = "derived virt" }
fun Base.who() = "extension"
fun Base.virt2() = "ext base"
fun Derived.virt2() = "ext derived"
fun Base.extOnly() = "extOnly " + virt()
val Base.prop get() = "prop " + who()
val String.firstChar get() = if (isEmpty()) '?' else this[0]
fun <T> List<T>.second(): T? = if (size > 1) this[1] else null
fun <T : Comparable<T>> List<T>.isSortedAsc(): Boolean { for (i in 1 until size) if (this[i - 1] > this[i]) return false; return true }
fun Int?.orZero() = this ?: 0
fun CharSequence.vowels() = count { it in "aeiou" }
fun Any.describe() = "any"
fun String.describe() = "string"
fun <T> T.describeG() = "generic"
fun Int.describeG() = "int"
class Host { fun String.local() = this + "@" + id; val id = "host"; fun use() = "abc".local() }
fun main() {
    val b: Base = Derived(); println(b.who()); println(b.virt()); println(b.virt2()); println((b as Derived).virt2()); println(Derived().extOnly()); println(b.prop)
    println("hey".firstChar); println("".firstChar); println(listOf(1, 2, 3).second()); println(listOf(1).second()); println(listOf(1, 2, 2).isSortedAsc()); println(listOf("b", "a").isSortedAsc())
    val n: Int? = null; println(n.orZero()); println(5.orZero()); println("education".vowels()); println(StringBuilder("aeb").vowels())
    val a: Any = "s"; println(a.describe()); println("s".describe()); println(1.describeG()); println("s".describeG()); println(null.describeG()); println(Host().use())
    println(listOf(1, 2).let { it.size }); println(5.let { it + 1 }.let { it * 2 }); println("x".also { println("also $it") }.length); println(mutableListOf(1).apply { add(2) }); println(StringBuilder().apply { append("a"); append(1) }.toString())
    println(3.takeIf { it > 2 }); println(3.takeUnless { it > 2 }); println(with(StringBuilder()) { append("w"); length }); println(run { 1 + 1 }); println("abc".run { length })
}
