// companion and object initialization time, const, lateinit, object in object, init of top-level properties
val top1 = run { println("top1"); 1 }
object Config { val name = "cfg".also { println("Config init") }; var count = 0; fun inc() = ++count }
class K {
    companion object { init { println("K companion init") }; val inst = K(); const val C = 5; var created = 0 }
    init { created++ ; println("K instance, created=$created") }
}
class Outer { object Inner { val v = 10.also { println("Inner init") } }; companion object Named { fun f() = "named" } }
interface HasCompanion { companion object { fun make() = "made" } }
class LateTest { lateinit var s: String; fun ok() = ::s.isInitialized }
enum class E(val v: Int) { A(1), B(2); init { println("E init " + name) }; companion object { val all = values().toList() } }
fun main() {
    println("main start")
    println(Config.name); Config.inc(); Config.inc(); println(Config.count)
    println(K.C); println(K.created); K(); println(K.created)
    println(Outer.Inner.v); println(Outer.f()); println(Outer.Named.f()); println(HasCompanion.make())
    val l = LateTest(); println(l.ok()); l.s = "set"; println(l.ok()); println(l.s)
    println(E.all); println(E.B.v)
    println(top1)
}
