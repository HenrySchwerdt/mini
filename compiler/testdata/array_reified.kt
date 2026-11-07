// Object arrays record the element type they were created with (docs/memory-model.md, "Reified object arrays"): an `Array<C>` value is
// read without any check, so the checks happen where an array enters such a type (a cast, an erased result) and on stores through a
// view that does not know the element type (an `Array<T>` of a generic function). Every failure is a ClassCastException.
open class Shape(val n: Int)
class Sq(n: Int) : Shape(n)
enum class Color { RED, GREEN }

class Holder<T>(val items: Array<T>) {
    fun set(i: Int, v: T) { items[i] = v }
}

fun <T> same(a: Array<T>): Array<T> = a
fun <T> bad(): Array<T> = arrayOfNulls<Any?>(1) as Array<T>
fun joined(vararg xs: String): String = xs[0] + xs[xs.size - 1]

fun attempt(name: String, f: () -> Unit) {
    try {
        f()
        println(name + ": ok")
    } catch (e: ClassCastException) {
        println(name + ": ClassCastException")
    } catch (e: NullPointerException) {
        println(name + ": NullPointerException")
    }
}

@Suppress("UNCHECKED_CAST")
fun main() {
    attempt("exact") { val a: Array<Shape> = arrayOf(Shape(1)); println(a[0].n) }
    attempt("out view") { val a: Array<out Shape> = arrayOf(Sq(2)); println(a[0].n) }
    attempt("loop") { var t = 0; for (s in arrayOf(Sq(1), Sq(2))) t += s.n; println(t) }
    attempt("array cast of another element type") { val a = arrayOf<Any>("x") as Array<String>; println(a[0].length) }
    attempt("array cast to a supertype") { val a = arrayOf(Sq(1)) as Array<Shape>; println(a[0].n) }
    attempt("array cast to an out type") { val a = (arrayOf(Sq(1)) as Any) as Array<out Shape>; println(a[0].n) }
    attempt("array cast of null slots") { val a = arrayOfNulls<String>(1) as Array<String>; println(a[0].length) }
    attempt("nullable slots are fine") { val a = arrayOfNulls<String>(2); a[0] = "x"; println(a[0]?.length) }
    val strings: Array<String> = arrayOf("x")
    attempt("store through a generic view") { (Holder(strings) as Holder<Any>).set(0, 1) }
    println(strings[0])
    attempt("generic store of the right type") { Holder(strings).set(0, "y"); println(strings[0].length) }
    attempt("generic result") { val s: Array<String> = same(arrayOf("q")); println(s[0].length) }
    attempt("array of unknown element type") { val s: Array<String> = bad(); println(s[0].length) }
    attempt("copyOf") { val a = arrayOf("a", "b").copyOf(); println(a[1].length) }
    attempt("copyOf with room") { val a: Array<String?> = arrayOf("a").copyOf(3); println(a[0]!!.length + a.size) }
    attempt("copyOf with room is nullable") { val a = arrayOf("a").copyOf(3) as Array<String>; println(a.size) }
    attempt("copyOfRange") { val a = arrayOf("a", "bb", "ccc").copyOfRange(1, 3); println(a[1].length) }
    attempt("plus") { val a = arrayOf("a", "b").plus("ccc"); println(a[2].length) }
    attempt("plus array") { val a = arrayOf("a") + arrayOf("dd"); println(a[1].length) }
    attempt("toTypedArray") { val a: Array<String> = listOf("a", "bbb").toTypedArray(); println(a[1].length) }
    attempt("sliceArray") { val a = arrayOf(Sq(1), Sq(2), Sq(3)).sliceArray(1..2); println(a[1].n) }
    attempt("reversedArray") { val a = arrayOf(Sq(1), Sq(2)).reversedArray(); println(a[0].n) }
    attempt("sortedArray") { val a = arrayOf("b", "a").sortedArray(); println(a[0].length) }
    attempt("boxed Int") { val a = intArrayOf(1, 2, 3).toTypedArray(); println(a[2] + 1) }
    attempt("Array constructor") { val a = Array(3) { Sq(it) }; println(a[2].n) }
    attempt("emptyArray") { val a = emptyArray<String>(); println(a.size) }
    attempt("enum values") { println(Color.values()[1]); for (c in enumValues<Color>()) print(c); println() }
    attempt("vararg") { println(joined("a", "b", "cc")) }
    attempt("spread") { val a = arrayOf("p", "q"); println(joined(*a)) }
    attempt("nested") { val a = arrayOf(arrayOf("a", "bb"), arrayOf("c")); println(a[0][1].length + a[1][0].length) }
    attempt("is") {
        val o: Any = arrayOf("a")
        println((o is Array<String>).toString() + " " + (o is Array<*>) + " " + (o is Array<Int>) + " " + ((arrayOf<Any>("a") as Any) is Array<String>))
    }
    attempt("as?") { val o: Any = arrayOf("a"); println((o as? Array<Int>) == null); println((o as? Array<String>)!![0].length) }
    attempt("list of arrays") { val l = listOf(arrayOf("a"), arrayOf("bb")); println(l[1][0].length) }
    attempt("array in a generic box") { val b = Holder(arrayOf(Sq(1))); println(b.items[0].n) }
}
