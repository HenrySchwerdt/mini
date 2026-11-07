// An unchecked cast can put a value of the wrong type into a generic container. Reading it as the declared type must throw a
// ClassCastException (the checkcast of the JVM), never treat the object as a String / Array / P (whose layout it does not have).
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

class Box<T>(val v: T)
class P(val x: Int)
interface Shape { fun area(): Int }
class Sq(val s: Int) : Shape { override fun area() = s * s }

@Suppress("UNCHECKED_CAST")
fun <T> unsafe(x: Any?): T = x as T

@Suppress("UNCHECKED_CAST")
fun main() {
    attempt("list element") { val l = listOf<Any>(1) as List<String>; println(l[0].length) }
    attempt("list for") { val l = listOf<Any>(1, 2) as List<String>; for (s in l) println(s.length) }
    attempt("box field") { val b = Box<Any>(P(1)) as Box<String>; println(b.v.length) }
    attempt("box local") { val b = Box<Any>(P(1)) as Box<String>; val s: String = b.v; println(s.length) }
    attempt("generic result") { val s: String = unsafe(P(3)); println(s.length) }
    attempt("generic result class") { val p: P = unsafe("str"); println(p.x) }
    attempt("generic int") { val n: Int = unsafe("str"); println(n + 1) }
    attempt("generic interface") { val s: Shape = unsafe(P(3)); println(s.area()) }
    attempt("array element") { val a = arrayOf<Any>(P(1)) as Array<String>; println(a[0].length) }
    attempt("map value") { val m = mapOf<Any, Any>("a" to "b") as Map<String, Int>; println(m["a"]!! + 1) }
    attempt("lambda parameter") { val f = { s: String -> s.length } as (Any) -> Int; println(f(5)) }
    attempt("null element") { val l = listOf<String?>(null) as List<String>; println(l[0].length) }
    attempt("subclass is fine") { val l: List<Shape> = listOf(Sq(2), Sq(3)); var t = 0; for (s in l) t += s.area(); println(t) }
    attempt("any is fine") { val l = listOf<Any>("a", 1) as List<Any>; println(l[1]) }
    attempt("same type is fine") { val l = listOf("abc"); println(l[0].length); println(Box(5).v + 1) }
}
