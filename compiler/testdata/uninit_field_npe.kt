// A non-null property read before its initializer ran (a virtual call from the constructor of a superclass, two objects that read each
// other while they initialize) must throw a NullPointerException like on the JVM, not dereference the zero of the allocation.
fun attempt(name: String, f: () -> Unit) {
    try {
        f()
        println(name + ": ok")
    } catch (e: NullPointerException) {
        println(name + ": NullPointerException")
    }
}

open class Base {
    init { show() }
    open fun show() {}
}

class UsesString : Base() { val s: String = "x"; override fun show() { println(s.length) } }
class UsesArray : Base() { val arr = IntArray(3); override fun show() { println(arr.size) } }
class UsesList : Base() { val l = ArrayList<String>(); override fun show() { l.add("q") } }
class UsesObject : Base() { val o: Any = Any(); override fun show() { println(o.hashCode() != 0) } }
class UsesPair : Base() { val p = Pair(1, 2); override fun show() { println(p.first) } }
class UsesInt : Base() { val n: Int = 5; override fun show() { println(n) } }
class UsesNullable : Base() { val s: String? = "x"; override fun show() { println(s) } }
class UsesLateinit : Base() { lateinit var s: String; override fun show() { println(if (::s.isInitialized) "set" else "unset") } }
class Fine : Base() { val s: String = "fine"; fun len() = s.length }

class Own {
    val a = describe()
    val b: String = "b"
    fun describe(): Int = b.length
}

object First { val p: Pair<Int, Int> = Pair(Second.q.first, 1) }
object Second { val q: Pair<Int, Int> = Pair(First.p.first, 2) }

fun main() {
    attempt("string") { UsesString() }
    attempt("array") { UsesArray() }
    attempt("list") { UsesList() }
    attempt("object") { UsesObject() }
    attempt("pair") { UsesPair() }
    attempt("int") { UsesInt() }
    attempt("nullable") { UsesNullable() }
    attempt("lateinit") { UsesLateinit() }
    attempt("own") { Own() }
    // (a failing object initialiser surfaces as ExceptionInInitializerError, as on the JVM; its cause is the NullPointerException)
    attempt("objects") { try { println(First.p.first) } catch (e: ExceptionInInitializerError) { throw (e.cause as NullPointerException) } }
    println(Fine().len())
}
