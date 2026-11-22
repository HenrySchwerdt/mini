// nested vs inner classes, this@Outer, shadowing, anonymous objects capturing, inner generic
class Outer(val name: String) {
    val tag = "outer-tag"
    class Nested { fun hi() = "nested" }
    inner class Inner(val n: Int) { val tag = "inner-tag"; fun who() = "inner $n of $name; ${this.tag} ${this@Outer.tag}"; inner class Deep { fun path() = "deep in ${this@Inner.n} in ${this@Outer.name}" } }
    fun make(n: Int) = Inner(n)
    fun lambdaCapture(): () -> String = { "captured $name ${this.name}" }
    fun obj() = object { fun f() = name + "!" }.f()
    interface Cb { fun call(): String }
    fun cb(): Cb = object : Cb { override fun call() = "cb of $name" }
    companion object { const val K = "k"; fun create() = Outer("made") }
}
class Gen<T>(val t: T) { inner class In(val u: T) { fun both() = "$t/$u" } }
fun Outer.ext() = "ext on $name ${this.tag}"
fun main() {
    println(Outer.Nested().hi()); val o = Outer("O"); val i = o.make(3); println(i.who()); println(o.Inner(4).n); println(i.Deep().path()); println(o.Inner(5).Deep().path())
    println(o.lambdaCapture()()); println(o.obj()); println(o.cb().call()); println(Outer.K); println(Outer.create().name); println(o.ext())
    println(Gen("g").In("u").both()); val gi = Gen(1).In(2); println(gi.both())
    val x = 10; fun local() = x + 1
    println(local()); class L { fun v() = x * 2 }; println(L().v())
    with(o) { println(name + tag) }; println(o.run { name.length }); println(o.let { it.name })
}
