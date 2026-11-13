// lateinit access before init, error messages, local lateinit, isInitialized, by lazy exception retry
class R { lateinit var name: String; lateinit var items: List<Int>; var counter = 0
    val lz: Int by lazy { counter++; if (counter < 2) throw IllegalStateException("first fails"); counter * 10 } }
object O { lateinit var cfg: String }
fun main() {
    val r = R()
    try { println(r.name) } catch (e: UninitializedPropertyAccessException) { println(e.message) }
    try { println(r.items.size) } catch (e: UninitializedPropertyAccessException) { println(e.message) }
    r.name = "n"; println(r.name)
    try { println(O.cfg) } catch (e: UninitializedPropertyAccessException) { println(e.message) }
    lateinit var local: String
    try { println(local) } catch (e: UninitializedPropertyAccessException) { println(e.message) }
    local = "ok"; println(local)
    try { println(r.lz) } catch (e: IllegalStateException) { println(e.message) }
    println(r.lz); println(r.lz); println(r.counter)
    val lz2 = lazy { println("init"); "v" }; println(lz2.isInitialized()); println(lz2.value); println(lz2.isInitialized()); println(lz2.value)
    var x: Int by kotlin.properties.Delegates.notNull(); try { println(x) } catch (e: IllegalStateException) { println(e.message) }; x = 3; println(x)
}
