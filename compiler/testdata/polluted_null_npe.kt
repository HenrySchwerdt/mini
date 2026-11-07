// Heap pollution with null: `arrayOfNulls<String>(n) as Array<String>`, `listOf<String?>(null) as List<String>` and a generic field are
// legal casts (a null passes an erased cast, like the JVM's checkcast). The value may flow around as the null it is (printed, compared,
// concatenated, stored in a nullable), but its dereference must throw a NullPointerException, not read the zero of a String or call
// through a null pointer.
class Box(val v: Int) { fun get() = v }
class G<T>(var x: T)
class Holder(var s: String, var b: Box)

fun attempt(name: String, f: () -> Unit) {
    try {
        f()
        println(name + ": ok")
    } catch (e: NullPointerException) {
        println(name + ": NullPointerException")
    }
}

fun lenOf(s: String): Int = s.length
fun first(a: Array<String>): String = a[0]
fun keep(s: String?): String? = s
fun <T : Any> firstOf(a: Array<T>): T = a[0]

@Suppress("UNCHECKED_CAST")
fun main() {
    // reads of a reified array: every kind of dereference
    attempt("string length") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0].length) }
    attempt("string hashCode") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0].hashCode()) }
    attempt("string get") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0][0]) }
    attempt("string substring") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0].substring(0, 0)) }
    attempt("string compareTo") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0].compareTo("x")) }
    attempt("class field") { val a = arrayOfNulls<Box>(2) as Array<Box>; println(a[0].v) }
    attempt("class call") { val a = arrayOfNulls<Box>(2) as Array<Box>; println(a[0].get()) }
    attempt("Any hashCode") { val a = arrayOfNulls<Any>(2) as Array<Any>; println(a[0].hashCode()) }
    attempt("Int unbox") { val a = arrayOfNulls<Int>(2) as Array<Int>; println(a[0] + 1) }
    attempt("for loop") { val a = arrayOfNulls<Box>(2) as Array<Box>; for (b in a) println(b.v) }
    attempt("for loop string") { val a = arrayOfNulls<String>(2) as Array<String>; for (s in a) println(s.length) }
    attempt("field write") { val a = arrayOfNulls<Box>(2) as Array<Box>; val h = Holder("x", Box(1)); h.b = a[0]; println("stored") }

    // erased generics
    attempt("list string") { val l = listOf<String?>(null) as List<String>; println(l[0].length) }
    attempt("list class") { val l = listOf<Box?>(null) as List<Box>; println(l[0].v) }
    attempt("list call") { val l = listOf<Box?>(null) as List<Box>; println(l[0].get()) }
    attempt("generic field string") { val g = G<String?>(null) as G<String>; println(g.x.length) }
    attempt("generic field class") { val g = G<Box?>(null) as G<Box>; println(g.x.v) }
    attempt("map value") { val m = hashMapOf<Int, String?>(1 to null) as Map<Int, String>; println(m.getValue(1).length) }

    // through a local, a parameter, a return value
    attempt("local") { val a = arrayOfNulls<String>(2) as Array<String>; val s = a[0]; println("got"); println(s.length) }
    attempt("local reassigned") { val a = arrayOfNulls<String>(2) as Array<String>; var s = "ok"; println(s.length); s = a[1]; println(s.length) }
    attempt("local in loop") { val a = arrayOfNulls<String>(3) as Array<String>; a[0] = "a"; a[1] = "b"; var n = 0; for (i in 0 until 3) { val s = a[i]; n += s.length }; println(n) }
    attempt("parameter") { val a = arrayOfNulls<String>(2) as Array<String>; println(lenOf(a[0])) }
    attempt("return") { val a = arrayOfNulls<String>(2) as Array<String>; println(first(a).length) }
    attempt("generic bound") { val a = arrayOfNulls<Box>(2) as Array<Box>; println(firstOf(a).v) }
    attempt("conditional") { val a = arrayOfNulls<String>(2) as Array<String>; val s = if (a.size > 1) a[0] else "x"; println(s.length) }

    // what the JVM accepts: null flows, it is only not dereferenced
    attempt("print") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0]) }
    attempt("concat") { val a = arrayOfNulls<String>(2) as Array<String>; println("x" + a[0]) }
    attempt("template") { val a = arrayOfNulls<String>(2) as Array<String>; println("v=${a[1]}") }
    attempt("equals") { val a = arrayOfNulls<String>(2) as Array<String>; println(a[0] == "x"); println(a[0] == a[1]) }
    attempt("assign") { val a = arrayOfNulls<String>(2) as Array<String>; val s: String = a[0]; println("assigned") }
    attempt("nullable param") { val a = arrayOfNulls<String>(2) as Array<String>; println(keep(a[0])) }
    attempt("safe call") { val a = arrayOfNulls<String>(2) as Array<String?>; println(a[0]?.length) }
    attempt("elvis") { val a = arrayOfNulls<String>(2) as Array<String?>; println(a[0]?.length ?: -1) }
    attempt("filled") { val a = arrayOfNulls<String>(2) as Array<String>; a[0] = "ab"; a[1] = "cde"; println(a[0].length + a[1].length) }
    attempt("copy") { val a = arrayOfNulls<String>(2) as Array<String>; val b = arrayOfNulls<String>(2) as Array<String>; b[0] = a[0]; println("copied") }
}
