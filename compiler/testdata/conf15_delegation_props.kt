// property delegates: observable, vetoable, map, custom delegates, class delegation
import kotlin.properties.Delegates
import kotlin.reflect.KProperty
class Logger { var log = ArrayList<String>() }
class User {
    var name: String by Delegates.observable("init") { _, old, new -> println("name: $old -> $new") }
    var age: Int by Delegates.vetoable(0) { _, _, new -> new >= 0 }
}
class Cfg(map: Map<String, Any?>) { val host: String by map; val port: Int by map; val opt: String? by map }
class Upper { private var v = ""; operator fun getValue(t: Any?, p: KProperty<*>) = v.uppercase() + "(" + p.name + ")"; operator fun setValue(t: Any?, p: KProperty<*>, x: String) { v = x } }
class Holder { var s by Upper(); val ro by Upper() }
interface Printer { fun print(s: String): String; val id: Int }
class Basic : Printer { override fun print(s: String) = "basic:$s"; override val id = 1 }
class Fancy(p: Printer) : Printer by p { override fun print(s: String) = "fancy(" + "x" + ")" }
class Tagged(p: Printer) : Printer by p
class MutableMapUser(val m: MutableMap<String, Any?>) { var a: Int by m; var b: String by m }
fun main() {
    val u = User(); u.name = "bob"; u.name = "amy"; println(u.name); u.age = 5; u.age = -1; println(u.age); u.age = 7; println(u.age)
    val c = Cfg(mapOf("host" to "h", "port" to 80, "opt" to null)); println(c.host + c.port + c.opt)
    val h = Holder(); h.s = "abc"; println(h.s); println(h.ro)
    println(Fancy(Basic()).print("q")); println(Fancy(Basic()).id); println(Tagged(Basic()).print("z")); println(Tagged(Basic()).id)
    val mm = mutableMapOf<String, Any?>("a" to 1, "b" to "x"); val mu = MutableMapUser(mm); mu.a = 2; mu.b = "y"; println(mm); println(mu.a)
    var local by Delegates.observable(0) { p, o, n -> println("${p.name} $o -> $n") }; local = 5; local += 1
    val lz by lazy { println("lazy computed"); 9 }; println("before"); println(lz); println(lz)
}
