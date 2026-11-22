// delegates: provideDelegate, delegate to another property, getValue extension, delegated val in interface impl
import kotlin.reflect.KProperty
class Logged<T>(var v: T, val name: String) { operator fun getValue(r: Any?, p: KProperty<*>): T { println("get $name"); return v }; operator fun setValue(r: Any?, p: KProperty<*>, x: T) { println("set $name = $x"); v = x } }
class Provider<T>(val init: T) { operator fun provideDelegate(r: Any?, p: KProperty<*>): Logged<T> { println("provide ${p.name}"); return Logged(init, p.name) } }
class Box { var a by Provider(1); val b by Provider("s"); var counter = 0; var c by this::counter; val d: Int by ::counter }
class Pt(var x: Int = 0) { var alias by ::x }
operator fun Map<String, Int>.getValue(t: Any?, p: KProperty<*>): Int = this[p.name] ?: -1
object Settings { val width by mapOf("width" to 640) ; val missing by mapOf("w" to 1) }
interface HasName { val name: String }
class Impl(n: String) : HasName { override val name by lazy { n.uppercase() } }
class Cache { private val store = HashMap<String, Int>(); var hits = 0
    fun get(k: String, compute: () -> Int): Int = store.getOrPut(k) { hits++; compute() } }
fun main() {
    val bx = Box(); println(bx.a); bx.a = 5; println(bx.a); println(bx.b); bx.c = 3; println(bx.counter); println(bx.d); bx.counter = 8; println(bx.c)
    val p = Pt(2); p.alias = 9; println(p.x); println(p.alias); println(Settings.width); println(Settings.missing); println(Impl("abc").name); val i = Impl("q"); println(i.name + i.name)
    val c = Cache(); println(c.get("a") { 5 }); println(c.get("a") { 6 }); println(c.hits); val lz = lazy(LazyThreadSafetyMode.NONE) { println("lazy"); 4 }; println(lz.value + lz.value)
    val q: Int by lazy { 7 }; println(q)
}
